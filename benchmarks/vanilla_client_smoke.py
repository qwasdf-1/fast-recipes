#!/usr/bin/env python3
"""Connect a hash-verified, unmodified 26.3 client to the isolated test server."""

import argparse
from hashlib import file_digest
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
from uuid import uuid4

from server_baseline import PacketIds, ServerProcess
from mc_testkit.protocol import offline_uuid


def checked_jar(path: Path, expected: str) -> Path:
    with path.open("rb") as stream:
        if file_digest(stream, "sha1").hexdigest() != expected:
            raise ValueError(f"SHA-1 mismatch for {path}")
    return path


def linux_allowed(library: dict) -> bool:
    rules = library.get("rules")
    if rules is None:
        return True
    allowed = False
    for rule in rules:
        platform = rule.get("os", {})
        if platform.get("name", "linux") == "linux" and platform.get("arch", "x86_64") in ("amd64", "x86_64"):
            allowed = rule["action"] == "allow"
    return allowed


def classpath(args, manifest) -> str:
    jars = [checked_jar(args.client, manifest["downloads"]["client"]["sha1"])]
    for library in manifest["libraries"]:
        if not linux_allowed(library):
            continue
        artifact = library["downloads"]["artifact"]
        name = Path(artifact["path"]).name
        cached = args.modules.joinpath(*library["name"].split(":")[:3], artifact["sha1"], name)
        path = cached if cached.is_file() else args.libraries / name
        jars.append(checked_jar(path, artifact["sha1"]))
    return os.pathsep.join(str(jar.resolve()) for jar in jars)


def craft_in_client(server, name: str, client: subprocess.Popen, client_dir: Path) -> None:
    scale = 2

    def xdo(*args: str) -> str:
        return subprocess.check_output(["xdotool", *args], text=True, timeout=20).strip()

    window = xdo("search", "--sync", "--onlyvisible", "--pid", str(client.pid)).splitlines()[-1]
    dimensions = dict(line.split("=", 1) for line in xdo("getwindowgeometry", "--shell", window).splitlines())
    left = (int(dimensions["WIDTH"]) - 176 * scale) // 2
    top = (int(dimensions["HEIGHT"]) - 166 * scale) // 2

    def hover(x: int, y: int) -> None:
        xdo("mousemove", "--sync", "--window", window, str(left + x * scale), str(top + y * scale))
        # GLFW updates the hovered slot while rendering the screen.
        time.sleep(0.5)

    def capture(name: str) -> None:
        if shutil.which("import"):
            subprocess.run(["import", "-window", window, str(client_dir / name)], timeout=10)

    xdo("windowfocus", "--sync", window)
    # Keep the player safe and stationary while the client renders its initial chunks.
    server.rcon("setblock 0 199 0 minecraft:stone")
    server.rcon(f"tp {name} 0.5 200 0.5")
    if not server.rcon(f"item replace entity {name} hotbar.0 with minecraft:oak_log 2").startswith("Replaced "):
        raise RuntimeError("Could not stage the vanilla client crafting input")
    time.sleep(5)
    xdo("key", "e")
    time.sleep(1)
    capture("inventory.png")
    hover(106, 26)
    xdo("key", "1")
    time.sleep(0.5)
    hover(162, 36)
    xdo("keydown", "Shift_L", "click", "1", "keyup", "Shift_L")
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if server.rcon(f"clear {name} minecraft:oak_planks 0").startswith("Found 8 "):
            print("Unmodified client shift-crafted eight oak planks via the inventory screen", flush=True)
            return
        time.sleep(0.1)
    capture("crafting-failed.png")
    raise RuntimeError(f"Unmodified client did not complete crafting: {server.rcon(f'data get entity {name} Inventory')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("install", "packets", "mod", "manifest", "client", "modules", "libraries", "assets"):
        parser.add_argument(f"--{flag}", type=Path, required=True)
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--craft", action="store_true", help="exercise the inventory GUI under an existing Xvfb display")
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if args.craft and (not os.environ.get("DISPLAY") or not shutil.which("xdotool")):
        parser.error("--craft requires DISPLAY and xdotool; launch this script with xvfb-run")
    manifest = json.loads(args.manifest.read_text())
    if manifest["id"] != "26.3":
        parser.error("Expected the 26.3 version manifest")
    paths = classpath(args, manifest)
    name = "RecipeVanilla"
    session = f"vanilla-client-{uuid4().hex[:10]}"
    install = args.install.resolve()
    mods = install / "test-mods" / session
    mods.mkdir(parents=True)
    for jar in (args.mod, *args.extra_mod):
        shutil.copy2(jar, mods)
    packets = PacketIds.load(args.packets)
    server = ServerProcess(install, session, "java", packets, loader="fabric", mods=mods,
                           jvm_args=["-Xms2G", "-Xmx2G", "-Dfastrecipes.lookup=adaptive-cache",
                                     "-Dfastrecipes.batchCrafting=true"])
    client_dir = server.directory / "vanilla-client"
    client_dir.mkdir()
    (client_dir / "options.txt").write_text(
        "version:5023\nonboardAccessibility:false\nrenderDistance:4\nguiScale:2\nfullscreen:false\n")
    print(f"Server and client logs: {server.directory}", flush=True)
    # This verifier uses an offline local profile on an isolated loopback-only server.
    try:
        server.start()
        server.rcon("whitelist off")
        command = ["java", "-Xmx2G", "--enable-native-access=ALL-UNNAMED",
                   "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                   "-cp", paths, manifest["mainClass"], "--username", name, "--uuid", offline_uuid(name).hex,
                   "--version", "26.3", "--gameDir", str(client_dir), "--assetsDir", str(args.assets.resolve()),
                   "--assetIndex", "26.3-" + manifest["assetIndex"]["id"], "--accessToken", "0",
                   "--offlineDeveloperMode", "--versionType", "release", "--width", "854", "--height", "480",
                   "--quickPlayPath", "quickplay.json",
                   "--quickPlayMultiplayer", f"127.0.0.1:{server.port}"]
        if not args.craft:
            command[:0] = ["xvfb-run", "-a"]
        with (client_dir / "console.log").open("w") as console:
            with subprocess.Popen(command, cwd=client_dir, stdout=console,
                                  stderr=subprocess.STDOUT, start_new_session=True,
                                  env={**os.environ, "SDL_VIDEO_DRIVER": "x11", "LIBGL_ALWAYS_SOFTWARE": "1"}) as client:
                try:
                    server.wait_for_log(rf"{name} joined the game", 90)
                    if name not in server.rcon("list") or client.poll() is not None:
                        raise RuntimeError("The vanilla client did not remain connected")
                    print("Unmodified Minecraft 26.3 client joined the server", flush=True)
                    if args.craft:
                        craft_in_client(server, name, client, client_dir)
                finally:
                    if client.poll() is None:
                        os.killpg(client.pid, signal.SIGTERM)
    finally:
        try:
            server.stop()
        finally:
            if server.running:
                server.kill()


if __name__ == "__main__":
    main()
