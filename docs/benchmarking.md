# Benchmarking

[Benchmark instructions](../benchmarks/README.md) cover the runnable isolated
lookup fixture and packaged-server smoke test. [The baseline
report](research/R-004-baseline.md) records the current measurements and limits.

For new dedicated-server measurements use `mc-lab` and `mc-testkit` from
sibling `mc-tooling`. Counterbalance runs on the same host with identical
world inputs, enforce correctness before reading timing, and record CPU,
elapsed time, allocations, workload completion, and reload memory. Keep raw
samples and process diagnostics in ignored `results/` directories. Preserve
completed run directories if a campaign is resumed.

## Release study

The release study compares the off control, repeated off control, three
cached lookup modes and adaptive lookup with batching. The built-in and
10,000-recipe same-slot packs use the same four-slot shift-click workload.
The dense-pivot workload alternates invalid four-slot grids and checks every
result packet. Batching applies to result takes, so the dense comparison
has no batching arm.

```sh
MC_LAB_CPUS=4 scripts/run-release-study.py results/release-study-example \
    --install run/smoke-install \
    --packets run/smoke-install/reports/reports/packets.json \
    --mod build/libs/fast-recipes-0.1.0-alpha.1.jar --accept-eula
```

Each instance contributes the median of its four timed windows after three
warmups. Built-in and same-slot packs each get six ordered blocks. Within
each block, the study compares each mode to off. The batching comparison
uses adaptive without batching as its control. The second off instance
estimates background variation. The dense workload gets two screening blocks.
The CPU improvement threshold is 5%, evaluated using the paired median
interval. A release candidate also needs ordinary-workload non-regression
and correctness evidence. All windows and host-quality flags are retained.

Reuse the same output directory and inputs to resume. Each completed arm
has its own atomic checkpoint. A failed attempt keeps its log, exits
nonzero and can be retried from the latest checkpoint.

Separate dense-pack diagnostic instances run full-GC class histograms after
warmup and again after a datapack reload and subsequent lookup. Heap totals
include the entire server. The hash-tree node histogram gives a lower bound
for tree storage because its arrays and maps use shared JVM and library
types. JVM diagnostic first-use timings cover lookup construction and,
for adaptive lookup, the separate lazy tree build. They exclude resource
reload duration and appear in the retained setup log.

The `--mixed` follow-up uses the same pairing on built-in and same-slot
packs, with off, repeated off, and adaptive lookup. A window verifies eight
ordinary log-to-plank pickups and four shift-clicked crafting tables.
CPU windows include inventory edits, the click fence, and the output checks
on the server thread. The dense diagnostics remain separate and record
lookup setup, postings setup, and each adaptive tree step independently.

Use `scripts/run-release-study.py --batching` for dedicated 3×3 shift-click
batching. Each block compares adaptive lookup, a repeated adaptive
control, and adaptive lookup with batching. The harness verifies four
glass-bottle remainders and saved crafting counts in each server instance.
Pass one `--extra-mod` per JAR to reproduce a Fabric API or Recrafted
stack. Those JAR hashes are part of the saved plan.

Dedicated station timing uses `benchmarks/station_parity.py --timings` to
measure the menu packets that trigger smithing and stonecutting previews.
Each window sums three repetitions of the relevant menu cases. Inventory
setup, result takes, and item verification occur outside the measured
intervals. `scripts/run-station-study.py` runs counterbalanced off,
repeated-off, and indexed arms across fresh JVMs and records each
completed instance. The heavy fixture adds 5,000 recipes per station.
If a short preview finishes before the OS updates the CPU counter, the
driver retains the failed attempt and retries that instance from scratch.
