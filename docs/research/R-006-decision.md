# R-006 Decision

Keep every optimization opt-in in the standalone Fabric experiment JAR.
The release study completed on the built-in recipe pack and two deterministic
10,000-recipe fixtures. Its built-in comparison leaves the no-regression gate
open. See [Release-study decision](#release-study-decision) for the completed
comparison.

| Candidate | Decision | Evidence |
| --- | --- | --- |
| Ordered loop | Keep as a control | It reduces heavy-fixture CPU relative to the vanilla stream. The bucket consistently does less work in that fixture. |
| Occupied-slot buckets | Retain in the experiment JAR | Heavy-pack CPU fell from 104.99–124.50 ms to 27.38–30.35 ms per 64 crafts in the dedicated pilot. Observed craft counts matched. The built-in pilot leaves ordinary-workload no-regression unestablished. |
| Selective-ingredient index | Retain in the experiment JAR | It substantially reduces same-slot late-hit and miss costs in the isolated comparison. A shared pivot can still leave many exact matches to evaluate. |
| Combined index | Retain in the experiment JAR | It helps when a pivot item is present across several slot counts. In the isolated same-slot case it is effectively tied with ingredient indexing alone. |
| Repeated-input cache | Retain in the experiment JAR | The combined filter and cache compose. Repeated lookups benefit in isolation. Mixed inputs and the cache-only server arm leave a clear unconditional win unestablished. |
| Crafting-grid update batching | Retain in the experiment JAR | Every dedicated window sent 69 result-slot updates when batching was enabled, compared with 261 in the controls. Its incremental server CPU benefit with caching remains unresolved. |
| Furnace miss cache | Retain as an independent opt-in switch | The fuelled-invalid pilot reduced CPU on the Fabric control. The incremental benefit above Lithium is unresolved. The reload smoke passed for both stacks. |
| Ingredient hash tree | Retain as a comparison arm | It wins the targeted cold shared-pivot miss. The simpler index is faster on the sampled mixed workloads. |

The release gate also needs a workload beyond sustained shift-clicks and
dense misses, plus broader mod compatibility. The verified transaction and
client cases are listed in [verification](../verification.md).

Recipe Essentials also completed the heavy fixture with a substantially
lower observed CPU median than its dependency-only control in one run.
Lithium passed the bounded compatibility smoke. This leaves a standalone
experiment worth retaining and a same-version alternative worth comparing
under the release workload. The [comparative audit](R-003-comparative-audit.md)
records the specific stacks and limits.

## Recommended experiment order

1. Continue the comparison with a realistic mixed-slot pack containing
   frequently used pivot items and multiple recipes with the tested slot
   count. Include early hits, late hits, misses, and overlaps. Calibrate
   built-in pack variance with independent JVMs and keep the ordered loop
   as a control.
2. Prefer occupied-slot, ingredient, or combined lookup based on the
   measured workload and the correctness gate. Keep the hash-tree strategy
   for workloads where a simpler index leaves a measured gap.
3. If repeated lookups dominate once candidate selection improves, compare
   Recipe Essentials 4.7 on the same stacks. Consider caching only where
   input, world dependencies, ordering, and reload invalidation can be
   accounted for. Profile FastWorkbench-style batching only if the remaining
   cost is in the crafting transaction.
4. Consider a furnace miss cache only if a fueled invalid-input workload has
   measurable residual cost with vanilla and Lithium. The recent FastFurnace
   and Lithium strategies are the relevant references for that comparison.

These are experiment priorities. Acceptance still depends on the workload
and correctness evidence above.

The selective-ingredient prototype and combined arm passed identity, smoke,
and tag-reload checks. In the dedicated comparison, the same-slot pack left
their advantage over the ordered loop unresolved. The built-in combined arm
had the lowest median in one pass. Keep the modes opt-in until a calibrated
server campaign establishes a winner and completes the release checks.
[Selective-ingredient results](R-005-experiments.md#selective-ingredient-prototype)

The repeated-input cache and per-take update batching compose. The packet
reduction held across both lookup modes in the dedicated pilot. Keep
server CPU as a separate decision criterion. The furnace miss cache and
hash tree also completed their targeted experiments. The furnace result
supports another stock-stack comparison if that workload matters. The
tree's cold-pivot benefit merits retaining the arm, with the existing
combined index and cache as the general comparison.

## Adaptive cache decision

Keep `adaptive-cache` as the opt-in candidate for workloads that mix
ordinary and dense cold lookups. Use the measured pilot cutoff of 256
estimated indexed candidates. The dense server pilot shows a reduction
against the combined index and cache, while the isolated built-in
measurements stay close. Keep fixed lookup modes for workload comparisons.
The earlier pilot and cutoff data are in the [adaptive results](R-005-experiments.md#adaptive-lookup-with-a-shared-cache)

## Release-study decision

The checkpointed campaign completed 86 fresh JVM instances. Four took heap
diagnostics. The timing instances verified 18,432 crafts and 1,280 empty
results. All 328 measured windows passed the configured mc-lab host-quality
checks. The study pinned each server to four logical CPUs and used the same
experiment JAR in every arm. Off uses vanilla lookup with the experiment's
runtime switches disabled. Transaction and client verification ran on
separate physical cores during a portion of the study. The same-mode control
captures variation between complete server instances.

Median paired changes below are in server-thread CPU. Negative values are
faster. Parentheses contain exact paired intervals with 96.875% achieved
coverage for the six-block comparisons. Dense-pivot has two screening blocks
and 50% coverage. Batching is compared with adaptive alone. Other arms use
off as their control.

| Comparison | Built-in shift-click | Same-slot heavy shift-click | Dense-pivot misses |
| --- | ---: | ---: | ---: |
| Repeated off control | −10.0% (−29.8%, +17.5%) | −13.4% (−33.5%, +35.7%) | −17.3% |
| Combined index + cache | −6.7% (−27.0%, +21.9%) | −31.0% (−41.5%, +6.4%) | −36.9% |
| Hash tree + cache | −3.4% (−27.0%, +6.5%) | −27.5% (−48.5%, −4.6%) | −88.4% |
| Adaptive cache | −8.1% (−31.4%, +24.6%) | −21.8% (−49.6%, +23.3%) | −91.1% |
| Adaptive cache with batching | −1.1% (−20.3%, +64.5%) | +3.2% (−62.7%, +28.9%) | — |

Keep adaptive as the mixed-workload opt-in. It captures the dense lookup
gain, and the built-in interval admits a meaningful slowdown. The fixed
tree ruled out slowdown for the heavy shift-click fixture, with an upper
bound just outside the planned 5% gain threshold. That result does not
establish a broader default. The first dense adaptive tree build took
82 ms in its diagnostic instance, which can exceed a 50 ms tick budget.
See [first-use and heap measurements](R-005-experiments.md#release-study-first-use-and-heap-measurements).

Keep batching independent. It reliably cut updates from 261 to 69 per
64 crafts on both packs. The paired CPU results leave its added benefit
unresolved. Furnace miss caching stays independent and opt-in based on the
earlier focused comparison.

The final parity checks passed ordinary and partial-capacity crafting,
full inventory protection, dynamic output and recipe-book selection.
The official client passed local login. These checks support continued
opt-in experimentation. Default promotion remains deferred.

The plan, per-instance checkpoints and machine-readable paired results
are under `results/release-study.7oTdeErg/`.

## Mixed crafting and Recrafted follow-up

The follow-up completed 38 fresh JVM instances. Two captured dense setup
diagnostics. The remaining 36 verified 1,728 crafts across 144 measured
windows, all of which passed the configured mc-lab quality checks. Each
window had eight ordinary result pickups and a four-result shift-click.
Each dataset used six counterbalanced blocks with an off, repeated off,
and adaptive instance on the same pinned four CPUs.

| Comparison to off | Built-in recipes | 10,000-recipe same-slot pack |
| --- | ---: | ---: |
| Repeated off | +7.8% (−2.7%, +64.7%) | −0.3% (−17.2%, +11.5%) |
| Adaptive cache | −8.6% (−15.1%, +27.2%) | −23.2% (−32.1%, +1.4%) |

Figures are paired median changes in server-thread CPU with 96.875%
intervals. Both adaptive intervals include a slowdown, and the repeated
control varies substantially on the built-in pack. Keep adaptive lookup
opt-in. The incremental tree spreads the dense setup cost across eligible
lookups, with a 7.24 ms longest observed step and a 30.96 ms first build
of the four-slot postings. [Dense setup timings](R-005-experiments.md#incremental-adaptive-tree-construction)

Crafting parity passed with the Recrafted aggregate and Fabric API, and
the official client joined the combined server. Recrafted plus Lithium
also passed the dedicated reload smoke. [Compatibility checks](../verification.md#recrafted-parity)

The plan, checkpoints, and summary are under
`results/mixed-release-study.DCIVFWoc/`.

## Fabric batching and station menu pilot

The batching guard now checks the active crafting recipes for supported
classes and vanilla ingredients. On the tested Fabric API stack, three
measured windows each took 64 crafting tables. The control sent 260
result-slot updates per window and batching sent 68. Both preserved
remainders and saved stats. One fresh instance per arm establishes the
packet-count result for this fixture, with insufficient replication for
a CPU decision. A custom Fabric ingredient added on reload restored
vanilla update handling. The Recrafted aggregate also retained vanilla
updates for its mixed recipe set. [Batching parity](../verification.md#fabric-recipe-api-batching)

The dedicated station pilot used two counterbalanced pairs for the
built-in fixture and two for a deterministic pack adding 5,000 recipes
per station. A separate pair used the Recrafted aggregate and Fabric API.
All 100 measured windows passed the configured quality checks, with 170
verified menu results. Server CPU changes below compare each indexed
instance median with its paired control.

| Recipe set | Smithing | Stonecutting |
| --- | ---: | ---: |
| Built-in, pair 1 | +45.5% | −7.9% |
| Built-in, pair 2 | −35.1% | −34.6% |
| Heavy, pair 1 | −12.7% | −23.9% |
| Heavy, pair 2 | +35.7% | +68.4% |
| Recrafted, one pair | −57.2% | −52.7% |

The menu windows include command, inventory and menu-handling costs.
The paired results vary enough that the isolated lookup gains still
leave ordinary-workload non-regression and server impact unestablished.
Keep both indexes and batching opt-in. The raw windows and summary are
under `results/station-release.gPA60IdI/`.

## Recrafted packet batching and station preview study

On Recrafted, batching retains every intermediate crafting recomputation
and sends only the last result-slot update from each shift-click result
take. The 3×3 test measured 260 updates with batching off and 68 with it
on for 64 crafts. Both arms checked remainders and saved statistics.
The three measured windows per arm came from one JVM instance each.
Median server-thread CPU was 40.3 ms off and 56.9 ms on in that pair.
The packet reduction replicated within the fixture. The CPU comparison
leaves the ordinary-workload gate open. [Batching verification](../verification.md#fabric-recipe-api-batching)

The station follow-up measured the packets that trigger previews. Staging
items, taking results, and checking the inventory ran outside the timed
intervals. Six blocks each on the built-in and heavy packs used an off,
repeated-off, and indexed instance. The heavy pack added 5,000 recipes per
station. Across the 36 completed JVM instances, all 144 measured windows
passed the configured quality checks and verified 648 transactions.
The runner retained failed attempts and retried CPU counter sampling
failures. Completed results were carried forward from checkpoints.

| Server-thread CPU change | Built-in recipes | Heavy recipes |
| --- | ---: | ---: |
| Smithing repeated off | −13.8% (−51.7%, +41.1%) | +6.4% (−17.7%, +56.0%) |
| Smithing indexed | −1.7% (−64.7%, +152.7%) | −49.5% (−71.3%, +1.2%) |
| Stonecutting repeated off | −19.2% (−29.9%, +11.9%) | +7.6% (−20.4%, +31.8%) |
| Stonecutting indexed | −25.2% (−36.0%, +45.5%) | −23.1% (−38.1%, +30.4%) |

Figures are paired median changes versus off, with exact intervals at
96.875% coverage. Heavy smithing had the largest median savings. Each
candidate interval includes a slowdown, and the repeated controls expose
substantial variation. Keep batching and both indexes opt-in. Raw samples,
logs, and paired summaries are under
`results/station-packet-study.34wZbRVi/stock-short/` and
`results/station-packet-study.34wZbRVi/heavy-short/`.
