#!/usr/bin/env python3
"""Measure inventory crafting on a dedicated Fabric 26.3 baseline."""

import argparse
import hashlib
from itertools import chain, combinations_with_replacement, islice
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
from uuid import uuid4

HERE = Path(__file__).resolve().parent
TOOLING = HERE.parents[1] / "mc-tooling"
sys.path[:0] = [str(TOOLING / "mc-testkit"), str(TOOLING / "mc-lab")]

from mc_lab import __version__ as lab_version
from mc_lab.cpu import ServerThread
from mc_lab.host import snapshot_host
from mc_lab.profile import LabConfig, apply_gamerules
from mc_testkit.bot import Bot
from mc_testkit.protocol import PacketIds, Writer
from mc_testkit.server import ServerProcess

INPUT_COUNT = 64
HEAVY_COUNT = 10_000
MISS_SWAPS = 32
COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
BLOCKS = tuple(f"minecraft:{color}_concrete" for color in COLORS)


class InventoryClient(Bot):
    """Observe inventory state IDs and send vanilla clicks with an empty prediction map."""

    container_id = 0
    inventory_state = None
    inventory_updates = 0
    result_updates = 0
    result_count = 0
    nonempty_results = 0

    def _update_inventory(self, state_id: int, result: int | None = None) -> None:
        def set_state(_):
            self.inventory_state = state_id
            self.inventory_updates += 1
            if result is not None:
                self.result_updates += 1
                self.result_count = result
                self.nonempty_results += int(result != 0)

        self._update(set_state)

    def _play_container_set_slot(self, reader):
        if reader.varint() == self.container_id:
            state_id = reader.varint()
            slot = struct.unpack(">h", reader.take(2))[0]
            self._update_inventory(state_id, reader.varint() if slot == 0 else None)

    def _play_container_set_content(self, reader):
        if reader.varint() == self.container_id:
            self._update_inventory(reader.varint())

    def _play_open_screen(self, reader):
        container_id = reader.varint()

        def open_screen(_):
            self.container_id = container_id
            self.inventory_state = None

        self._update(open_screen)

    def open_crafting_table(self, server: ServerProcess) -> None:
        server.rcon("setblock 0 199 0 minecraft:stone")
        server.rcon("setblock 0 200 1 minecraft:crafting_table")
        teleports = self.state.teleports
        server.rcon(f"tp {self.name} 0.5 200 0.5 0 0")
        self.wait_for(lambda state: state.teleports > teleports, 10, "teleport next to the table")
        # Main hand, block (0, 200, 1), north face and hit offsets, then sequence.
        packet = (Writer().varint(0).long((1 << 12) | 200).varint(2)
                  .float(0.5).float(0.5).float(0.0).raw(b"\0\0").varint(1).bytes())
        self._send("play", "use_item_on", packet)
        self.wait_for(lambda _: self.container_id != 0 and self.inventory_state is not None,
                      10, "open the crafting table")

    def four_slot_grid(self) -> tuple[int, ...]:
        return (1, 2, 4, 5) if self.container_id else (1, 2, 3, 4)

    def click(self, slot: int, button: int, action: int) -> None:
        if self.inventory_state is None:
            raise RuntimeError("Player inventory has no server-provided state ID")
        packet = (Writer().varint(self.container_id).varint(self.inventory_state)
                  .raw(struct.pack(">hb", slot, button)).varint(action)
                  .varint(0).raw(b"\0").bytes())
        self._send("play", "container_click", packet)

    def await_update(self, after: int) -> None:
        self.wait_for(lambda _: self.inventory_updates > after, 10, "receive an inventory update")

    def await_result(self, after: int, count: int | None = None) -> None:
        self.wait_for(lambda _: self.result_updates > after and (count is None or self.result_count == count),
                      10, "receive the expected crafting result")


def install_heavy_pack(world: Path, dataset: str) -> str:
    pack = world / "datapacks" / "fast-recipes-heavy"
    recipes = pack / "data" / "fast_recipes_load" / "recipe"
    recipes.mkdir(parents=True)
    digest = hashlib.sha256()
    meta = json.dumps({"pack": {"description": "Fast Recipes deterministic load fixture",
                                "min_format": [121, 0], "max_format": [121, 0]}})
    (pack / "pack.mcmeta").write_text(meta, encoding="utf-8")
    digest.update(meta.encode())
    if dataset == "dense-pivot":
        fillers = BLOCKS + tuple(f"minecraft:{color}_{material}"
                                 for material in ("terracotta", "wool") for color in COLORS)
        combinations = (("minecraft:oak_planks", *rest) for rest in combinations_with_replacement(fillers, 3))
    elif dataset == "same-slots":
        extra = tuple(f"minecraft:{color}_terracotta" for color in
                      ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray"))
        combinations = combinations_with_replacement(BLOCKS + extra, 4)
    else:
        by_count = (combinations_with_replacement(BLOCKS, count) for count in range(4, 9))
        combinations = chain.from_iterable(zip(*by_count))
    fixtures = islice(combinations, HEAVY_COUNT)
    for index, ingredients in enumerate(fixtures):
        recipe = {"type": "minecraft:crafting_shapeless", "ingredients": ingredients,
                  "result": {"id": "minecraft:smooth_stone", "count": 1}}
        name = f"load_{index:05}.json"
        payload = json.dumps(recipe, separators=(",", ":"))
        (recipes / name).write_text(payload, encoding="utf-8")
        digest.update(name.encode())
        digest.update(payload.encode())
    return digest.hexdigest()


def fill_crafting_slot(server: ServerProcess, player: InventoryClient, slot: int,
                       item: str, count: int, expected_result: int | None = None) -> None:
    updates = player.inventory_updates
    reply = server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:{item} {count}")
    if not reply.startswith("Replaced 1 slot(s)"):
        raise RuntimeError(f"Failed to insert inputs: {reply}")
    player.await_update(updates)
    updates = player.result_updates
    player.click(slot, 0, 2)  # SWAP with hotbar slot 0
    player.await_result(updates, expected_result)


def craft_stack(server: ServerProcess, player: InventoryClient, four_slots: bool,
                count: int = INPUT_COUNT) -> int:
    """Fill the 2×2 grid through vanilla inventory clicks and shift-click the output."""
    slots, ingredient, result, per_craft = (
        (player.four_slot_grid(), "oak_planks", "crafting_table", 1)
        if four_slots else ((1,), "oak_log", "oak_planks", 4)
    )
    for slot in slots:
        fill_crafting_slot(server, player, slot, ingredient, count, per_craft if slot == slots[-1] else None)
    updates = player.result_updates
    player.click(0, 0, 1)  # QUICK_MOVE the crafting result
    player.await_result(updates, 0)
    reply = server.rcon(f"clear {player.name} minecraft:{result} 0")
    if not reply.startswith(f"Found {count * per_craft} "):
        raise RuntimeError(f"Unexpected crafting output: {reply}")
    reply = server.rcon(f"clear {player.name} minecraft:{result}")
    if not reply.startswith(f"Removed {count * per_craft} "):
        raise RuntimeError(f"Unexpected cleared output: {reply}")
    return count


def craft_mixed(server: ServerProcess, player: InventoryClient, batch: int) -> int:
    """Take eight single results, then shift-click four results from a different recipe."""
    fill_crafting_slot(server, player, 1, "oak_log", 8, 4)
    for remaining in range(7, -1, -1):
        updates = player.result_updates
        player.click(0, 0, 0)
        player.await_result(updates, 4 if remaining else 0)
    player.click(9, 0, 0)
    marker = f"fast-recipes-mixed-{batch}-deposit"
    player.command(f"tellraw {player.name} " + json.dumps({"text": marker}))
    player.wait_for_message(marker, 10)
    reply = server.rcon(f"clear {player.name} minecraft:oak_planks")
    if not reply.startswith("Removed 32 "):
        raise RuntimeError(f"Unexpected ordinary crafting output: {reply}")
    return 8 + craft_stack(server, player, four_slots=True, count=4)


def check_remainders(server: ServerProcess, player: InventoryClient) -> None:
    slots = player.four_slot_grid()
    for slot in slots:
        fill_crafting_slot(server, player, slot, "honey_bottle", 1, 1 if slot == slots[-1] else None)
    updates = player.result_updates
    player.click(0, 0, 1)
    player.await_result(updates, 0)
    reply = server.rcon(f"clear {player.name} minecraft:honey_block")
    if not reply.startswith("Removed 1 "):
        raise RuntimeError(f"Expected one honey block: {reply}")

    for slot in slots:
        updates = player.result_updates
        player.click(slot, 0, 2)
        player.await_result(updates, 0)
        contents = server.rcon(f"data get entity {player.name} Inventory[{{Slot:0b}}]")
        if 'id: "minecraft:glass_bottle"' not in contents or "count: 1" not in contents:
            raise RuntimeError(f"Expected one glass bottle in grid slot {slot}: {contents}")
        server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:air")
    print("Verified one honey block and four glass bottle remainders", flush=True)


def await_client_fence(server: ServerProcess, player: InventoryClient, batch: int | str) -> None:
    # The command response and preceding slot updates share the player's connection.
    marker = f"fast-recipes-batch-{batch}-complete"
    server.rcon(f"tellraw {player.name} " + json.dumps({"text": marker}))
    player.wait_for_message(marker, 10)


def prepare_dense_misses(server: ServerProcess, player: InventoryClient) -> None:
    for slot in (2, 3, 4):
        fill_crafting_slot(server, player, slot, "bedrock", 1, 0)
    fill_crafting_slot(server, player, 1, "oak_planks", 1, 0)
    updates = player.inventory_updates
    reply = server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:barrier")
    if not reply.startswith("Replaced 1 slot(s)"):
        raise RuntimeError(f"Failed to stage the alternating item: {reply}")
    player.await_update(updates)
    await_client_fence(server, player, "setup")


def verify_dense_misses(player: InventoryClient) -> int:
    updates, nonempty = player.result_updates, player.nonempty_results
    for _ in range(MISS_SWAPS):
        player.click(1, 0, 2)
    player.await_result(updates + MISS_SWAPS - 1, 0)
    if player.nonempty_results != nonempty:
        raise RuntimeError("The invalid crafting grid received a result")
    return 0


def measure(args) -> None:
    session = f"recipe-baseline-{args.dataset}-{uuid4().hex[:10]}"
    install = args.install.resolve()
    mods = install / "test-mods" / session
    mods.mkdir(parents=True)
    for jar in [args.mod, *args.extra_mod]:
        if (mods / jar.name).exists():
            raise ValueError(f"Duplicate mod filename: {jar.name}")
        shutil.copy2(jar, mods)
    packets = PacketIds.load(args.packets)
    lab = LabConfig.from_env()
    options = lab.server_options()
    server = ServerProcess(install, session, "java", packets, loader="fabric", mods=mods,
                           command_prefix=options["command_prefix"],
                           jvm_args=["-Xms2G", "-Xmx2G", *options["jvm_args"], f"-Dfastrecipes.lookup={args.lookup}",
                                     f"-Dfastrecipes.craftingCacheEntries={args.cache_entries}",
                                     f"-Dfastrecipes.batchCrafting={str(args.batch_crafting).lower()}",
                                     f"-Dfastrecipes.measureSetup={str(args.heap_diagnostic).lower()}"])
    report = HERE.parent / "results" / session
    report.mkdir(parents=True)
    print(f"Results: {report}; console: {server.directory / 'console.log'}", flush=True)
    player = InventoryClient("RecipeBaseline", "127.0.0.1", server.port, packets, protocol_version=777)
    try:
        dense_misses = args.dataset == "dense-pivot"
        four_slots = args.four_slot_craft or args.dataset == "same-slots" or args.table_craft
        pack_hash = install_heavy_pack(server.world, args.dataset) if args.dataset != "vanilla" else None
        craft = "invalid_grid" if dense_misses else "crafting_table" if four_slots else "oak_planks"
        if args.mixed_crafting:
            craft = "mixed_pickup_and_shift_click"
        metadata = {"dataset": args.dataset, "craft": craft,
                    "additional_recipes": HEAVY_COUNT if pack_hash else 0,
                    "datapack_sha256": pack_hash, "jar_sha256": hashlib.sha256(args.mod.read_bytes()).hexdigest(),
                    "trials": args.trials, "warmup": args.warmup, "mc_lab": lab_version, "host": snapshot_host(),
                    "extra_mods": {jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in args.extra_mod},
                    "jvm_args": server.jvmOptions(), "seed": 4242, "profiled": args.profile, "lookup": args.lookup,
                    "cache_entries": args.cache_entries,
                    "batch_crafting": args.batch_crafting, "table_menu": args.table_craft,
                    "lab": lab.as_dict(), "heap_diagnostic": args.heap_diagnostic}
        (report / "run.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        server.start()
        server.wait_for_log(r"Loading Minecraft 26\.3 with Fabric Loader 0\.20\.1", 5)
        server.rcon("whitelist off")
        apply_gamerules(server.rcon, lab.gamerules)
        player.connect()
        player.wait_for(lambda _: player.inventory_state is not None, 10, "receive the player inventory")
        if args.mixed_crafting:
            server.rcon(f"op {player.name}")
        if pack_hash:
            enabled = server.rcon("datapack list enabled")
            reply = server.rcon(f"recipe give {player.name} fast_recipes_load:load_09999")
            # Recipe-book optimizers can expose the whole recipe book on login.
            if "file/fast-recipes-heavy" not in enabled or reply not in (
                f"Unlocked 1 recipe(s) for {player.name}", "No new recipes were learned",
            ):
                raise RuntimeError(f"The heavy dataset did not load: {enabled}; {reply}")
        if args.table_craft:
            player.open_crafting_table(server)
        if args.check_remainders:
            check_remainders(server, player)
        if dense_misses:
            prepare_dense_misses(server, player)
        thread = ServerThread.of(server.pid)
        if args.profile:
            subprocess.run(["jcmd", str(server.pid), "JFR.start", "name=recipe-baseline",
                            "settings=profile", "dumponexit=true",
                            f"filename={report / 'server.jfr'}"], check=True)
        with (report / "windows.jsonl").open("w", encoding="utf-8", buffering=1) as output:
            for index in range(-args.warmup, args.trials):
                updates = player.result_updates
                with thread.window(max_wait_fraction=lab.max_wait_fraction()) as window:
                    if args.mixed_crafting:
                        crafted = craft_mixed(server, player, index)
                    elif dense_misses:
                        crafted = verify_dense_misses(player)
                    else:
                        crafted = craft_stack(server, player, four_slots)
                await_client_fence(server, player, index)
                output.write(json.dumps({"batch": index, "crafts": crafted,
                                         "result_updates": player.result_updates - updates, **window.result}) + "\n")
        if args.batch_crafting:
            server.wait_for_log("Fast Recipes crafting (?:update|packet batching) experiment initialized", 5)
        if args.lookup != "off":
            server.wait_for_log(f"Fast Recipes recipe experiment initialized: {args.lookup}", 5)
        if args.profile:
            subprocess.run(["jcmd", str(server.pid), "JFR.stop", "name=recipe-baseline"], check=True)
        if args.heap_diagnostic:
            for generation in ("initial", "reloaded"):
                if generation == "reloaded":
                    start = len(server.lines)
                    server.rcon("reload")
                    server.wait_for_log(r"Loaded \d+ advancements", 45, start=start)
                    if dense_misses:
                        verify_dense_misses(player)
                    else:
                        craft_stack(server, player, four_slots)
                with (report / f"heap-{generation}.txt").open("w", encoding="utf-8") as heap:
                    subprocess.run(["jcmd", str(server.pid), "GC.class_histogram"], stdout=heap,
                                   stderr=subprocess.STDOUT, check=True, timeout=60)
            (report / "setup.log").write_text("\n".join(line for line in server.lines
                if "Fast Recipes setup" in line) + "\n", encoding="utf-8")
        player.assert_connected()
        diagnostics = server.suspicious_lines()
        if diagnostics:
            raise RuntimeError("Unexpected server diagnostics:\n" + "\n".join(diagnostics))
        if dense_misses:
            print(f"Verified {args.trials * MISS_SWAPS} empty results from measured grid swaps", flush=True)
        else:
            print(f"Verified {args.trials * crafted} measured crafts", flush=True)
    finally:
        player.disconnect()
        try:
            server.stop()
        finally:
            if server.running:
                server.kill()


    if args.check_remainders:
        stats_path = server.world / "players" / "stats" / f"{player.uuid}.json"
        stats = json.loads(stats_path.read_text(encoding="utf-8"))["stats"]["minecraft:crafted"]
        item, per_craft = ("crafting_table", 1) if four_slots else ("oak_planks", 4)
        if stats.get("minecraft:honey_block") != 1 or stats.get(f"minecraft:{item}") != (
            (args.trials + args.warmup) * INPUT_COUNT * per_craft
        ):
            raise RuntimeError(f"Unexpected saved crafting statistics: {stats}")
        print("Verified saved crafting statistics", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("install", "packets", "mod"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--dataset", choices=("vanilla", "heavy", "same-slots", "dense-pivot"), required=True)
    parser.add_argument("--four-slot-craft", action="store_true",
                        help="craft tables instead of planks on the built-in or mixed-slot pack")
    parser.add_argument("--table-craft", action="store_true", help="use a 3×3 crafting table menu")
    parser.add_argument("--mixed-crafting", action="store_true", help="alternate ordinary clicks and short shift-click runs")
    parser.add_argument("--trials", type=int, default=12)
    parser.add_argument("--warmup", type=int, default=4)
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--lookup", choices=("off", "loop", "occupancy", "ingredient", "combined", "cache", "combined-cache", "hash-tree", "hash-tree-cache", "adaptive-cache"), default="off")
    parser.add_argument("--batch-crafting", action="store_true")
    parser.add_argument("--cache-entries", type=int, choices=range(1, 17), default=1)
    parser.add_argument("--check-remainders", action="store_true")
    parser.add_argument("--heap-diagnostic", action="store_true")
    parser.add_argument("--profile", action="store_true", help="keep a separate JFR diagnostic recording")
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("--accept-eula is required to start the isolated server")
    if args.trials < 1 or args.warmup < 0:
        parser.error("trials must be positive and warmup nonnegative")
    if args.dataset == "dense-pivot" and (args.check_remainders or args.table_craft or args.batch_crafting):
        parser.error("dense-pivot measures empty inventory-grid updates without result takes")
    if args.mixed_crafting and (args.dataset == "dense-pivot" or args.table_craft or args.check_remainders
                               or args.heap_diagnostic or args.four_slot_craft or args.batch_crafting):
        parser.error("mixed crafting uses the player inventory with its own verified output and no batching")
    measure(args)


if __name__ == "__main__":
    main()
