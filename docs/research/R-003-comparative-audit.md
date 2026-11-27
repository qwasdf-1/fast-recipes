# R-003 Comparative source audit

Revisions and license evidence are pinned in the [reference lock](../references.lock.json).
These are static findings. Compatibility and performance require launches and
controlled reproduction.

Reference selection as of 2026-11-25 uses the newest verified releases for
the closest game and loader targets. FastSuite 7.0.0, FastWorkbench 10.0.0,
and FastFurnace 10.0.0 have NeoForge 26.1.2 releases and AlCapone11 Fabric
26.2 ports. Recipe Essentials 4.7 and Lithium 0.26.4 have Fabric 26.3
releases. Fast Recipe Search 26.8.4 has a NeoForge 26.1.x release and a
Fabric 1.21.x release. Its pinned NeoForge 26.1 branch is the modern source
reference for this audit. [FastSuite Fabric](https://www.curseforge.com/minecraft/mc-mods/fastsuite-fabric-unofficial-port) · [FastWorkbench Fabric](https://www.curseforge.com/minecraft/mc-mods/fastworkbench-fabric-unofficial-port/files/all) · [FastFurnace Fabric](https://www.curseforge.com/minecraft/mc-mods/fastfurnace-fabric-unofficial-port) · [Recipe Essentials](https://www.curseforge.com/minecraft/mc-mods/recipe-essentials-forge-fabric/files/9511907) · [Lithium](https://modrinth.com/mod/lithium/version/Lal8mJGR) · [Fast Recipe Search](https://www.curseforge.com/minecraft/mc-mods/fast-recipe-search/files/all)

| Reference | Source anchor and approach | Main concern or overlap |
| --- | --- | --- |
| FastSuite 7 / AlCapone11 Fabric 26.2 | `CachedRecipeList.selectPivot`, `gatherCandidates`, `mergeByPriority` file a recipe under the items of a selective ingredient, deduplicate and sort candidates, then merge an ordered fallback list. The Fabric port injects at `RecipeMapMixin.getRecipesFor`. Original commit `6d975ef` introduces indexing and `bcfa981` restricts the preceding parallel matcher. | Candidate collection and sorting precede the first exact match. Test registry order, tagged ingredients, fallbacks and ordinary early hits. The 26.2 port is a reference for design, with a conflicting hook if ported to 26.3. |
| FastWorkbench 10 / AlCapone11 Fabric 26.2 | `MixinCraftingMenu`, `MixinInventoryMenu` and `FastBenchUtil` alter result computation and quick-moving stacks. `SlotUpdateManager` scopes updates around the transaction. | Overlaps crafting lookup and inventory mutation. Treat remainder, dynamic result, capacity and packet parity as separate checks. |
| FastFurnace 10 / AlCapone11 Fabric 26.2 | `MixinAbstractFurnaceBlockEntity.getRecipe` adds a current holder and failed-stack cache in front of vanilla's check. The port redirects the tick lookup and intercepts total cook time. | Inspect pinned-holder and failed-stack behavior across reload and custom context changes before using either optimization. |
| Historical FabricFastFurnace | `Hooks` and `AbstractFurnaceBlockEntityMixin` target 1.16.5's lookup and fuel calculation. | 26.3 has its own cached check and fuel component path. |
| Recipe Essentials 4.7 Fabric 26.3 | `ReloadableServerResourcesMixin` substitutes its own manager. `recipecache/RecipeManager.getRecipeFor` uses input fingerprints, verifies cached matches and falls through to vanilla on misses. Finalization clears caches and rebuilds ordering. | A cached match alone does not establish that earlier world-dependent recipes still fail. Recipe Essentials is the direct same-version comparison for cache-heavy cases. Its setup also affects recipe-book behavior. |
| Fast Recipe Search 26.8.4 / NeoForge 26.1 source | `RecipeManagerMixin.getRecipeFor` uses `RecipeDB` for configured recipe types and an ordered loop for the others. `RecipeDB` builds a tree from ingredient hashes and validates candidates with the exact matcher. | Cross-version algorithm reference. Verify priority, completeness, and index cost before considering the tree. |
| Lithium 0.26.4 | `world/block_entity_ticking/sleeping/furnace/AbstractFurnaceBlockEntityMixin` checks for sleep after `serverTick` and wakes on inventory or load events. | Profile alongside Lithium before work on repeated furnace ticking. Lithium remains optional. |

The older 1Influence artifacts declare Fabric environment `*`. Their mixin
configuration includes common entries. The FastWorkbench jar lists a
client-named mixin in that list, so metadata alone cannot establish dedicated
startup. These artifacts target earlier releases and were inspected without
execution.

## Strategy checklist

Fast Recipes implements ordered lookup, two candidate filters, a
repeated-input cache, crafting-grid batching, a furnace miss cache, and a hash tree in its
opt-in experiment JAR. The ordered loop is the full-scan control. See the [experiment report](R-005-experiments.md).

| Strategy | Reference implementation | Fast Recipes status |
| --- | --- | --- |
| Scan all recipes in order with a plain loop | Fast Recipe Search 26.8.4 uses this for unindexed recipe types. Vanilla 26.3 uses an ordered stream for this path. | Implemented as the `loop` control |
| Filter by the number of occupied crafting slots | Fast Recipes experiment | Implemented as `occupancy`. It buckets exact vanilla shaped and shapeless classes, retains unclassified recipes in order, and runs exact matching. |
| Index recipes by a selective ingredient | FastSuite 7 and its newer Fabric port gather candidates from the input items, then restore recipe order and merge unindexed candidates | Implemented independently as `ingredient`, with `combined` applying it within the occupied-slot bucket. |
| Search an item and tag hash tree | Fast Recipe Search builds an ingredient-hash tree for supported types, validates candidates, and has a serial fallback | Independent experimental `hash-tree` and `hash-tree-cache` modes |
| Reuse the last matching crafting recipe | FastWorkbench 10 rechecks the previous result recipe before searching | The `cache` modes reuse a match or miss for a repeated grid and recheck dynamic recipes in priority order. |
| Batch crafting-grid updates and shift-click processing | FastWorkbench 10 queues result updates and suppresses intermediate grid recomputation during shift-crafting | Experimental per-take batching via `fastrecipes.batchCrafting`. |
| Reduce crafting result packets | FastWorkbench 10 selectively sends result updates and accounts for dynamic outputs | Per-take batching removes intermediate result-slot updates from stock shift-clicks. |
| Cache input fingerprints and known recipe matches | Recipe Essentials 4.7 rechecks cached recipes, searches on misses, and clears its cache at finalization | One-entry cache with a trimmed grid key. It uses ordered or indexed lookup on a cold input. |
| Optimize recipe-book display, sorting and unlock tracking | Recipe Essentials 4.7 includes configurable recipe-book behavior | Absent |
| Reuse the last successful furnace recipe | FastFurnace 10 and its newer Fabric port keep the current holder. | Absent. Vanilla 26.3 supplies its own successful-recipe hint. |
| Remember a furnace input that failed to match | FastFurnace 10 and its newer Fabric port cache the failed stack | Bounded, per-manager experiment for exact vanilla cooking recipe types |
| Suspend idle furnace ticking | Lithium sleeps eligible furnaces and wakes them on inventory or load events | Absent. Lithium passed the combined smoke test for the cases below. |

FastSuite's move-to-front and parallel strategies and FabricFastFurnace's
1.16.5 fuel-table cache belong to the historical references. FastSuite 7
uses ingredient indexing. [FastSuite upstream release notes](https://www.curseforge.com/minecraft/mc-mods/fastsuite)

## Same-version runtime checks

The occupied-slot experiment and Lithium 0.26.4 passed dedicated startup,
vanilla-protocol join, furnace and Crafter checks, and datapack reload when
installed together. The experiment observed 1,266 crafting recipes before
reload and rebuilt against 1,267 afterward. The smoke console is under
`run/smoke-install/sessions/fast-recipes-c822fa837bbd/`.

Recipe Essentials 4.7 ran separately with Fabric API 0.165.0 and Cupboard
4.2. Each heavy-pack run verified 768 measured crafts. The dependency-only
control recorded 112.40 ms median server-thread CPU per batch, with all
12 windows passing host quality. Recipe Essentials recorded 34.98 ms with
9 of 12 windows passing host quality. The single-run comparison establishes
a viable same-version alternative for this repetitive fixture. A relative
ranking against the Fast Recipes prototype needs a matched campaign.
