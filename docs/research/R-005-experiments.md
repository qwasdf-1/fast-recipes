# R-005 Occupied-slot buckets

The initial candidate groups crafting recipes by required occupied slots.
Only exact vanilla shaped and shapeless classes supply the count. Unclassified
entries are inserted into every bucket in the order they appear in the
original collection. A lookup runs the exact matcher for each candidate in
that order. The fallback scans the original list for inputs outside the
bucket range. The isolated benchmark and experiment JAR share the implementation.

An exact shaped match needs the pattern's occupied slots. An exact shapeless
match first compares ingredient count. This filter uses those counts without
expanding item tags or caching matched outputs. The matcher still validates
offset, dimensions, mirroring and ingredient assignment. A full runtime
design would also need to preserve the active manager, level context, hint
precedence and the first eligible hook point.

The benchmark includes a plain ordered loop as a control to separate the
benefit of scanning fewer recipes from the benefit of the loop. Measure
reload build time and retained memory, especially when many recipes use the
unclassified fallback.

## Runtime experiment

The separate experiment JAR hooks the three-argument first-match lookup
for ordinary crafting inputs on the game server thread. It preserves
hinted calls, exact matching, the relative order of all retained candidates,
and vanilla across the other recipe types. Its indexed data belongs to the
active recipe manager, so replacing the manager on reload rebuilds it on
first use. This hook is exercised only with an explicit experiment mode.

The comparison uses the same JAR in disabled, ordered-loop, and occupied-slot
modes. The first server pilot uses two fresh instances per mode and dataset,
in forward and reverse order. This run estimates direction and variance.
Production acceptance requires stronger evidence, including a calibrated
paired comparison, compatibility, adversarial transactions, and index
memory and reload cost.

### Dedicated-server pilot result

Every run verified 768 measured crafts. Values are the medians of each fresh
instance's 12 measured windows, in server-thread milliseconds per 64 crafts
and fixture commands. The ranges contain two independent instances each.

| Data pack | Disabled | Ordered loop | Occupied-slot buckets |
| --- | ---: | ---: | ---: |
| Built-in | 23.57–38.38 ms | 25.87–32.46 ms | 32.02–36.65 ms |
| 10,000 additional recipes | 104.99–124.50 ms | 63.21–88.60 ms | 27.38–30.35 ms |

The smoke test with the bucket experiment enabled passed startup, vanilla
protocol login, furnace and Crafter behavior, and a Crafter miss-to-hit
reload. The heavy workload showed a reduction in every run of the bucket
mode compared with the disabled and loop modes. Built-in host quality was
8/12 and 10/12 in the bucket sessions. The built-in intervals overlap and the
number of independent runs leaves vanilla no-regression unestablished.

## Selective-ingredient prototype

FastSuite 7 motivated a separate ingredient arm. Fast Recipes selects the
required ingredient with the smallest available set of accepted items,
subject to a 64-item pivot limit. A matching input must contain at least one
of those items. The index stores recipes by their original ordinal. It
merges postings for items present in the grid together with the ordered
fallback and runs the exact predicate once per candidate. If a recipe
cannot safely be classified, it enters the fallback. The combined arm
builds ingredient postings within each occupied-slot bucket on first use.

The index lives with the active recipe manager. The original ingredient
prototype fell back to vanilla when the Fabric Recipe API module was
installed. The later implementation classifies Fabric ingredients per
recipe. See [Recrafted interoperability](#recrafted-interoperability).

The new same-slot workload adds four-slot recipes and crafts four-slot
tables. It removes the advantage the original one-slot heavy fixture gave
the occupied-slot filter. The isolated shared-ingredient workload also tests
what happens when many candidates have a pivot item present in the input.

### Isolated comparison

The focused run wrote 750 matched measurements to
`results/lookup.z3ml0Cbv/samples.csv`. All arms agreed on each measured
batch's successful lookup count. The differential fixture checked selected
recipe identity before timing. Times below are median thread CPU in
microseconds per lookup, with ten batches per arm.

| Dataset and input | Vanilla stream | Ordered loop | Occupied slots | Ingredients | Combined |
| --- | ---: | ---: | ---: | ---: | ---: |
| Built-in, second hit | 45.86 | 20.78 | 5.56 | 4.22 | 2.47 |
| 1,000 mixed-slot recipes, shared-ingredient miss | 40.52 | 25.58 | 16.69 | 18.07 | 11.15 |
| 10,000 same-slot recipes, late hit | 1,471.54 | 1,303.16 | 1,285.66 | 0.40 | 0.40 |
| 10,000 same-slot recipes, shared-ingredient miss | 1,876.25 | 1,686.40 | 1,693.51 | 1,169.57 | 1,165.26 |
| 10,000 same-slot recipes, first hit | 0.41 | 0.32 | 0.32 | 0.36 | 0.37 |

The filters compound when several slot counts share a pivot item. The
same-slot fixture gives both index arms nearly the same candidate set.
Its shared-ingredient miss still exercises thousands of exact predicates.
The early hit shows an overhead when vanilla can stop immediately. All
index timings here use warm state. Index construction, retained memory
and run-to-run uncertainty still need measurement before release.

### Runtime correctness checks

The ingredient mode and the combined mode with Lithium 0.26.4 passed
dedicated startup, protocol-777 login, furnace and Crafter checks, and
datapack reload. A second reload changed only the ingredient tag. The
Crafter stopped accepting the removed item and still accepted the retained
item. A separate Fabric API smoke verified that the combined mode
selected the vanilla fallback. Raw logs are in `results/ingredient-smoke/`.

### Dedicated-server comparison

The checkpointed run in `results/ingredient-server-pilot/` used the same
four-slot shift-click workload on both datapacks. All 15 fresh instances
verified 512 measured crafts each. Median server-thread milliseconds per
64 crafts and fixture commands appear below. Built-in values are from one
instance per mode. Same-slot ranges cover two independent instances per
mode in forward and reverse order. The ranges are observed run medians.

| Data pack | Vanilla | Ordered loop | Occupied slots | Ingredients | Combined |
| --- | ---: | ---: | ---: | ---: | ---: |
| Built-in | 97.00 | 91.03 | 85.97 | 77.01 | 65.66 |
| 10,000 additional four-slot recipes | 81.31–92.42 | 73.09–79.22 | 74.33–83.64 | 79.98–85.87 | 75.09–82.83 |

Both same-slot vanilla sessions passed the host check on every window.
The same-slot index arms passed on 14 of 16 ingredient windows and
15 of 16 combined windows. The table includes all measurements.
The combined arm had the lowest built-in median in this single pass.
The server pilot leaves the relative advantage of either index over the
ordered loop unresolved on the same-slot pack. Its measured windows
include inventory commands and transaction work. The isolated lookup gains
alone do not establish a corresponding server CPU gain.

## Repeated-input cache and crafting-update batches

The `cache` mode uses an ordered scan on a cold lookup and stores one
result per recipe manager. `combined-cache` uses the combined index on a
cold lookup. The cached key uses trimmed grid dimensions and the item
holder in each slot. In the traced vanilla 26.3 code, shaped matching uses
item membership and shapeless matching accounts for one item per nonempty
slot. A cached vanilla winner still competes with earlier dynamic recipes,
which are checked with the live input and level in original order. A cached
miss rechecks every dynamic recipe. The result cache holds recipe identity
only. Recipe assembly, remainders, and output synchronization follow the
normal paths. Both cache modes use the vanilla fallback when Fabric Recipe
API is present.

The focused cache run retained 750 rows in
`results/lookup.UtH2b042/samples.csv`. All arms agreed on successful lookup
counts. The identity test includes a count-sensitive custom recipe that
takes precedence over both a cached match and a cached miss. In the built-in
second-hit fixture, median lookup CPU fell from 3.309 to 1.242 microseconds
when adding the cache to the combined index. The mixed-input case measured
1.383 and 1.359 microseconds respectively. In the 1,000-recipe mixed-slot
mixed-input case the combined index took 0.107 microseconds and adding the
cache took 0.118 microseconds. The warm isolated numbers do not predict the
cost of a full server transaction.

The `fastrecipes.batchCrafting` experiment coalesces grid-result updates
within an individual shift-click result take. It flushes the final grid
change before vanilla's next take can begin. The batch uses the actual
vanilla result take, including item consumption, remainders, inventory
insertion and craft hooks. The guard requires stock menu and result-slot
classes on the server thread, falls back with Fabric Recipe API, and
checks the active manager for crafting recipe classes from other
packages. Other mods that alter vanilla transactions still need dedicated
compatibility tests.

### Transaction comparison

All ten dedicated runs in `results/transaction-pilot.N2Q2KNoO/` confirmed
384 measured crafts each, with six measured windows and three warmups per
instance. Every run used the same 10,000-recipe four-slot datapack. The
four combined-index configurations ran in forward and reverse order.
The loop and cache-only configurations each ran once.

| Mode | Median CPU per 64 crafts and fixture commands | Result-slot updates per batch |
| --- | ---: | ---: |
| Ordered loop | 98.38 ms | 261 |
| Cache plus ordered loop | 102.97 ms | 261 |
| Combined index | 88.17–97.61 ms | 261 |
| Combined index plus cache | 81.76–87.08 ms | 261 |
| Combined index plus batching | 79.39–101.96 ms | 69 |
| Combined index, cache and batching | 77.87–87.67 ms | 69 |

Batching removed 192 result-slot updates per batch in every measured
window. At this sample size, batching's incremental CPU benefit with
the cache is unresolved. Quality flags were retained, including only
three qualifying windows out of six in each unbatched combined-index
instance. The ranges are observed instance medians.

The first cache server campaign encountered a client result timeout. The
revised harness waits for actual result-slot packets and drains them
through a vanilla system-chat fence outside the measured window. A fresh
comparison finished all ten arms. The 2×2 differential checks also
verified one honey block, four glass bottle remainders, and saved crafting
statistics across the disabled control, cached lookup, batching, and both
together. See `results/transaction-smoke.BtZISwKC/`.

A separate 3×3 crafting-table comparison verified the same honey-block
remainders and saved crafting statistics with both prototypes active and
Lithium 0.26.4 installed. Both the disabled control and the compounded arm
confirmed 128 crafting tables. The reload and Fabric Recipe API fallback
checks also passed for the cache. Evidence is in
`results/table-smoke.HwCkdGPv/`.

## Furnace miss cache

The opt-in `fastrecipes.furnaceMisses` experiment stores repeated misses
for smelting, blasting, and smoking in the active recipe manager. It
enables a type only when every recipe of that type uses the exact vanilla
cooking class. Vanilla's single-item predicate tests item membership, so
the cache uses holder identity. The bounded cache clears at 64 entries per
type. A new recipe manager starts empty, and the mode is off when Fabric
Recipe API is present. Successful hints, burn timing, fuel, and output
handling follow the vanilla paths.

The furnace pilot uses 97 loaded furnaces with fuel and a bedrock input.
It measures eight windows of 160 sprinted ticks after three warmups. A
separate check adds a bedrock smelting recipe during reload and verifies
that an input that previously missed can smelt.

All four instances completed the sprint and reload checks. The warmed cache
reported reuse on both enabled stacks. The tested furnace retained its coal
and invalid input through the timed windows, then produced the expected
output after reload. The measured case covers smelting. Blasting and
smoking use the same exact-class guard in the implementation.

| Stack | Median server-thread CPU per 160 ticks | Host-quality passes |
| --- | ---: | ---: |
| Fabric control | 601.70 ms | 8/8 |
| Fabric with miss cache | 444.82 ms | 8/8 |
| Lithium | 413.33 ms | 8/8 |
| Lithium with miss cache | 391.94 ms | 6/8 |

These are single-instance medians from the fueled-invalid fixture under
`results/furnace-pilot.93MYUjOi/`. The stock-stack reduction was about 26%.
The measured increment above Lithium was smaller and remains unresolved.

## Ingredient hash tree

The `hash-tree` mode sorts a recipe's required ingredient hashes into a
multiset path in a trie. A grid slot contributes possible hash tokens for
its item. The tree rejects paths that exceed those counts. Collisions and
overlapping tag memberships can admit extra candidates. The exact matcher
still validates them and enforces ingredient assignment. The tree sorts
candidates and unclassified recipes by original ordinal before matching.
Recipes with very broad or unavailable ingredient sets enter the fallback.
Inputs with more than 128 distinct possible hashes use the ordered scan.

The `hash-tree-cache` arm combines the same tree with the repeated-input
cache. Both tree modes use the existing Fabric Recipe API fallback. The
isolated identity checks include an overlapping requirement case where
one spruce plank cannot satisfy two required ingredients.

The isolated comparison in `results/lookup.jZYWU4FM/samples.csv` retained
750 samples with matching successful lookup counts. Median CPU times are
in microseconds per lookup.

| Workload | Combined index | Combined index and cache | Hash tree | Hash tree and cache |
| --- | ---: | ---: | ---: | ---: |
| Built-in, mixed inputs | 1.438 | 1.323 | 2.248 | 1.983 |
| 1,000 mixed-slot recipes, mixed inputs | 0.088 | 0.098 | 0.184 | 0.168 |
| 1,000 same-slot recipes, shared-ingredient miss | 203.615 | 0.024 | 0.123 | 0.029 |

The tree's stronger rejection pays off when a cold input has a commonly
shared pivot and fails other requirements. In repeated misses, caching
already avoids most matching work. The simpler index is faster on the
sampled mixed workloads. Construction time and memory remain unmeasured.

All four dedicated comparisons in `results/hash-tree-pilot.6jarC8Xe/`
completed 384 measured shift-click crafts each on the same four-slot heavy
datapack. Each row is one JVM with six measured windows.

| Mode | Median server-thread CPU per 64 crafts and fixture commands | Host-quality passes |
| --- | ---: | ---: |
| Combined index | 120.99 ms | 4/6 |
| Combined index and cache | 87.75 ms | 5/6 |
| Hash tree | 80.67 ms | 4/6 |
| Hash tree and cache | 76.85 ms | 4/6 |

The tree was faster in this server pass. The single-instance results and
host-quality failures leave the ranking unresolved. The hash tree and
cache also passed the tag reload and fueled furnace reload when enabled
together with the furnace miss cache. The tree selected the vanilla
fallback with Fabric Recipe API installed. See the hash-tree logs under
`results/furnace-pilot.93MYUjOi/`.

In the final 3×3 smoke, the hash tree, repeated-input cache and batching
ran together with Lithium. The fixture verified 128 crafting tables,
one honey block, four glass bottle remainders, and saved crafting
statistics. See `results/hash-tree-table-composition.log`.

## Adaptive lookup with a shared cache

The adaptive mode checks the existing one-entry cache first. On a cold
lookup, it uses the occupied-slot bucket and merges the selective-ingredient
postings in original recipe order. The sum of distinct posting lengths,
capped at the number of indexable recipes in the bucket, estimates the
remaining work. Unclassified recipes stay in the ordered fallback. The
mode keeps the posting scan for one-slot inputs and for small estimates.

For a dense estimate, it checks up to four candidates in order to protect
early hits. If more candidates remain, it lazily builds a hash tree for
that slot-count bucket. Both structures use the same recipe ordinals, so
the tree begins at the first untested candidate and the matcher never
replays the checked prefix. Result identity still comes from the exact
vanilla matcher. The recipe-manager snapshot owns the cache and indexes.

The cutoff sweep compares 64, 256 and 512 against the fixed cached modes.
It covers cold and repeated queries, early hits, late hits, crowded misses,
and geometry misses where hashes cannot help. The benchmark records
allocation through construction and first use separately from warm
lookup allocation. Crossover and cold setup measurements inform the pilot
cutoff. Retained heap and broad campaign variance remain release evidence.

### Cutoff calibration

The 850 measurements in `results/lookup.jrlspufQ/samples.csv` agreed on
all per-batch hit counts. The build verified vanilla recipe identity,
including a forced switch after four probes with a count-sensitive custom
recipe in the checked prefix. Times are median thread CPU in microseconds
per lookup.

| Fixture | Combined index + cache | Hash tree + cache | Cutoff 64 | Cutoff 256 | Cutoff 512 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Built-in mixed inputs | 1.342 | 2.909 | 1.361 | 1.314 | 1.324 |
| 96 candidates, cold miss | 19.775 | 0.114 | 1.065 | 19.753 | 19.945 |
| 320 candidates, cold miss | 69.202 | 0.135 | 1.127 | 1.124 | 69.453 |
| 1,024 candidates, cold miss | 547.893 | 0.346 | 2.639 | 2.573 | 2.572 |
| 1,024 candidates, cold early hit | 0.541 | 0.676 | 0.546 | 0.543 | 0.548 |
| 1,024 candidates, cold geometry miss | 51.029 | 46.677 | 49.220 | 49.960 | 45.931 |

The runtime pilot uses 256. It catches the mid-sized and large crowds in
the sweep and keeps small buckets on the posting scan. The 64 cutoff's
first query allocated about 364 kB in the 96-candidate cold-miss fixture,
compared with about 122 kB at 256. At 1,024 candidates, the adaptive first
query allocated about 3.44 MB versus 1.36 MB for the combined index alone.
These figures include transient allocations. The cost becomes worthwhile
only if the bucket sees enough expensive cold lookups. The benchmark has
yet to establish a retained-heap budget or a release cutoff.

### Dedicated-server comparison

All six instances in `results/adaptive-pilot.vc4C9vXS/` loaded the same
10,000-recipe shared-pivot pack and verified 192 empty results each.
Each measured window sent 32 swaps between two invalid four-slot grids.
The forward and reverse runs used the same experiment JAR.

| Mode | Median server-thread CPU per 32 grid swaps, range of two instance medians | Host-quality passes |
| --- | ---: | ---: |
| Combined index + cache | 113.50–142.28 ms | 12/12 |
| Hash tree + cache | 17.41–19.30 ms | 10/12 |
| Adaptive cache | 21.35–23.72 ms | 11/12 |

The adaptive mode captured most of the tree's savings in the targeted
server workload. The fixed tree was faster on this dense-only fixture.
In the isolated built-in mixed workload the adaptive mode stayed close
to the combined index. The server comparison is a pilot and does not
establish a universal ranking.

Adaptive mode passed tag-only reload and a furnace miss-to-smelt reload
with furnace miss caching enabled. This version used the vanilla fallback
with Fabric Recipe API installed. With Lithium and crafting-update batching
enabled, the 3×3 test verified 128 crafting tables, one honey block,
four glass-bottle remainders and saved crafting statistics.

### Recrafted interoperability

With Fabric Recipe API installed, Fast Recipes reads the original shaped
pattern or shapeless ingredient list to identify custom predicates. The
ingredient postings, hash tree, and repeated-input cache classify exact
vanilla shaped and shapeless recipes with vanilla ingredients. They keep
custom recipe classes and Fabric ingredients in recipe order. On a cached
hit, any earlier custom recipe is evaluated against the actual grid. On a
cached miss, all custom recipes are evaluated. This preserves Recrafted's
component checks when the input item type stays the same.

Recrafted and Fabric API are optional at runtime. Fast Recipes uses the
Fabric Recipe API module when the server already has it. At this stage,
both batching and furnace miss caching used a whole-module guard.
The [compound prototypes](#compound-cache-and-station-prototypes) narrowed
the furnace guard to individual recipe types.

### Incremental adaptive tree construction

Dense lookups advance the tree by up to 512 recipes per call. The builder
checks a 1 ms elapsed target between recipes. The budget is a scheduling
target, so one expensive recipe, allocation, or JVM pause can exceed it.
While construction is in progress, the postings finish the current search.
The first lookup that completes the tree switches at the next untested
recipe ordinal. Construction state belongs to that recipe manager and
is discarded on reload.

In the follow-up dense diagnostic, the initial tree became available on the
38th eligible lookup. The slowest step took 7.24 ms and the steps totalled
69.59 ms. The earlier one-shot build took 82 ms in a separate diagnostic.
The initial four-slot postings still took 30.96 ms to build. After reload,
the postings took 22.58 ms and the initial 32 alternating grid swaps
advanced the tree 16 times. The longest of those steps took 2.33 ms and
the tree was still building when the diagnostic took its heap sample.
Use that heap sample for the in-progress state only. Raw timings are
under `results/mixed-release-study.DCIVFWoc/`.

### Release-study first-use and heap measurements

Separate Linux JVM instances ran the deterministic 10,000-recipe
dense-pivot fixture before and after a reload. All used Java 25 and the
same instrumented experiment JAR. The live heap columns are whole-server
post-GC differences against the corresponding disabled control state.
The counts do not isolate every object reachable from an index.

| Mode | Lookup construction, first use / after reload | Adaptive tree build, first use / after reload | Live heap difference, first use / after reload |
| --- | ---: | ---: | ---: |
| Combined index + cache | 15.1 / 8.9 ms | — | +1.3 / −6.9 MiB |
| Hash tree + cache | 57.2 / 40.1 ms | — | +8.2 / +2.0 MiB |
| Adaptive cache | 11.1 / 16.1 ms | 82.0 / 35.8 ms | +5.0 / +6.5 MiB |

The adaptive histogram held 15,662 tree nodes at both sample points,
about 489 KiB of shallow node storage. The fixed tree held 16,871 nodes,
about 527 KiB. Arrays, lists, and maps add to those totals. The negative
combined-index delta after reload illustrates whole-process variation.
This diagnostic gives the scale of live memory and the potential first
dense request stall. The startup figures are individual observations.
The two adaptive timing columns cover separate construction paths and
may occur on different requests. Raw histograms and logs are linked by
the diagnostic records under `results/release-study.7oTdeErg/`.

## Compound cache and station prototypes

The crafting cache supports 1–16 recent inputs. Its one-entry fast path
remains the default for cached lookup modes. A larger capacity remembers
both hits and misses across grid changes. Custom predicates are still
evaluated in recipe order, and the recipe manager owns the entries.

The smithing prototype indexes vanilla transforms and trims by one
required slot and checks the full predicate on the ordered candidates.
Custom classes and custom ingredients stay in the ordered fallback. The
stonecutting prototype indexes the server's menu catalog and preserves
the complete ordered option list, including entries with custom
ingredients. It also uses the index for the quick-move admission check.
The catalog is rebuilt on reload and no client changes are required.

The furnace prototype tests vanilla's existing hint first. The later
unhinted lookup can reuse previously observed hits and misses. Each
outcome has a 64-entry bound per recipe type and manager. A type with a
custom recipe class or Fabric ingredient uses vanilla lookup. This
allows eligible types to run both caches on a Fabric API server even if
another cooking type has custom recipes.

Isolated median server-code CPU time per lookup for the built-in fixtures
is shown below. The furnace candidate arm models the hint and cache path
in the fixture. These numbers exclude normal server and menu activity.

| Lookup fixture | Vanilla | Prototype |
| --- | ---: | ---: |
| Smithing with two valid inputs and a miss | 732 ns | 188 ns |
| Stonecutter ordered option list | 3,988 ns | 118 ns |
| Stonecutter quick-move admission | 1,825 ns | 40 ns |
| Furnace alternating two valid inputs | 700 ns | 20 ns |

Vanilla took roughly 14 ns per lookup with a valid repeated furnace hint
in a separate arm. The larger crafting cache had mixed results on built-in
inputs, with little margin in the two-input alternating fixture and extra
cost for capacity 16 on mostly unique grids.

On the dedicated dense-pivot fixture, four entries reduced the median
server-thread CPU per 32 alternating invalid grid updates from 44.99 ms to
16.98 ms in the first block and from 15.91 ms to 9.59 ms in the second.
All 16 measured windows passed the configured quality checks and
verified every result stayed empty. The blocks ran in reverse order with
the same JAR and datapack. This is a screening result for alternating
dense misses. Keep capacity one as the default while broader workloads
remain inconclusive.

The furnace smoke verified positive and negative reuse on stock Fabric,
reload, recipe-use accounting, and Recrafted's component-dependent
smelting. With Recrafted and Lithium, blasting reused both cached hits
and misses while smelting preserved its custom ingredient checks. Cooking
recipe-use accounting was inspected at the furnace. Player XP pickup was
outside this fixture.

The raw timing batches and server parity logs are under
`results/compound-prototypes-verified/`.
