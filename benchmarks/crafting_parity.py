#!/usr/bin/env python3
"""Check vanilla crafting transactions against an opt-in lookup mode."""

import argparse
import json
from pathlib import Path
import shutil
import struct
import time
from uuid import uuid4

from server_baseline import (
    InventoryClient, PacketIds, ServerProcess, Writer, await_client_fence, fill_crafting_slot,
)


class CraftingClient(InventoryClient):
    book_updates = 0
    display_id = None
    partial_hotbar_count = None

    def _play_container_set_slot(self, reader):
        if reader.varint() == self.container_id:
            state_id = reader.varint()
            slot = struct.unpack(">h", reader.take(2))[0]
            count = reader.varint()
            if self.container_id == 0 and slot == 43:
                self.partial_hotbar_count = count
            self._update_inventory(state_id, count if slot == 0 else None)

    def close_inventory(self) -> None:
        self._send("play", "container_close", Writer().varint(self.container_id).bytes())

    def _play_recipe_book_add(self, reader):
        if reader.varint() == 1:
            display_id = reader.varint()

            def record(_):
                self.display_id = display_id
                self.book_updates += 1

            self._update(record)

    def place_recipe(self) -> None:
        if self.display_id is None:
            raise RuntimeError("No recipe display ID received")
        packet = Writer().varint(self.container_id).varint(self.display_id).raw(b"\0")
        self._send("play", "place_recipe", packet.bytes())


def install_overlap(world: Path, recrafted: bool) -> Path:
    pack = world / "datapacks" / "fast-recipes-parity"
    data = pack / "data" / "fast_recipes_probe" / "recipe"
    data.mkdir(parents=True)
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Fast Recipes recipe priority probe", "min_format": [121, 0], "max_format": [121, 0]},
    }))
    # These recipes overlap only each other. The book explicitly selects the second.
    for name, output, count in [("a_first", "emerald", 1), ("z_second", "diamond", 2)]:
        (data / (name + ".json")).write_text(json.dumps({
            "type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:bedrock"],
            "result": {"id": "minecraft:" + output, "count": count},
        }))
    for material in ("barrier", "bedrock"):
        (data / f"staged_{material}.json").write_text(json.dumps({
            "type": "minecraft:crafting_shapeless", "ingredients": [
                f"minecraft:{material}", "minecraft:stick",
            ],
            "result": {"id": "minecraft:gold_ingot", "count": 3},
        }))
    if recrafted:
        component = {"fabric:type": "recrafted_core:component_predicate",
                     "not_predicates": {"minecraft:custom_data": {"fast_recipes_probe": {"marked": True}}}}
        for kind, item in (("shaped", "structure_block"), ("shapeless", "barrier")):
            holder = "minecraft:" + item
            for name, ingredient, result in (("a", {**component, "ingredient": holder}, "emerald"),
                                              ("z", holder, "diamond")):
                shape = {"pattern": ["X"], "key": {"X": ingredient}} if kind == "shaped" else {
                    "ingredients": [ingredient],
                }
                (data / f"{name}_{kind}_component.json").write_text(json.dumps({
                    "type": f"minecraft:crafting_{kind}", **shape,
                    "result": {"id": "minecraft:" + result, "count": 1 if name == "a" else 2},
                }))
        (data / "recrafted_serializer.json").write_text(json.dumps({
            "type": "recrafted_core:component_crafting", "category": "misc",
            "ingredients": [{"id": "ink", "ingredient": "minecraft:ink_sac"},
                            {"id": "paper", "ingredient": "minecraft:paper"}],
            "result": {"id": "minecraft:book"},
        }))
    return data


def ordinary(server, player) -> None:
    fill_crafting_slot(server, player, 1, "oak_log", 1, 4)
    updates = player.result_updates
    player.click(0, 0, 0)
    player.await_result(updates, 0)
    click_fence(player, "ordinary-pickup")
    player.click(9, 0, 0)
    click_fence(player, "ordinary-deposit")
    if not server.rcon(f"clear {player.name} minecraft:oak_planks").startswith("Removed 4 "):
        raise RuntimeError("Ordinary crafting did not produce four planks")


def click_fence(player: CraftingClient, name: str) -> None:
    marker = "fast-recipes-confirm-" + name
    player.command(f"tellraw {player.name} " + json.dumps({"text": marker}))
    player.wait_for_message(marker, 10)


def wait_inventory(server, command: str, predicate) -> str:
    deadline = time.monotonic() + 5
    while True:
        reply = server.rcon(command)
        if predicate(reply):
            return reply
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Inventory did not reach the expected state: {reply}")
        time.sleep(0.05)


def capacity(server, player) -> None:
    fill_crafting_slot(server, player, 1, "oak_log", 2, 4)
    for slot in [*(f"hotbar.{index}" for index in range(9)), *(f"inventory.{index}" for index in range(27))]:
        reply = server.rcon(f"item replace entity {player.name} {slot} with minecraft:iron_ingot 64")
        if not reply.startswith("Replaced 1 slot(s)"):
            raise RuntimeError(reply)
    player.wait_for(lambda _: player.partial_hotbar_count == 64, 10, "receive the full inventory")
    player.click(0, 0, 1)
    click_fence(player, "full-result")
    if player.result_count != 4 or not server.rcon(f"clear {player.name} minecraft:oak_planks 0").startswith("No items"):
        raise RuntimeError("Full inventory unexpectedly accepted a craft")
    server.rcon(f"item replace entity {player.name} hotbar.7 with minecraft:oak_planks 60")
    player.wait_for(lambda _: player.partial_hotbar_count == 60, 10, "receive partial-capacity stack")
    player.click(0, 0, 1)
    click_fence(player, "partial-result")
    wait_inventory(server, f"clear {player.name} minecraft:oak_planks 0", lambda reply: reply.startswith("Found 64 "))
    if player.result_count != 4:
        raise RuntimeError(f"Partial capacity changed the remaining preview: {player.result_count}")
    server.rcon(f"clear {player.name} minecraft:iron_ingot")
    if not server.rcon(f"clear {player.name} minecraft:oak_planks").startswith("Removed 64 "):
        raise RuntimeError("Partial-capacity inventory changed before clearing its output")
    # Closing the inventory returns the remaining grid contents to the player.
    player.close_inventory()
    click_fence(player, "partial-remainder")
    wait_inventory(server, f"data get entity {player.name} Inventory",
                   lambda contents: 'id: "minecraft:oak_log"' in contents and "count: 1" in contents)
    server.rcon(f"clear {player.name}")


def inventory_remainders(server, player) -> None:
    for slot in (1, 2, 3, 4):
        fill_crafting_slot(server, player, slot, "honey_bottle", 1, 1 if slot == 4 else None)
    take_result(server, player, "minecraft:honey_block", 1)
    player.close_inventory()
    click_fence(player, "return-glass-bottles")
    wait_inventory(server, f"clear {player.name} minecraft:glass_bottle 0",
                   lambda reply: reply.startswith("Found 4 "))
    if not server.rcon(f"clear {player.name} minecraft:glass_bottle").startswith("Removed 4 "):
        raise RuntimeError("Expected four glass-bottle remainders")


def dynamic(server, player) -> None:
    fill_crafting_slot(server, player, 1, "leather_horse_armor", 1)
    fill_crafting_slot(server, player, 2, "red_dye", 1, 1)
    updates = player.result_updates
    player.click(0, 0, 1)
    player.await_result(updates, 0)
    click_fence(player, "dynamic-take")
    wait_inventory(server, f"data get entity {player.name} Inventory",
                   lambda contents: 'id: "minecraft:leather_horse_armor"' in contents
                   and "minecraft:dyed_color" in contents)
    server.rcon(f"clear {player.name}")


def book(server, player) -> None:
    player.open_crafting_table(server)
    fill_crafting_slot(server, player, 1, "bedrock", 1, 1)
    updates = player.result_updates
    player.click(1, 0, 2)
    player.await_result(updates, 0)
    await_client_fence(server, player, "before-book")
    updates = player.book_updates
    reply = server.rcon(f"recipe give {player.name} fast_recipes_probe:z_second")
    if not reply.startswith("Unlocked 1 "):
        raise RuntimeError(reply)
    player.wait_for(lambda _: player.book_updates > updates, 10, "receive the recipe book entry")
    updates = player.result_updates
    player.place_recipe()
    player.await_result(updates, 2)


def take_result(server, player, item: str, count: int) -> None:
    updates = player.result_updates
    player.click(0, 0, 1)
    player.await_result(updates, 0)
    click_fence(player, f"take-{item}-{updates}")
    if not server.rcon(f"clear {player.name} {item}").startswith(f"Removed {count} "):
        raise RuntimeError(f"Expected {count} {item} from crafting")


def shift_click_transition(server, player, material: str) -> None:
    fill_crafting_slot(server, player, 1, material, 2, 1)
    fill_crafting_slot(server, player, 2, "stick", 1, 3)
    updates = player.result_updates
    player.click(0, 0, 1)
    player.await_result(updates, 1)
    click_fence(player, "staged-first-result")
    if not server.rcon(f"clear {player.name} minecraft:gold_ingot").startswith("Removed 3 "):
        raise RuntimeError("Shift-click did not take exactly the initial recipe result")
    take_result(server, player, "minecraft:emerald", 1)


def fabric_ingredient_reload(server, player, data: Path) -> None:
    # The recipe still uses the stock ShapelessRecipe class after reload.
    take_result(server, player, "minecraft:diamond", 2)
    (data / "fabric_reload.json").write_text(json.dumps({
        "type": "minecraft:crafting_shapeless",
        "ingredients": [{"fabric:type": "fabric:difference",
                         "base": ["minecraft:barrier", "minecraft:structure_block"],
                         "subtracted": "minecraft:structure_block"}],
        "result": {"id": "minecraft:gold_ingot", "count": 3},
    }))
    start = len(server.lines)
    server.rcon("reload")
    server.wait_for_log(r"Loaded \d+ advancements", 45, start=start)
    fill_crafting_slot(server, player, 1, "barrier", 1, 3)
    take_result(server, player, "minecraft:gold_ingot", 3)
    server.wait_for_log("Fast Recipes crafting packet batching experiment initialized", 5,
                        start=start)
    print("Passed switch to packet-only batching after Fabric ingredient reload", flush=True)


def recrafted_components(server, player) -> None:
    for item in ("structure_block", "barrier"):
        marked = item + "[minecraft:custom_data={fast_recipes_probe:{marked:1b}}]"
        # Keep the same grid shape and item holder while switching predicate outcomes.
        for count, input_item in ((2, marked), (1, item), (2, marked), (1, item)):
            server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:air")
            await_client_fence(server, player, f"component-{player.result_updates}")
            fill_crafting_slot(server, player, 1, input_item, 1, count)
        take_result(server, player, "minecraft:emerald", 1)
        server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:air")
        await_client_fence(server, player, f"component-{player.result_updates}")
        fill_crafting_slot(server, player, 1, marked, 1, 2)
        take_result(server, player, "minecraft:diamond", 2)
    fill_crafting_slot(server, player, 1, "ink_sac", 1)
    fill_crafting_slot(server, player, 2, "paper", 1, 1)
    take_result(server, player, "minecraft:book", 1)
    fill_crafting_slot(server, player, 2, "copper_ingot", 1)
    server.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:air")
    await_client_fence(server, player, "copper-shears")
    fill_crafting_slot(server, player, 3, "copper_ingot", 1, 1)
    take_result(server, player, "recrafted_armory:copper_shears", 1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("install", "packets", "mod"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--lookup", choices=("off", "adaptive-cache"), required=True)
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--recrafted", action="store_true", help="exercise Recrafted predicates and serializer")
    parser.add_argument("--batch-crafting", action="store_true")
    parser.add_argument("--fabric-reload", action="store_true",
                        help="load a custom Fabric ingredient into a stock recipe after the batching check")
    parser.add_argument("--cache-entries", type=int, choices=range(1, 17), default=1)
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if args.fabric_reload and (not args.batch_crafting or args.recrafted):
        parser.error("--fabric-reload requires --batch-crafting on a Fabric API stack without Recrafted")
    session = f"crafting-parity-{uuid4().hex[:10]}"
    install = args.install.resolve()
    mods = install / "test-mods" / session
    mods.mkdir(parents=True)
    for jar in (args.mod, *args.extra_mod):
        shutil.copy2(jar, mods)
    packets = PacketIds.load(args.packets)
    server = ServerProcess(install, session, "java", packets, loader="fabric", mods=mods,
                           jvm_args=["-Xmx2G", f"-Dfastrecipes.lookup={args.lookup}",
                                     f"-Dfastrecipes.craftingCacheEntries={args.cache_entries}",
                                     f"-Dfastrecipes.batchCrafting={str(args.batch_crafting).lower()}"])
    player = CraftingClient("RecipeParity", "127.0.0.1", server.port, packets, protocol_version=777)
    print(f"Console: {server.directory / 'console.log'}", flush=True)
    try:
        data = install_overlap(server.world, args.recrafted)
        server.start()
        server.rcon("whitelist off")
        player.connect()
        player.wait_for(lambda _: player.inventory_state is not None, 10, "receive inventory")
        server.rcon(f"op {player.name}")
        checks = (ordinary, capacity, dynamic, inventory_remainders)
        if args.recrafted:
            checks += (recrafted_components,)
        for check in checks:
            check(server, player)
            print(f"Passed {check.__name__}", flush=True)
        shift_click_transition(server, player, "barrier" if args.recrafted else "bedrock")
        print("Passed shift-click recipe transition", flush=True)
        book(server, player)
        print("Passed book", flush=True)
        player.assert_connected()
        if args.batch_crafting:
            kind = "packet batching" if args.recrafted else "update"
            server.wait_for_log(f"Fast Recipes crafting {kind} experiment initialized", 5)
        if args.fabric_reload:
            fabric_ingredient_reload(server, player, data)
        if args.lookup != "off":
            server.wait_for_log(f"Fast Recipes recipe experiment initialized: {args.lookup}", 5)
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
