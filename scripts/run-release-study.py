#!/usr/bin/env python3
"""Resume a pinned dedicated-server release study and summarize completed blocks."""

import argparse
from collections import defaultdict
from hashlib import file_digest
import json
from pathlib import Path
import re
from statistics import median
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "mc-tooling" / "mc-lab"))
from mc_lab.design import balanced_orders
from mc_lab.profile import LabConfig
from mc_lab.stats import summarize_paired_changes

ARMS = {
    "off": ("off", False),
    "off-repeat": ("off", False),
    "combined-cache": ("combined-cache", False),
    "hash-tree-cache": ("hash-tree-cache", False),
    "adaptive-cache": ("adaptive-cache", False),
    "adaptive-repeat": ("adaptive-cache", False),
    "adaptive-batch": ("adaptive-cache", True),
}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def save(path: Path, data) -> None:
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(data, indent=2) + "\n")
    temp.replace(path)


def collect(path: Path, spec: dict) -> dict:
    rows = [json.loads(line) for line in (path / "windows.jsonl").read_text().splitlines()]
    measured = [row for row in rows if row["batch"] >= 0]
    if len(measured) != spec["trials"]:
        raise RuntimeError(f"Incomplete windows under {path}")
    expected = 0 if spec["dataset"] == "dense-pivot" else 12 if spec.get("mixed") else 64
    if any(row["crafts"] != expected or row["result_updates"] <= 0 for row in measured):
        raise RuntimeError(f"Unexpected measured results under {path}")
    if expected == 0 and any(row["result_updates"] != 32 for row in measured):
        raise RuntimeError(f"Unexpected number of dense-pivot updates under {path}")
    result = {
        "cpu_ns": median(row["cpu_ns"] for row in measured),
        "wall_ns": median(row["wall_ns"] for row in measured),
        "result_updates": median(row["result_updates"] for row in measured),
        "quality_passes": sum(row["quality_pass"] for row in measured),
        "result_path": str(path),
        "datapack_sha256": json.loads((path / "run.json").read_text())["datapack_sha256"],
    }
    if spec["diagnostic"]:
        for stage in ("initial", "reloaded"):
            contents = (path / f"heap-{stage}.txt").read_text()
            result[f"live_heap_{stage}_bytes"] = int(re.search(r"^Total\s+\d+\s+(\d+)", contents, re.M)[1])
        result["setup"] = (path / "setup.log").read_text().splitlines()
    return result


def summarize(output: Path, plan: dict) -> None:
    blocks = defaultdict(lambda: defaultdict(dict))
    diagnostics = {}
    for spec in plan["runs"]:
        checkpoint = output / (spec["id"] + ".json")
        if checkpoint.exists():
            result = json.loads(checkpoint.read_text())
            if spec["diagnostic"]:
                diagnostics[spec["id"]] = result
            else:
                blocks[spec["dataset"]][spec["block"]][spec["arm"]] = result

    effects = {}
    for dataset, dataset_blocks in blocks.items():
        expected = {spec["arm"] for spec in plan["runs"] if spec["dataset"] == dataset and not spec["diagnostic"]}
        full = [arms for arms in dataset_blocks.values() if set(arms) == expected]
        if len({result["datapack_sha256"] for block in full for result in block.values()}) > 1:
            raise RuntimeError(f"{dataset} was measured with mixed datapacks")
        effects[dataset] = {}
        baseline = "adaptive-cache" if plan.get("table_craft") else "off"
        for arm in sorted(expected - {baseline}):
            control = "adaptive-cache" if arm in ("adaptive-batch", "adaptive-repeat") else "off"
            delta = [100 * (block[arm]["cpu_ns"] / block[control]["cpu_ns"] - 1) for block in full]
            effects[dataset][arm] = {
                **summarize_paired_changes(delta, minimum_gain_percent=None if arm.endswith("-repeat") else 5),
                "control": control,
                "paired_deltas": delta,
            }
    save(output / "summary.json", {"effects": effects, "diagnostics": diagnostics})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    for flag in ("install", "packets", "mod"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--blocks", type=int, default=6)
    study = parser.add_mutually_exclusive_group()
    study.add_argument("--mixed", action="store_true", help="measure mixed crafting and diagnose dense tree setup")
    study.add_argument("--batching", action="store_true", help="compare repeated adaptive controls and 3×3 batching")
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if args.blocks < 1:
        parser.error("--blocks must be at least one")
    if len({jar.name for jar in [args.mod, *args.extra_mod]}) != len(args.extra_mod) + 1:
        parser.error("use distinct filenames for the mod and extra mod JARs")
    args.output.mkdir(parents=True, exist_ok=True)
    inputs = {key: sha256(path) for key, path in {
        "experiment": args.mod, "packets": args.packets, "harness": ROOT / "benchmarks/server_baseline.py",
        "launcher": args.install / "fabric-server-launch.jar",
        "launcher_properties": args.install / "fabric-server-launcher.properties",
    }.items()}
    runs = []
    original_arms = tuple(arm for arm in ARMS if arm != "adaptive-repeat")
    scenarios = (
        ("vanilla", original_arms), ("same-slots", original_arms), ("dense-pivot", original_arms[:5]),
    )
    diagnostic_arms = ("off", "combined-cache", "hash-tree-cache", "adaptive-cache")
    if args.mixed:
        mixed_arms = ("off", "off-repeat", "adaptive-cache")
        scenarios = (("vanilla", mixed_arms), ("same-slots", mixed_arms))
        diagnostic_arms = ("adaptive-cache", "off")
    if args.batching:
        scenarios = (("vanilla", ("adaptive-cache", "adaptive-repeat", "adaptive-batch")),)
        diagnostic_arms = ()
    for dataset, arms in scenarios:
        count = min(2, args.blocks) if dataset == "dense-pivot" else args.blocks
        for block, order in enumerate(balanced_orders(arms, count, 263)):
            for arm in order:
                runs.append(dict(id=f"{dataset}-{block:02d}-{arm}", dataset=dataset, block=block,
                                 arm=arm, trials=4, diagnostic=False, **({"mixed": True} if args.mixed else {})))
    runs.sort(key=lambda spec: spec["block"])
    for arm in diagnostic_arms:
        runs.insert(0, dict(id=f"heap-dense-{arm}", dataset="dense-pivot", block=0, arm=arm,
                            trials=2, diagnostic=True))
    plan = {"inputs": inputs, "lab": LabConfig.from_env().as_dict(), "runs": runs}
    if args.batching:
        plan["table_craft"] = True
    if args.extra_mod:
        plan["extra_mods"] = {jar.name: sha256(jar) for jar in args.extra_mod}
    plan_file = args.output / "plan.json"
    if plan_file.exists() and json.loads(plan_file.read_text()) != plan:
        raise SystemExit("Existing plan differs. Use a new study directory for changed inputs.")
    save(plan_file, plan)
    for spec in runs:
        checkpoint = args.output / (spec["id"] + ".json")
        if checkpoint.exists():
            continue
        mode, batch = ARMS[spec["arm"]]
        command = [sys.executable, str(ROOT / "benchmarks/server_baseline.py"),
                   "--accept-eula", "--install", str(args.install.resolve()), "--mod", str(args.mod.resolve()),
                   "--packets", str(args.packets.resolve()), "--dataset", spec["dataset"], "--lookup", mode,
                   "--trials", str(spec["trials"]), "--warmup", "3"]
        if batch:
            command.append("--batch-crafting")
        for jar in args.extra_mod:
            command.extend(("--extra-mod", str(jar.resolve())))
        if args.batching:
            command.extend(("--table-craft", "--check-remainders"))
        elif spec.get("mixed"):
            command.append("--mixed-crafting")
        elif spec["dataset"] == "vanilla":
            command.append("--four-slot-craft")
        if spec["diagnostic"]:
            command.append("--heap-diagnostic")
        attempt = 0
        while (log := args.output / f"{spec['id']}.{attempt}.log").exists():
            attempt += 1
        for retry in range(3):
            with log.open("x") as stream:
                process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=480)
            contents = log.read_text()
            if process.returncode == 0:
                break
            # Offline loopback sessions still start Mojang's asynchronous key request.
            diagnostics = contents.partition("RuntimeError: Unexpected server diagnostics:\n")[2].splitlines()
            if retry == 2 or len(diagnostics) != 1 or "Failed to request Minecraft Services public key" not in diagnostics[0]:
                raise SystemExit(f"{spec['id']} exited {process.returncode}. See {log}")
            attempt += 1
            print(f"Retrying {spec['id']} after a Minecraft Services key request failed", flush=True)
            log = args.output / f"{spec['id']}.{attempt}.log"
        path = Path(re.search(r"Results: (.*?);", contents)[1])
        save(checkpoint, collect(path, spec))
        summarize(args.output, plan)
        print(f"Finished {spec['id']}", flush=True)


if __name__ == "__main__":
    main()
