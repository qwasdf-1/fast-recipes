#!/usr/bin/env python3
"""Check a packaged Fast Recipes build with a loopback Fabric dedicated server."""

import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def sprint(server, ticks: int) -> None:
    start = len(server.lines)
    server.rcon(f"tick sprint {ticks}t")
    server.wait_for_log("Sprint completed", 45, start=start)


def place(server, prefix: str, *blocks: str) -> None:
    for block in blocks:
        reply = server.rcon(prefix + "setblock " + block)
        if not reply.startswith("Changed the block"):
            raise RuntimeError(f"Failed to place recipe fixture: {reply}")


def check_recipe_blocks(server, player: str) -> None:
    prefix = f"execute at {player} positioned ~ 200 ~ run "
    place(server, prefix,
          '~ ~ ~ minecraft:furnace{Items:[{Slot:0b,id:"minecraft:cobblestone",count:1},'
          '{Slot:1b,id:"minecraft:coal",count:1}]}',
          '~2 ~ ~ minecraft:crafter[orientation=east_up]{Items:[{Slot:0b,id:"minecraft:oak_log",count:1}]}',
          "~3 ~ ~ minecraft:chest",
          "~2 ~1 ~ minecraft:redstone_block")
    sprint(server, 240)
    furnace = server.rcon(prefix + "data get block ~ ~ ~ Items[{Slot:2b}]")
    chest = server.rcon(prefix + "data get block ~3 ~ ~ Items[{Slot:0b}]")
    if 'id: "minecraft:stone"' not in furnace or "count: 1" not in furnace:
        raise RuntimeError(f"Expected one smelted stone in the furnace output: {furnace}")
    if 'id: "minecraft:oak_planks"' not in chest or "count: 4" not in chest:
        raise RuntimeError(f"Expected four crafted planks in the chest: {chest}")
    print("Verified furnace smelting and redstone Crafter output", flush=True)


def reload_pack(server) -> None:
    start = len(server.lines)
    server.rcon("reload")
    server.wait_for_log(r"Loaded \d+ advancements", 45, start=start)


def pulse_reload_crafter(server, prefix: str) -> str:
    place(server, prefix, "~5 ~1 ~ minecraft:air", "~5 ~1 ~ minecraft:redstone_block")
    sprint(server, 12)
    return server.rcon(prefix + "data get block ~6 ~ ~ Items[{Slot:0b}]")


def check_furnace_miss(server, player: str, windows: Path | None) -> None:
    prefix = f"execute at {player} positioned ~ 200 ~ run "
    fixture = ('minecraft:furnace{Items:[{Slot:0b,id:"minecraft:bedrock",count:1},'
               '{Slot:1b,id:"minecraft:coal",count:1}]}')
    place(server, prefix, f"~8 ~ ~ {fixture}")
    if windows is not None:
        from mc_lab.cpu import ServerThread

        place(server, prefix, *(f"~{x} ~ ~{z} {fixture}" for x in range(8, 20) for z in range(2, 10)))
        thread = ServerThread.of(server.pid)
        with windows.open("x", encoding="utf-8", buffering=1) as samples:
            for batch in range(-3, 8):
                with thread.window() as window:
                    sprint(server, 160)
                samples.write(json.dumps({"batch": batch, "furnaces": 97, "ticks": 160, **window.result}) + "\n")
    else:
        sprint(server, 12)
    items = server.rcon(prefix + "data get block ~8 ~ ~ Items")
    if "minecraft:bedrock" not in items or "minecraft:coal" not in items or "Slot: 2b" in items:
        raise RuntimeError(f"Invalid furnace input changed inventory: {items}")


def install_furnace_hit_recipe(world: Path) -> Path:
    pack = world / "datapacks" / "fast-recipes-furnace-hits"
    recipe = pack / "data" / "fast_recipes_hits" / "recipe" / "barrier.json"
    recipe.parent.mkdir(parents=True)
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Furnace cache fixture", "min_format": [121, 0], "max_format": [121, 0]},
    }), encoding="utf-8")
    recipe.write_text(json.dumps({
        "type": "minecraft:smelting", "ingredient": "minecraft:barrier",
        "cookingtime": 8, "experience": 3, "result": {"id": "minecraft:emerald"},
    }), encoding="utf-8")
    return recipe


def check_furnace_xp(server, packets) -> None:
    sys.path.insert(0, str(ROOT / "benchmarks"))
    from station_parity import StationClient, take

    name = "RecipeExperience"
    player = StationClient(name, "127.0.0.1", server.port, packets, protocol_version=777)
    pos = "0 200 1"
    try:
        player.connect()
        player.wait_for(lambda _: player.inventory_state is not None, 10, "receive inventory")
        server.rcon(f"op {name}")
        server.rcon(f"experience set {name} 0 levels")
        server.rcon(f"experience set {name} 0 points")
        player.open_station(server, "furnace", 2)
        server.rcon(f"item replace block {pos} container.1 with minecraft:coal 4")
        # Invalidate vanilla's most recent successful hint before repeating the first recipe.
        for source, result in (("barrier", "emerald"), ("sand", "glass"), ("barrier", "emerald")):
            server.rcon(f"item replace block {pos} container.2 with minecraft:air")
            reply = server.rcon(f"item replace block {pos} container.0 with minecraft:{source}")
            if not reply.startswith("Replaced "):
                raise RuntimeError(reply)
            sprint(server, 230)
            contents = server.rcon(f"data get block {pos} Items")
            if f'id: "minecraft:{result}"' not in contents:
                raise RuntimeError(f"Expected smelted {result}: {contents}")
        take(server, player, "emerald", 1)
        player.close_station()
        server.rcon(f"tp {name} 0.5 200 1.5")
        for _ in range(3):
            sprint(server, 40)
            reply = server.rcon(f"experience query {name} points")
            if reply in (f"{name} has 6 experience points", f"{name} has 0 experience points"):
                # Seven points advances the player to level one with zero leftover points.
                if reply == f"{name} has 6 experience points" or (
                    server.rcon(f"experience query {name} levels") == f"{name} has 1 experience levels"
                ):
                    print("Verified the player collected XP from the furnace result take", flush=True)
                    return
            time.sleep(0.1)
        raise RuntimeError(f"Furnace result did not award the expected player XP: {reply}")
    finally:
        player.disconnect()


def check_furnace_hits(server, player: str, recipe: Path, custom_ingredient: bool) -> None:
    prefix = f"execute at {player} positioned ~ 200 ~ run "
    place(server, prefix, '~10 ~ ~ minecraft:furnace{Items:[{Slot:1b,id:"minecraft:coal",count:4}]}')

    def smelt(source: str, expected: str | None) -> None:
        for slot, item in ((2, "air"), (0, source)):
            reply = server.rcon(prefix + f"item replace block ~10 ~ ~ container.{slot} with minecraft:{item}")
            if not reply.startswith("Replaced "):
                raise RuntimeError(f"Failed to change furnace input: {reply}")
        sprint(server, 230)
        if expected is None:
            items = server.rcon(prefix + "data get block ~10 ~ ~ Items")
            if "Slot: 0b" not in items or "Slot: 2b" in items:
                raise RuntimeError(f"Expected unconsumed input and empty output for {source}: {items}")
        else:
            output = server.rcon(prefix + "data get block ~10 ~ ~ Items[{Slot:2b}]")
            if f'id: "minecraft:{expected}"' not in output or "count: 1" not in output:
                raise RuntimeError(f"Unexpected output for {source}: {output}")

    for source, output in (("barrier", "emerald"), ("sand", "glass"), ("barrier", "emerald")):
        smelt(source, output)
    used = server.rcon(prefix + "data get block ~10 ~ ~ RecipesUsed")
    if "fast_recipes_hits:barrier" not in used:
        raise RuntimeError(f"Cached smelt did not record the recipe used for XP: {used}")
    content = json.loads(recipe.read_text())
    content["result"]["id"] = "minecraft:diamond"
    if custom_ingredient:
        content["ingredient"] = {"fabric:type": "recrafted_core:component_predicate",
                                 "ingredient": "minecraft:barrier",
                                 "not_predicates": {"minecraft:custom_data": {"fast_recipes_probe": True}}}
    recipe.write_text(json.dumps(content), encoding="utf-8")
    reload_pack(server)
    smelt("barrier", "diamond")
    if custom_ingredient:
        smelt("barrier[minecraft:custom_data={fast_recipes_probe:1b}]", None)
        smelt("barrier", "diamond")
    print("Verified alternating successful smelts, recipe-use accounting, and new output after reload", flush=True)


def check_recrafted_blasting(server, player: str) -> None:
    prefix = f"execute at {player} positioned ~ 200 ~ run "
    place(server, prefix, '~12 ~ ~ minecraft:blast_furnace{Items:[{Slot:1b,id:"minecraft:coal",count:4}]}')
    for source, expected in (("raw_iron", "iron_ingot"), ("raw_copper", "copper_ingot"), ("raw_iron", "iron_ingot"),
                             ("bedrock", None)):
        for slot, item in ((2, "air"), (0, source)):
            reply = server.rcon(prefix + f"item replace block ~12 ~ ~ container.{slot} with minecraft:{item}")
            if not reply.startswith("Replaced "):
                raise RuntimeError(f"Failed to change blast furnace input: {reply}")
        sprint(server, 120)
        if expected is None:
            items = server.rcon(prefix + "data get block ~12 ~ ~ Items")
            if 'id: "minecraft:bedrock"' not in items or "Slot: 2b" in items:
                raise RuntimeError(f"Expected unconsumed bedrock and empty output: {items}")
            continue
        output = server.rcon(prefix + "data get block ~12 ~ ~ Items[{Slot:2b}]")
        if f'id: "minecraft:{expected}"' not in output or "count: 1" not in output:
            raise RuntimeError(f"Unexpected Recrafted blast furnace output for {source}: {output}")
    print("Verified both furnace caches for Recrafted's eligible blasting type", flush=True)


def check_datapack_reload(server, player: str, *, furnace: bool = False) -> None:
    prefix = f"execute at {player} positioned ~ 200 ~ run "
    place(server, prefix,
          '~5 ~ ~ minecraft:crafter[orientation=east_up]{Items:[{Slot:0b,id:"minecraft:bedrock",count:1}]}',
          "~6 ~ ~ minecraft:chest",
          "~5 ~1 ~ minecraft:redstone_block")
    sprint(server, 12)
    before = server.rcon(prefix + "data get block ~6 ~ ~ Items")
    if not before.endswith("block data: []"):
        raise RuntimeError(f"Expected an empty chest before the recipe reload: {before}")

    pack = server.world / "datapacks" / "fast-recipes-smoke"
    recipe = pack / "data" / "fast_recipes_smoke" / "recipe" / "bedrock.json"
    recipe.parent.mkdir(parents=True)
    recipe.write_text(json.dumps({
        "type": "minecraft:crafting_shapeless",
        "ingredients": ["#fast_recipes_smoke:reload_inputs"],
        "result": {"id": "minecraft:glowstone", "count": 1},
    }), encoding="utf-8")
    if furnace:
        cooking = recipe.with_name("bedrock_smelting.json")
        cooking.write_text(json.dumps({
            "type": "minecraft:smelting", "ingredient": "minecraft:bedrock",
            "cookingtime": 200, "experience": 0,
            "result": {"id": "minecraft:glowstone", "count": 1},
        }), encoding="utf-8")
    tag = pack / "data" / "fast_recipes_smoke" / "tags" / "item" / "reload_inputs.json"
    tag.parent.mkdir(parents=True)
    tag.write_text(json.dumps({"values": ["minecraft:barrier", "minecraft:bedrock"]}), encoding="utf-8")
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Fast Recipes reload fixture", "min_format": [121, 0], "max_format": [121, 0]},
    }), encoding="utf-8")
    reload_pack(server)
    after = pulse_reload_crafter(server, prefix)
    if 'id: "minecraft:glowstone"' not in after or "count: 1" not in after:
        raise RuntimeError(f"Expected one glowstone after the recipe reload: {after}")
    print("Verified a cached Crafter miss becomes a hit after datapack reload", flush=True)

    if furnace:
        # Reload may already have produced output while the test pulses the Crafter.
        for slot, item in ((0, "air"), (2, "air"), (0, "bedrock")):
            reply = server.rcon(prefix + f"item replace block ~8 ~ ~ container.{slot} with minecraft:{item}")
            if not reply.startswith("Replaced "):
                raise RuntimeError(f"Failed to wake furnace after reload: {reply}")
        sprint(server, 230)
        output = server.rcon(prefix + "data get block ~8 ~ ~ Items[{Slot:2b}]")
        if 'id: "minecraft:glowstone"' not in output or "count: 1" not in output:
            raise RuntimeError(f"Expected the new smelting recipe after reload: {output}")
        print("Verified a fueled furnace miss becomes a smelt after reload", flush=True)

    tag.write_text(json.dumps({"values": ["minecraft:barrier"]}), encoding="utf-8")
    reload_pack(server)
    for item, expected in (("bedrock", 1), ("barrier", 2)):
        reply = server.rcon(prefix + f"item replace block ~5 ~ ~ container.0 with minecraft:{item}")
        if not reply.startswith("Replaced "):
            raise RuntimeError(f"Failed to update Crafter input for the tag reload: {reply}")
        contents = pulse_reload_crafter(server, prefix)
        if 'id: "minecraft:glowstone"' not in contents or f"count: {expected}" not in contents:
            raise RuntimeError(f"Unexpected Crafter output after removing bedrock from the tag: {contents}")
    print("Verified a tag-only reload removes bedrock while retaining barrier", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", required=True, type=Path, help="isolated Fabric 26.3 server install")
    parser.add_argument("--packets", required=True, type=Path, help="26.3 server reports/packets.json")
    parser.add_argument("--mod", required=True, type=Path, help="Fast Recipes jar from build/libs")
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    modes = ("off", "loop", "occupancy", "ingredient", "combined", "cache", "combined-cache",
             "hash-tree", "hash-tree-cache", "adaptive-cache")
    parser.add_argument("--lookup", choices=modes, help="override the server configuration for this run")
    parser.add_argument("--config", type=Path, help="copy a TOML configuration into the isolated server")
    parser.add_argument("--expect-lookup", choices=modes, help="assert the effective lookup mode")
    parser.add_argument("--furnace-misses", action="store_true")
    parser.add_argument("--furnace-hits", action="store_true")
    parser.add_argument("--furnace-xp", action="store_true", help="verify player XP after taking a stock furnace result")
    parser.add_argument("--custom-cooking", action="store_true", help="probe Recrafted ingredient fallback after reload")
    parser.add_argument("--furnace-report", type=Path)
    parser.add_argument("--accept-eula", action="store_true", help="accept https://aka.ms/MinecraftEULA for this test")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("--accept-eula is required to start the isolated server")
    if args.custom_cooking and not (args.furnace_hits and args.furnace_misses):
        parser.error("--custom-cooking requires --furnace-hits and --furnace-misses")
    for file in (args.install / "fabric-server-launch.jar", args.packets, args.mod):
        if not file.is_file():
            parser.error(f"missing file {file}")

    os.environ["MC_TEST_PROTOCOL_VERSION"] = "777"
    sys.path.insert(0, str(ROOT.parent / "mc-tooling" / "mc-testkit"))
    sys.path.insert(0, str(ROOT.parent / "mc-tooling" / "mc-lab"))
    from mc_testkit.protocol import PacketIds
    from mc_testkit.server import ServerProcess

    session = f"fast-recipes-{uuid4().hex[:12]}"
    install = args.install.resolve()
    mods = install / "test-mods" / session
    mods.mkdir(parents=True)
    for jar in [args.mod, *args.extra_mod]:
        if (mods / jar.name).exists():
            raise ValueError(f"Duplicate mod filename: {jar.name}")
        shutil.copy2(jar, mods)
    packets = PacketIds.load(args.packets)
    jvm_args = []
    if args.lookup is not None:
        jvm_args.append(f"-Dfastrecipes.lookup={args.lookup}")
    if args.furnace_misses:
        jvm_args.append("-Dfastrecipes.furnaceMisses=true")
    if args.furnace_hits:
        jvm_args.append("-Dfastrecipes.furnaceHits=true")
    server = ServerProcess(install, session, "java", packets, loader="fabric", mods=mods, jvm_args=jvm_args)
    print(f"Server console: {server.directory / 'console.log'}", flush=True)
    try:
        if args.config:
            config = server.directory / "config" / "fast-recipes.toml"
            config.parent.mkdir(exist_ok=True)
            shutil.copy2(args.config, config)
        hit_recipe = install_furnace_hit_recipe(server.world) if args.furnace_hits or args.furnace_xp else None
        server.start()
        server.wait_for_log(r"Loading Minecraft 26\.3 with Fabric Loader 0\.20\.1", 5)
        server.wait_for_log(r"fast_recipes 0\.", 5)
        server.rcon("whitelist off")
        bot = server.bot("RecipeSmoke")
        bot.assert_connected()
        if bot.name not in server.rcon("list"):
            raise RuntimeError("Headless vanilla protocol client did not appear in the player list")
        # The command should see a vanilla recipe after the headless client joins.
        server.rcon(f"recipe take {bot.name} minecraft:crafting_table")
        result = server.rcon(f"recipe give {bot.name} minecraft:crafting_table")
        if result != f"Unlocked 1 recipe(s) for {bot.name}":
            raise RuntimeError(f"Vanilla recipe award failed: {result}")
        check_recipe_blocks(server, bot.name)
        furnace = args.furnace_misses or args.furnace_report is not None
        if furnace:
            check_furnace_miss(server, bot.name, args.furnace_report)
        if args.furnace_misses and not args.custom_cooking:
            server.wait_for_log("Fast Recipes furnace miss experiment reused a negative result", 5)
        check_datapack_reload(server, bot.name, furnace=furnace)
        if args.furnace_xp:
            check_furnace_xp(server, packets)
        if args.furnace_hits and hit_recipe:
            check_furnace_hits(server, bot.name, hit_recipe, args.custom_cooking)
            suffix = ""
            if args.custom_cooking:
                check_recrafted_blasting(server, bot.name)
                suffix = " for .*blasting"
                server.wait_for_log("Fast Recipes furnace miss experiment reused a negative result" + suffix, 5)
            server.wait_for_log("Fast Recipes furnace hit experiment reused a successful result" + suffix, 5)
        expected = args.expect_lookup or args.lookup
        if expected == "off":
            if any("Fast Recipes recipe experiment initialized:" in line for line in server.lines):
                raise RuntimeError("Crafting lookup was active while off was expected")
        elif expected:
            server.wait_for_log(f"Fast Recipes recipe experiment initialized: {expected}", 5)
        diagnostics = server.suspicious_lines()
        if diagnostics:
            raise RuntimeError("Unexpected server diagnostics:\n" + "\n".join(diagnostics))
        print(f"Joined with vanilla protocol 777. Recipe command response: {result}", flush=True)
    finally:
        try:
            server.stop()
        finally:
            if server.running:
                server.kill()


if __name__ == "__main__":
    main()
