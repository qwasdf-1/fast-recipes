#!/usr/bin/env python3
"""Run and resume counterbalanced dedicated station comparisons."""

import argparse
from hashlib import file_digest
import json
from pathlib import Path
from statistics import median
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "benchmarks/station_parity.py"
sys.path.insert(0, str(ROOT.parent / "mc-tooling/mc-lab"))

from mc_lab.design import balanced_orders
from mc_lab.profile import LabConfig
from mc_lab.stats import summarize_paired_changes

MENUS = ("smithing", "stonecutting")
ARMS = ("off", "off-repeat", "indexed")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def save(path: Path, value) -> None:
    pending = path.with_suffix(".tmp")
    pending.write_text(json.dumps(value, indent=2) + "\n")
    pending.replace(path)


def collect(samples: Path, plan: dict, arm: str) -> dict:
    metadata = json.loads(samples.with_suffix(".meta.json").read_text())
    expected = {
        "indexed": arm == "indexed", "recrafted": plan["recrafted"], "extra_recipes_per_station": plan["recipes"],
        "windows": plan["windows"], "warmup": plan["warmup"], "lab": plan["lab"],
        "mod_sha256": plan["jar_sha256"], "extra_mods": plan["extra_mods"],
    }
    if any(metadata[key] != value for key, value in expected.items()):
        raise RuntimeError(f"Run metadata differs from the plan: {samples}")
    results = {"samples": str(samples), "fixture_sha256": metadata["fixture_sha256"]}
    rows = [json.loads(line) for line in samples.read_text().splitlines()]
    if len(rows) != (plan["windows"] + plan["warmup"]) * len(MENUS):
        raise RuntimeError(f"Unexpected row count: {samples}")
    for menu in MENUS:
        measured = [row for row in rows if row["menu"] == menu and row["batch"] >= 0]
        if [row["batch"] for row in measured] != list(range(plan["windows"])) or any(
                row["verified_results"] != metadata["transactions_per_window"][menu] for row in measured):
            raise RuntimeError(f"Incomplete {menu} verification: {samples}")
        results[menu] = {"cpu_ns": median(row["cpu_ns"] for row in measured),
                         "wall_ns": median(row["wall_ns"] for row in measured),
                         "quality_passes": sum(row["quality_pass"] for row in measured),
                         "windows": len(measured), "transactions": sum(row["verified_results"] for row in measured)}
    return results


def summarize(output: Path, plan: dict) -> None:
    complete = []
    for block in range(len(plan["orders"])):
        paths = {arm: output / f"{block:02}-{arm}.json" for arm in ARMS}
        if all(path.is_file() for path in paths.values()):
            complete.append({arm: json.loads(path.read_text()) for arm, path in paths.items()})
    if len({run["fixture_sha256"] for block in complete for run in block.values()}) > 1:
        raise RuntimeError("Station study mixed recipe fixtures")
    summary = {menu: {} for menu in MENUS}
    for menu in MENUS:
        for arm in ARMS[1:]:
            deltas = [100 * (block[arm][menu]["cpu_ns"] / block["off"][menu]["cpu_ns"] - 1)
                      for block in complete]
            summary[menu][arm] = {"paired_deltas": deltas, **summarize_paired_changes(
                deltas, minimum_gain_percent=5 if arm == "indexed" else None)}
    totals = {metric: sum(run[menu][metric] for block in complete for run in block.values() for menu in MENUS)
              for metric in ("windows", "quality_passes", "transactions")}
    save(output / "summary.json", {"complete_blocks": len(complete), **totals, "menus": summary})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    for flag in ("install", "packets", "mod"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--extra-mod", type=Path, action="append", default=[])
    parser.add_argument("--recrafted", action="store_true")
    parser.add_argument("--extra-recipes", type=int, default=0)
    parser.add_argument("--blocks", type=int, default=6)
    parser.add_argument("--windows", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if min(args.blocks, args.windows) < 1 or min(args.extra_recipes, args.warmup) < 0:
        parser.error("use positive blocks and windows and nonnegative extra recipes and warmups")
    if len({jar.name for jar in [args.mod, *args.extra_mod]}) != len(args.extra_mod) + 1:
        parser.error("use distinct filenames for the experiment and extra mod JARs")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    plan = {
        "jar_sha256": sha256(args.mod), "extra_mods": {jar.name: sha256(jar) for jar in args.extra_mod},
        "packets_sha256": sha256(args.packets), "harness_sha256": sha256(HARNESS),
        "client_sha256": sha256(ROOT / "benchmarks/server_baseline.py"),
        "launcher_sha256": sha256(args.install / "fabric-server-launch.jar"),
        "lab": LabConfig.from_env().as_dict(), "recipes": args.extra_recipes, "recrafted": args.recrafted,
        "windows": args.windows, "warmup": args.warmup,
        "orders": [list(order) for order in balanced_orders(ARMS, args.blocks, 263)],
    }
    path = output / "plan.json"
    if path.exists() and json.loads(path.read_text()) != plan:
        parser.error("the existing plan differs; use a new output directory")
    save(path, plan)
    command = [sys.executable, str(HARNESS), "--accept-eula", "--install", str(args.install.resolve()),
               "--packets", str(args.packets.resolve()), "--mod", str(args.mod.resolve()),
               "--windows", str(args.windows), "--warmup", str(args.warmup), "--extra-recipes", str(args.extra_recipes)]
    for jar in args.extra_mod:
        command.extend(("--extra-mod", str(jar.resolve())))
    if args.recrafted:
        command.append("--recrafted")
    for block, order in enumerate(plan["orders"]):
        for arm in order:
            name = f"{block:02}-{arm}"
            checkpoint = output / (name + ".json")
            if checkpoint.is_file():
                continue
            attempt = 0
            while (log := output / f"{name}.{attempt}.log").exists():
                attempt += 1
            flags = ["--indexed"] if arm == "indexed" else []
            for retry in range(3):
                samples = log.with_suffix(".jsonl")
                with log.open("x") as stream:
                    result = subprocess.run([*command, *flags, "--timings", str(samples)], cwd=ROOT,
                                            stdout=stream, stderr=subprocess.STDOUT, timeout=480)
                if result.returncode == 0:
                    save(checkpoint, collect(samples, plan, arm))
                    print(f"Finished {name}", flush=True)
                    break
                lines = log.read_text().splitlines()
                failure = lines[-1] if lines else ""
                transient = failure == "ValueError: server-thread CPU counters did not advance" or (
                    failure.startswith("RuntimeError: Unexpected server diagnostics: [")
                    and "Failed to request Minecraft Services public key" in failure
                )
                if retry == 2 or not transient:
                    raise SystemExit(f"{name} exited {result.returncode}. See {log}")
                print(f"Retrying {name} after a transient measurement failure. See {log}", flush=True)
                attempt += 1
                log = output / f"{name}.{attempt}.log"
            summarize(output, plan)
    summarize(output, plan)


if __name__ == "__main__":
    main()
