# Verification

| Check | Command or procedure | Evidence |
| --- | --- | --- |
| Build | `./gradlew build` | Runs isolated lookup parity and packages the server alpha |
| Isolated lookup | `scripts/run-lookup-benchmark.sh` | Selected recipe identity, synthetic and built-in lookup timings |
| Server smoke | Follow [the smoke instructions](../benchmarks/README.md#dedicated-server-smoke-test) | Fabric dedicated startup, packaged mod load and vanilla-protocol handshake |
| Documentation | `python3 ../mc-tooling/mc-hygiene/mc_hygiene/doc_boundary.py` | Local project material stays outside maintained docs |
| Runtime feature acceptance | Follow [the decision gate](research/R-006-decision.md) | Gameplay parity, reload behavior, performance and compatibility |

Gradle's offline mode requires the full dependency cache and local daemon
sockets. Dedicated-server runs use an isolated test install and retain the
session console. The release gate also needs an unmodified client and
representative runtime workload with the same Minecraft build and recipe data.

## Release-study transaction coverage

On Minecraft 26.3 with Fabric Loader 0.20.1, the disabled control and
adaptive lookup with batching both passed protocol-driven checks for an
ordinary click, a full inventory, exactly one craft into partial
capacity, unconsumed input, and the dyed-color component on crafted
leather horse armor. The 3×3 recipe-book test selected the second of two
overlapping recipes and verified its distinct preview count. The
hash-verified official client also joined the adaptive experiment server
using its offline developer profile under Xvfb. That client test covers
startup and login. The transaction fixtures use the headless client.

Evidence is under `results/release-correctness.veEngWFa/`.

## Recrafted parity

With Recrafted's 26.3 aggregate and Fabric API 0.165.0, the `off` control and
`adaptive-cache` both passed the vanilla transaction checks. Both passed
shaped and shapeless overlaps that alternate marked and unmarked stacks of
the same item, a component-crafting serializer fixture, and Recrafted's
packaged copper shears recipe. The test confirms the output items as well
as the changing preview counts. [Run the same fixtures](../benchmarks/README.md#release-study-verification)

The retained logs and input JAR hashes are under
`results/recrafted-compatibility/final/`.

The hash-verified official 26.3 client joined this combined server under
Xvfb. A separate smoke with Lithium 0.26.4 also passed furnace smelting,
Crafter output, and datapack and tag reload checks.

## Compound recipe prototypes

Four-entry adaptive crafting passed the crafting parity suite with
batching on stock Fabric and with custom ingredients on Recrafted.
Smithing and stonecutting passed before and after reload on Recrafted
with the indexes off and on. Smithing verified the custom recipe's
copied name and the lower-priority vanilla result for a marked stack.
Stonecutting verified quick-move admission and the output corresponding
to each tested option position, including a custom ingredient and a
reloaded result.

The furnace smoke passed with both cache switches on. The Recrafted
checks verified positive and negative reuse for blasting and vanilla
handling of component-sensitive smelting, both with and without
Lithium. The compound fixtures checked recipe-use accounting. The alpha
verification below covers player XP pickup. Logs and tested artifact hashes are under
`results/compound-prototypes-verified/`.

## Fabric Recipe API batching

With Fabric API 0.165.0 and vanilla crafting recipes, batching passed
inventory crafting, full and partial inventory capacity, dynamic dyed
output, honey-block remainders, a shift-click that changes the next
recipe, and recipe-book selection. The dedicated 3×3 crafting-table
comparison also verified remainders and the saved crafting counts.

Reloading a stock shapeless recipe with a `fabric:difference` ingredient
switched to packet-only batching and preserved the recipe output.
Recrafted used packet-only batching from startup. The off and on
controls passed custom component predicates, the custom serializer,
packaged copper shears, and the shift-click transition to a custom
recipe. The 3×3 Recrafted comparison preserved remainders and saved
statistics, with 260 versus 68 result-slot updates per 64 crafts.
The hash-verified unmodified 26.3 client also joined this server stack.
The headless client exercised the transactions.
Logs and tested input hashes are under `results/recrafted-packet-batching.2fuyFDHA/`.

## Alpha artifact

The `0.1.0-alpha.1` JAR passed the stock startup and reload checks with all
switches off. With the selected switches on, the same JAR passed Fabric API
crafting and ingredient reload, Recrafted crafting and recipe transitions,
and Recrafted smithing and stonecutting before and after reload. A
hash-verified Minecraft client joined the Recrafted server and used the
inventory screen to shift-craft two logs into eight planks.

On stock Fabric, a player took a furnace result and collected the expected
XP after alternating valid inputs. This passed with both caches off and
with both caches on. The cache run also verified positive and negative
reuse and reload. See `scripts/smoke-server.py --furnace-xp` to repeat the
XP fixture and `benchmarks/vanilla_client_smoke.py --craft` for the GUI
fixture.

The exact JAR matched a second Gradle build byte for byte. The tested
input hashes and logs are under `results/alpha.0.1.0-alpha.1/`. The tag
workflow checks that same JAR hash before attaching the prerelease asset.
