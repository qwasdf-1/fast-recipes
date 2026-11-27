# Current state

Fast Recipes builds a standalone Minecraft 26.3 Fabric alpha JAR for
dedicated servers. Runtime switches default to off. The regular build
includes the optimization hooks and the Apache-2.0 license.

The benchmark source set contains a runnable isolated lookup fixture and a
prototype that filters crafting recipes by occupied slot count. It uses the
actual 26.3 built-in server data pack and five synthetic registry sizes.
The recorded run passed the isolated identity checks and validated 864 timing
samples. See [the baseline report](research/R-004-baseline.md).

Ten upstream checkouts and five published artifacts are pinned. The vanilla
audit confirmed existing furnace and Crafter caches and identified ordinary
crafting and remainder lookup as the first profiling targets. Subsequent reports cover dedicated-server timing, differential gameplay, and
Lithium compatibility. See [research status](research/README.md).

## Dedicated-server smoke result

The packaged JAR loaded on Fabric Loader 0.20.1. A headless client completed
the vanilla 26.3 protocol handshake, joined, and received a recipe award.
The server smelted cobblestone into stone, routed four planks from a powered
Crafter into a chest, and turned a cached Crafter miss into a hit after
datapack reload. These checks exercise the research scaffold and the vanilla
baseline. The full console is retained under
`run/smoke-install/sessions/fast-recipes-995bfec955db/console.log`.

## Experiment status

The initial occupied-slot, ingredient and combined modes passed the
server and reload smoke checks.
In the first dedicated pilot, it reduced server-thread CPU in the
10,000-recipe fixture. The built-in case is inconclusive. See the
[experiment report](research/R-005-experiments.md).

The selective-ingredient and combined modes pass the recipe-identity and
dedicated-server checks, including a tag-only reload. The combined mode's
Lithium smoke passed. An early Fabric API smoke verified the whole-lookup
fallback, which the Recrafted interoperability work narrowed to custom recipes.
The isolated benchmark found a benefit from combining the filters on
mixed slot counts. The dedicated comparison leaves the advantage over
the ordered loop unresolved for the four-slot heavy pack.
[Selective-ingredient results](research/R-005-experiments.md#selective-ingredient-prototype)


The experiment JAR also contains repeated-input caching and optional
shift-click grid-update batching. In the four-slot dedicated comparison,
batching dropped result-slot updates from 261 to 69 per 64 crafts. The
prototypes passed checks for remainders and persisted crafting statistics
in the player's grid. The CPU comparison is a pilot.
[Cache and batching results](research/R-005-experiments.md#repeated-input-cache-and-crafting-update-batches)

The experiment JAR also contains the bounded furnace miss cache and two
hash-tree lookup modes. The furnace reload test passed with and without
Lithium. The hash tree passed identity, overlap, and tag-reload checks.
Its dedicated comparison needs repetition. All remain opt-in.
[Latest experiments](research/R-005-experiments.md#furnace-miss-cache)

The opt-in `adaptive-cache` mode shares the repeated-input cache and
chooses between the occupied-slot ingredient index and a lazily built hash
tree. It passed priority and reload checks, a dedicated dense-miss pilot,
and a 3×3 composition with Lithium and batching.
[Adaptive experiment](research/R-005-experiments.md#adaptive-lookup-with-a-shared-cache)

The latest experiment supports Recrafted's custom recipe classes and Fabric
ingredients through ordered per-recipe fallback. It continues to optimize
ordinary crafting recipes on the same server. Dense adaptive tree setup is
spread across multiple lookups. [Implementation and compatibility checks](research/R-005-experiments.md#recrafted-interoperability)

## Release study

The 86-instance dedicated-server study completed. Adaptive lookup had
large savings on the dense-pivot screening fixture. The built-in paired
comparison leaves no-regression unestablished. Batching's update-count
reduction replicated, with inconclusive CPU results. All switches remain
opt-in. [Release-study decision](research/R-006-decision.md#release-study-decision)

The follow-up mixed crafting study measured lower median CPU for adaptive
lookup on both the built-in and same-slot heavy packs. Both paired
intervals still include a slowdown, so adaptive lookup remains opt-in.
[Mixed workload results](research/R-006-decision.md#mixed-crafting-and-recrafted-follow-up)

The experiment JAR also offers an optional larger crafting cache, smithing
and stonecutting indexes, and a successful furnace lookup cache.
Furnace hit and miss caching can run together on eligible cooking types.
The new switches remain opt-in and the default capacity of a selected
crafting cache is one. [Compound prototype results](research/R-005-experiments.md#compound-cache-and-station-prototypes)

Batching coalesces result recomputations on supported crafting recipe sets.
On Recrafted, it preserves the intermediate callbacks and coalesces
the result-slot packets per take. [Batching verification](verification.md#fabric-recipe-api-batching)
