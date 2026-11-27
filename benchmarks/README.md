# Benchmarks

Run the isolated lookup fixture with Java 25 from the project root.

```sh
scripts/run-lookup-benchmark.sh
```

Each invocation creates a separate evidence directory in `results/` with Gradle
output, measured samples and host details. A failed run retains its log and
returns a nonzero exit code. Use `--offline` after the build dependencies have
been cached.

All arms share an ordered Minecraft `RecipeMap`. The vanilla stream, ordered
loop, occupied-slot filter, ingredient index, and combined index run the
same exact recipe predicates. The suite includes the built-in server pack,
synthetic registries with varied slot counts, and a 10,000-recipe
same-slot registry. It tests early and late matches, misses, a shared
ingredient, and a mixed workload. Seed 263 drives the differential inputs
and the mixed workload. The build's parity check covers ambiguous recipes,
a mirrored shaped match, custom fallback priority, misses, and empty input.

Each workload uses 10 warmup and 10 measured batches of 512 lookups per
arm. Arms cycle through forward and reverse rotations. The script saves
wall time, thread CPU, thread allocations, and hit counts. Use
`-PlookupQuick` to run the built-in, 1,000-recipe mixed-slot, and
10,000-recipe same-slot datasets.

The synthetic registrations deliberately vary occupied slots. They do not
model recipe frequencies seen on a server. The fixture excludes world-dependent
recipes, transaction work, startup and reload cost, and retained heap. Use
`../mc-tooling/mc-lab` and `mc-testkit` to measure dedicated-server workloads.

## Dedicated-server smoke test

The smoke script uses sibling `mc-testkit` to start a loopback-only server,
load the built JAR, connect a headless vanilla-protocol client, and award a
vanilla recipe. It also checks furnace output, Crafter output routing and a
miss that becomes a hit after datapack reload and changes again after a tag-only reload. It needs the pinned Fabric
launcher, its `fabric-server-launcher.properties` file with an absolute
`serverJar` path, and the official 26.3 `packets.json` report. Generate that report from the Mojang server bundle with
`java -DbundlerMainClass=net.minecraft.data.Main -jar minecraft-server.jar --reports`.

```sh
scripts/smoke-server.py --install run/smoke-install \
    --packets run/smoke-install/reports/reports/packets.json \
    --mod build/libs/fast-recipes-0.1.0-alpha.1.jar --accept-eula
```

The script saves the server console in the named session directory.

## Dedicated-server crafting baseline

The release workloads are the built-in data pack and a deterministic heavy
data pack. The heavy fixture adds 10,000 shapeless recipes, with 2,000
distinct concrete-block ingredient multisets for each size from four to
eight. The benchmark selects an oak-log workload whose output is the same
with either pack. The harness uses mc-testkit to issue vanilla inventory
clicks and mc-lab to read server-thread CPU and host quality.

```sh
python3 benchmarks/server_baseline.py --install run/smoke-install \
    --packets run/smoke-install/reports/reports/packets.json \
    --mod build/libs/fast-recipes-0.1.0-alpha.1.jar --dataset vanilla --accept-eula
```

Use `--dataset heavy` for the second workload. A batch swaps 64 oak logs into
the player's 2×2 crafting grid, waits for the authoritative result, and
shift-clicks the output. The script verifies the result slot becomes empty
and measures 256 planks in inventory before clearing them. Each batch writes
a checkpoint with CPU, wall time, host quality, and completed craft count.
RCON setup and output checks are part of the measured server window. The
client omits speculative changed-slot hashes, so transport and
synchronization costs represent this fixture's headless client.

Use `--dataset same-slots` to add 10,000 four-slot recipes. This fixture
shift-clicks 64 crafting tables from four stacks of oak planks.
`--four-slot-craft` uses the same workload with the built-in or older
mixed-slot pack.

Use `--profile` in a separate run for a JFR call-path recording. Keep those
windows out of timing comparisons.

## Runtime comparison artifact

`./gradlew build` creates the installable alpha JAR. The original lookup modes are listed below.

| Mode | Lookup |
| --- | --- |
| `off` | Vanilla |
| `loop` | Ordered scan |
| `occupancy` | Scan the occupied-slot bucket |
| `ingredient` | Merge the ingredient postings and fallback in recipe order |
| `combined` | Use ingredient postings within the occupied-slot bucket |

The experiment selects a pivot only for exact vanilla shaped and shapeless
recipes with vanilla ingredients. When Fabric Recipe API is present, Fast
Recipes inspects the original ingredient definitions. Recipes with custom
ingredients and custom recipe classes retain their position in the ordered
fallback. Cache hits recheck those predicates against the live input.

```sh
scripts/run-ingredient-pilot.sh results/my-ingredient-pilot \
    --install run/smoke-install \
    --packets run/smoke-install/reports/reports/packets.json \
    --mod build/libs/fast-recipes-0.1.0-alpha.1.jar --accept-eula
```

The campaign saves each finished arm as a checkpoint. Use the same output
directory and unchanged inputs to resume. Start a new directory when
changing the artifact or fixture.

## Repeated-input cache and crafting-update experiments

Use `-PlookupCacheQuick` with the isolated lookup script to compare the
ordered loop, combined index, one-entry cache, and their combination.
The two synthetic datasets use 1,000 recipes for this focused run.

The experiment JAR accepts `--lookup cache` and `--lookup combined-cache`
in the server scripts. Use `--batch-crafting` in the server baseline script
to enable per-take grid-update batching independently of the lookup mode.
Use `--check-remainders` to verify a honey-block craft returns one glass
bottle per grid slot before collecting timing windows. The server logs
record the result-slot updates observed by the headless client.
Use `--table-craft` to run through the 3×3 crafting table and
`--check-remainders` to verify persisted crafting statistics after shutdown.

## Furnace misses and ingredient hash tree

The smoke script accepts `--furnace-misses` to enable the bounded negative
cache, exercise reuse, and check a miss-to-smelt datapack reload.
Use `--furnace-report results/furnaces.jsonl` to run the fueled-invalid-input
pilot and write server CPU windows to a new file. Add the pinned Lithium
JAR via `--extra-mod` to compare the same test with furnace sleeping.

Use `-PlookupTreeQuick` to time the hash tree and tree-plus-cache against
the existing combined index modes. The server scripts accept
`--lookup hash-tree` and `--lookup hash-tree-cache`.

## Adaptive lookup

`--lookup adaptive-cache` uses the repeated-input cache and switches
between the ordered ingredient postings and the hash tree as candidate
density grows. The hash tree is built in steps on dense lookups, per
occupied-slot bucket. The postings serve lookups until the tree is complete.

Use `scripts/run-lookup-benchmark.sh --offline -PlookupAdaptive` for the
cutoff sweep. The run saves timing rows and a separate setup allocation
file. Use `--dataset dense-pivot` on the server harness for a 10,000-recipe
crowded-pivot fixture. The client alternates two invalid four-slot grids
and checks that every measured result stays empty.

## Release-study verification

The [release study](../docs/benchmarking.md#release-study) records the
counterbalanced comparisons, first-use timing, and post-GC live heap.
`benchmarks/crafting_parity.py` drives ordinary clicks, inventory capacity,
a dynamic dyed output, and recipe-book placement against overlapping recipes.
Use `--recrafted` with one `--extra-mod` argument each for Recrafted's
26.3 aggregate JAR and Fabric API. Run once with `--lookup off` and once with
`--lookup adaptive-cache`. The Recrafted checks use shaped and shapeless
component-predicate overlaps, the custom component-crafting serializer, and
the packaged copper shears recipe. The client smoke also accepts `--extra-mod`.
`benchmarks/vanilla_client_smoke.py` verifies the official unmodified client
JAR and its libraries against the Mojang version manifest before starting a
local profile under Xvfb. Each harness prints its console log location.

Run `scripts/run-release-study.py` with `--mixed` to compare off, repeated off,
and adaptive lookup on eight ordinary result pickups followed by a short
four-result shift-click. The study runs that sequence on vanilla and the
same deterministic 10,000-recipe pack. It also records dense-tree build steps
and the first ingredient-postings construction before and after reload.

## Compound prototypes

The experiment JAR accepts the following JVM options.

| Option | Scope |
| --- | --- |
| `-Dfastrecipes.craftingCacheEntries=4` | Recent input capacity for any cached crafting mode. The default is one and the supported range is 1–16. |
| `-Dfastrecipes.smithingIndex=true` | Index supported smithing recipes by a required slot and keep custom recipes in order. |
| `-Dfastrecipes.stonecuttingIndex=true` | Index the server stonecutter catalog and preserve the complete ordered option list. |
| `-Dfastrecipes.furnaceHits=true` | Remember successful unhinted cooking lookups after the normal vanilla hint has failed. |
| `-Dfastrecipes.furnaceMisses=true` | Remember unsuccessful cooking lookups. Both furnace switches can run together. |

The station and furnace switches are off by default. Furnace caching runs
only when the entire cooking type has supported classes and vanilla
ingredients. Crafting batching can run with Fabric Recipe API installed.
It coalesces recomputations for supported crafting recipe sets. With
mixed recipes such as Recrafted, it coalesces only result-slot packets
during each shift-click result take and preserves recomputations.

Run `./gradlew benchmarkLookup -PlookupCacheCapacity benchmarkStations` for
the isolated comparisons. The dedicated-server harness accepts
`--cache-entries`. Use `benchmarks/station_parity.py` with and without
`--indexed` for menu and reload parity. Pass `--recrafted` and one
`--extra-mod` for each of Fabric API and the Recrafted aggregate. Use
`scripts/smoke-server.py --furnace-hits --furnace-misses` for the combined
furnace checks and add `--custom-cooking` when running with Recrafted.

For batching parity on Fabric API, run `benchmarks/crafting_parity.py` with
`--batch-crafting --fabric-reload` and `--extra-mod` pointing to Fabric API.
The test adds a Fabric ingredient to a stock shapeless recipe on reload
and verifies the switch to packet-only batching. A run with
`--recrafted --batch-crafting` checks that path from startup.

For dedicated station timing, run `benchmarks/station_parity.py` with
`--timings` pointing to a new JSONL file. Use `--extra-recipes 5000`
for the deterministic heavy fixture, and toggle `--indexed` between fresh
instances. The timed path keeps adaptive crafting off in both arms. Each
window sums three repetitions of each station's menu preview cases.
Input setup, result takes, and inventory verification run outside the
timed intervals. The accompanying metadata
records the fixture and JAR hashes. Run `scripts/run-station-study.py` with
the same server and JAR arguments for counterbalanced off, repeated off,
and indexed runs with resumable checkpoints.
