#!/usr/bin/env python3
"""Compare stock and indexed smithing and stonecutting through vanilla menu packets."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import shutil
import struct
from uuid import uuid4

from crafting_parity import click_fence, ordinary, wait_inventory
from server_baseline import InventoryClient, PacketIds, ServerProcess, Writer
from mc_lab.cpu import ServerThread
from mc_lab.profile import LabConfig

TIMED_REPETITIONS = 3


class StationClient(InventoryClient):
    result_slot = 0

    def _play_container_set_slot(self, reader):
        if reader.varint() == self.container_id:
            state_id = reader.varint()
            slot = struct.unpack(">h", reader.take(2))[0]
            count = reader.varint()
            if self.container_id:
                self.slot_counts[slot] = count
            self._update_inventory(state_id, count if slot == self.result_slot else None)

    def open_station(self, server, block: str, result_slot: int) -> None:
        server.rcon("setblock 0 199 0 minecraft:stone")
        server.rcon(f"setblock 0 200 1 minecraft:{block}")
        teleports = self.state.teleports
        server.rcon(f"tp {self.name} 0.5 200 0.5 0 0")
        self.wait_for(lambda state: state.teleports > teleports, 10, "teleport next to the station")
        self.result_slot = result_slot
        self.slot_counts = {}
        packet = (Writer().varint(0).long((1 << 12) | 200).varint(2)
                  .float(0.5).float(0.5).float(0.0).raw(b"\0\0").varint(1).bytes())
        self._send("play", "use_item_on", packet)
        self.wait_for(lambda _: self.container_id != 0 and self.inventory_state is not None,
                      10, "open the station")

    def close_station(self) -> None:
        self._send("play", "container_close", Writer().varint(self.container_id).bytes())
        click_fence(self, f"close-{self.container_id}")
        self.container_id = 0

    def choose(self, option: int, count: int) -> None:
        updates = self.result_updates
        self._send("play", "container_button_click", Writer().varint(self.container_id).varint(option).bytes())
        self.await_result(updates, count)


def install_fixtures(world: Path, recrafted: bool, extra_recipes: int = 0) -> Path:
    pack = world / "datapacks" / "fast-recipes-stations"
    data = pack / "data" / "fast_recipes_stations" / "recipe"
    data.mkdir(parents=True)
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Fast Recipes station parity", "min_format": [121, 0], "max_format": [121, 0]},
    }))
    predicate = {"fabric:type": "recrafted_core:component_predicate", "ingredient": "minecraft:barrier",
                 "not_predicates": {"minecraft:custom_data": {"fast_recipes_probe": True}}}
    for name, ingredient, output, count in (
        ("a_cut", predicate if recrafted else "minecraft:barrier", "emerald", 1),
        ("z_cut", "minecraft:barrier", "diamond", 2),
    ):
        (data / (name + ".json")).write_text(json.dumps({
            "type": "minecraft:stonecutting", "ingredient": ingredient,
            "result": {"id": "minecraft:" + output, "count": count},
        }))
    smithing = {"template": "minecraft:echo_shard", "base": "minecraft:diamond_sword",
                "addition": "minecraft:prismarine_crystals"}
    (data / "z_smith.json").write_text(json.dumps({
        "type": "minecraft:smithing_transform", **smithing, "result": {"id": "minecraft:iron_sword"},
    }))
    if recrafted:
        (data / "a_smith.json").write_text(json.dumps({
            "type": "recrafted_core:component_smithing", **smithing,
            "base": {**predicate, "ingredient": smithing["base"]},
            "result": {"id": "minecraft:netherite_sword"},
            "transform": {"transmute_from": "base", "copy_components": [
                {"from": "addition", "components": ["minecraft:custom_name"]},
            ]},
        }))
    if extra_recipes:
        stones = ("tuff", "andesite", "granite", "diorite", "cobblestone", "deepslate")
        templates = ("iron_ingot", "gold_ingot", "diamond", "netherite_ingot", "amethyst_shard", "quartz")
        for index in range(extra_recipes):
            (data / f"load_cut_{index:05}.json").write_text(json.dumps({
                "type": "minecraft:stonecutting", "ingredient": f"minecraft:{stones[index % len(stones)]}",
                "result": {"id": "minecraft:stone_button"},
            }))
            (data / f"load_smith_{index:05}.json").write_text(json.dumps({
                "type": "minecraft:smithing_transform",
                **smithing, "template": f"minecraft:{templates[index % len(templates)]}",
                "result": {"id": "minecraft:iron_sword"},
            }))
    return data


def stage(server, player, item: str) -> None:
    reply = server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:{item}")
    if not reply.startswith("Replaced 1 slot(s)"):
        raise RuntimeError(reply)
    hotbar_slot = player.result_slot + 28
    player.wait_for(lambda _: player.slot_counts.get(hotbar_slot) == 1, 10,
                    f"receive the staged hotbar item for slot {hotbar_slot}")


def fill(server, player, slot: int, item: str, label: str) -> None:
    stage(server, player, item)
    player.click(slot, 0, 2)
    click_fence(player, label)
    player.wait_for(lambda _: player.slot_counts.get(slot) == 1, 10, f"fill station slot {slot} for {label}")


def take(server, player, item: str, count: int, component: str | None = None) -> None:
    marker = f"result-{player.container_id}-{player.result_updates}"
    updates = player.inventory_updates
    player.click(player.result_slot, 0, 0)
    click_fence(player, marker + "-take")
    player.await_update(updates)
    player.click(player.result_slot + 1, 0, 0)
    click_fence(player, marker + "-deposit")
    wait_inventory(server, f"data get entity {player.name} Inventory",
                   lambda contents: f'id: "minecraft:{item}"' in contents
                   and (component is None or component in contents))
    reply = server.rcon(f"clear {player.name} minecraft:{item}")
    if not reply.startswith(f"Removed {count} "):
        raise RuntimeError(f"Expected {count} {item}: {reply}")


def smithing(server, player, recrafted: bool, phase: str, *, measure=None) -> None:
    cases = (False, True) if recrafted else (False,)
    for marked in cases:
        player.open_station(server, "smithing_table", 3)
        tag = f"{phase}-smith-{marked}"
        fill(server, player, 0, "echo_shard", tag + "-template")
        fill(server, player, 1, "diamond_sword" + (
            "[minecraft:custom_data={fast_recipes_probe:1b}]" if marked else ""), tag + "-base")
        addition = 'prismarine_crystals[minecraft:custom_name="Station parity"]'
        updates = player.result_updates
        if measure is None:
            fill(server, player, 2, addition, tag + "-addition")
            player.await_result(updates, 1)
        else:
            stage(server, player, addition)

            def preview():
                player.click(2, 0, 2)
                player.await_result(updates, 1)

            measure(preview)
        if recrafted and not marked:
            take(server, player, "netherite_sword", 1, "Station parity")
        else:
            take(server, player, "iron_sword", 1)
        player.close_station()


def stonecutting(server, player, recrafted: bool, phase: str, after_reload: bool = False, *, measure=None) -> None:
    player.open_station(server, "stonecutter", 1)
    cases = [("barrier", 0, 1, "gold_ingot" if after_reload else "emerald"),
             ("barrier", 1, 2, "diamond")]
    if recrafted:
        cases.append(("barrier[minecraft:custom_data={fast_recipes_probe:1b}]", 0, 2, "diamond"))
    for index, (item, option, count, result) in enumerate(cases):
        if measure is None:
            if index == 0:
                stage(server, player, item)
                player.click(29, 0, 1)
                click_fence(player, phase + "-stone-quick-move")
            else:
                fill(server, player, 0, item, f"{phase}-stone-{index}")
            player.choose(option, count)
        else:
            stage(server, player, item)

            def preview():
                player.click(29 if index == 0 else 0, 0, 1 if index == 0 else 2)
                player.wait_for(lambda _: player.slot_counts.get(0) == 1, 10, "insert stonecutter input")
                player.choose(option, count)

            measure(preview)
        take(server, player, result, count)
    player.close_station()


def measure_stations(server, player, args, output, lab) -> None:
    thread = ServerThread.of(server.pid)
    with output.open("x", buffering=1) as stream:
        for index in range(-args.warmup, args.windows):
            for menu in (smithing, stonecutting):
                samples = []

                def measure(action):
                    with thread.window(max_wait_fraction=lab.max_wait_fraction()) as window:
                        action()
                    samples.append(window.result)

                for repetition in range(TIMED_REPETITIONS):
                    menu(server, player, args.recrafted, f"measured-{index}-{repetition}", measure=measure)
                count = (2 if args.recrafted else 1) if menu == smithing else (3 if args.recrafted else 2)
                stream.write(json.dumps({"menu": menu.__name__, "batch": index,
                                         "verified_results": TIMED_REPETITIONS * count,
                                         "cpu_ns": sum(sample["cpu_ns"] for sample in samples),
                                         "wall_ns": sum(sample["wall_ns"] for sample in samples),
                                         "quality_pass": all(sample["quality_pass"] for sample in samples),
                                         "samples": samples}) + "\n")
        if args.indexed:
            for station in ("smithing", "stonecutting"):
                server.wait_for_log(f"Fast Recipes {station} index experiment initialized", 5)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("install", "packets", "mod"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--recrafted", action="store_true")
    parser.add_argument("--indexed", action="store_true")
    parser.add_argument("--timings", type=Path, help="write menu CPU windows to this new JSONL file")
    parser.add_argument("--extra-recipes", type=int, default=0, help="add this many recipes per station")
    parser.add_argument("--windows", type=int, default=6)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if args.extra_recipes < 0 or args.windows < 1 or args.warmup < 0:
        parser.error("recipe and warmup counts must be nonnegative and windows must be positive")
    session = f"station-parity-{uuid4().hex[:10]}"
    install = args.install.resolve()
    mods = install / "test-mods" / session
    mods.mkdir(parents=True)
    for jar in (args.mod, *args.extra_mod):
        shutil.copy2(jar, mods)
    packets = PacketIds.load(args.packets)
    enabled = str(args.indexed).lower()
    lab = LabConfig.from_env()
    options = lab.server_options() if args.timings else {"command_prefix": [], "jvm_args": []}
    crafting = "adaptive-cache" if args.indexed and not args.timings else "off"
    server = ServerProcess(install, session, "java", packets, loader="fabric", mods=mods,
                           command_prefix=options["command_prefix"],
                           jvm_args=["-Xmx2G", *options["jvm_args"], f"-Dfastrecipes.smithingIndex={enabled}",
                                     f"-Dfastrecipes.stonecuttingIndex={enabled}",
                                     f"-Dfastrecipes.lookup={crafting}",
                                     "-Dfastrecipes.craftingCacheEntries=4"])
    player = StationClient("StationParity", "127.0.0.1", server.port, packets, protocol_version=777)
    print(f"Console: {server.directory / 'console.log'}", flush=True)
    try:
        data = install_fixtures(server.world, args.recrafted, args.extra_recipes)
        if args.timings:
            digest = sha256()
            for recipe in sorted(data.glob("*.json")):
                digest.update(recipe.name.encode() + recipe.read_bytes())
            with args.timings.with_suffix(".meta.json").open("x") as metadata:
                json.dump({"indexed": args.indexed, "recrafted": args.recrafted, "windows": args.windows,
                           "transactions_per_window": {
                               "smithing": TIMED_REPETITIONS * (2 if args.recrafted else 1),
                               "stonecutting": TIMED_REPETITIONS * (3 if args.recrafted else 2),
                           },
                           "warmup": args.warmup, "extra_recipes_per_station": args.extra_recipes,
                           "fixture_sha256": digest.hexdigest(), "mod_sha256": sha256(args.mod.read_bytes()).hexdigest(),
                           "extra_mods": {jar.name: sha256(jar.read_bytes()).hexdigest() for jar in args.extra_mod},
                           "lab": lab.as_dict(), "console": str(server.directory / "console.log")}, metadata, indent=2)
        server.start()
        server.rcon("whitelist off")
        player.connect()
        player.wait_for(lambda _: player.inventory_state is not None, 10, "receive inventory")
        server.rcon(f"op {player.name}")
        ordinary(server, player)
        if args.timings:
            if args.extra_recipes:
                for station in ("cut", "smith"):
                    name = f"fast_recipes_stations:load_{station}_{args.extra_recipes - 1:05}"
                    reply = server.rcon(f"recipe give {player.name} {name}")
                    if not reply.startswith("Unlocked 1 "):
                        raise RuntimeError(f"Heavy station fixture failed to load {name}: {reply}")
            measure_stations(server, player, args, args.timings, lab)
        for phase in (() if args.timings else ("initial", "reloaded")):
            start = len(server.lines)
            if phase == "reloaded":
                recipe = data / "a_cut.json"
                content = json.loads(recipe.read_text())
                content["result"]["id"] = "minecraft:gold_ingot"
                recipe.write_text(json.dumps(content))
                server.rcon("reload")
                server.wait_for_log(r"Loaded \d+ advancements", 45, start=start)
            smithing(server, player, args.recrafted, phase)
            stonecutting(server, player, args.recrafted, phase, after_reload=phase == "reloaded")
            if args.indexed:
                for station in ("smithing", "stonecutting"):
                    server.wait_for_log(f"Fast Recipes {station} index experiment initialized", 5, start=start)
            print(f"Passed smithing and stonecutting {phase} menu checks", flush=True)
        player.assert_connected()
        if server.suspicious_lines():
            raise RuntimeError(f"Unexpected server diagnostics: {server.suspicious_lines()}")
    finally:
        player.disconnect()
        try:
            server.stop()
        finally:
            if server.running:
                server.kill()


if __name__ == "__main__":
    main()
