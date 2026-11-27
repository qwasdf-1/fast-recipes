# R-004 Isolated crafting lookup

The fixture loads the built-in Minecraft 26.3 server pack with `WorldLoader`,
then measures five synthetic recipe populations from 1,000 to 50,000 crafting
recipes. It compares vanilla's `RecipeMap.getRecipesFor(...).findFirst()`, a
sequential loop over the same ordered entries, and an occupied-slot bucket
lookup using the same loop. All arms call Minecraft's exact predicates.

The Java 25 process uses a 512 MiB fixed heap. Each workload has six warmup
batches and 12 measured batches of 512 lookups per arm. The harness cycles
through all six arm orders. It checks selected identity against vanilla
before timing and retains batch wall time, current-thread CPU, allocation,
and hit counts. Reproduce with `scripts/run-lookup-benchmark.sh`.

The synthetic recipe populations deliberately vary ingredient count. The
measurements exclude inventory and packet handling, result assembly, recipe
reload, retained index memory, and mod interactions. They do not establish a
dedicated-server improvement. Compare a representative crafting workload on
Fabric and Recipe Essentials with identical datapacks and observed outputs
before deciding whether to ship an implementation.

## Recorded run

Local run `results/lookup.uPjEgbuc/` recorded 864 samples on Ubuntu 26.04,
OpenJDK 25.0.5.1, and an Intel Core i7-10700. The values below are medians of
12 per-batch CPU-time measurements, in microseconds per lookup. All arms
returned the same hit counts in every measured batch.

| Dataset and workload | Vanilla stream | Ordered loop | Occupied-slot buckets |
| --- | ---: | ---: | ---: |
| Built-in server pack, 1,266 recipes, oak-log hit | 26.13 | 8.72 | 1.97 |
| Built-in server pack, 1,266 recipes, wooden-hoe hit | 51.60 | 19.40 | 4.90 |
| Built-in server pack, 1,266 recipes, bedrock miss | 55.93 | 18.94 | 3.20 |
| 10,000 synthetic recipes, first-match hit | 0.22 | 0.05 | 0.05 |
| 10,000 synthetic recipes, late mirrored hit | 456.62 | 268.18 | 130.99 |
| 10,000 synthetic recipes, miss | 318.60 | 159.60 | 21.07 |
| 50,000 synthetic recipes, mixed | 2,367.30 | 1,078.34 | 290.18 |

For the 10,000-recipe late hit, the loop and bucket arms both allocated
257,864 bytes per lookup in this deliberately repetitive shapeless fixture.
This points to expensive exact comparisons within the selected bucket.
For the corresponding miss, median allocation was 224 bytes per lookup for
the stream and zero for both loop arms.

The ordered-loop control accounts for part of the observed reduction.
The bucket also reduces CPU time against that control on several sampled
inputs. The smoke-test packet report was generated on the same host during
the early part of this run, and all batches share one JVM. Host isolation,
repetition across JVMs, and production impact remain unverified.

A second isolated run, `results/lookup.eU6JMBGR/`, completed after the local
server jobs stopped. In the built-in bedrock miss case it measured 33.80,
13.89 and 2.40 microseconds per lookup in stream, loop and bucket order.
For the 10,000-recipe mixed case it measured 220.53, 141.52 and 52.22
microseconds per lookup. The direction held in a separate JVM, with
material variation in the magnitude.

## Dedicated-server baseline

The release workloads selected for Fast Recipes are the built-in recipe data
pack and a deterministic heavy data pack. The initial dedicated-server
fixture exercises 64 crafts per batch through a protocol-777 client's 2×2
grid and shift-clicks the result. All four runs verified 768 measured crafts
each. Fresh Fabric instances were run in vanilla/heavy/heavy/vanilla order.

| Run | Dataset | Median server-thread CPU per 64 crafts and fixture commands | Host-quality passes |
| --- | --- | ---: | ---: |
| `vanilla-164659779d` | Built-in | 32.08 ms | 11/12 |
| `heavy-3f9f3f7f88` | Heavy | 135.43 ms | 12/12 |
| `heavy-f8e152c38b` | Heavy | 117.65 ms | 12/12 |
| `vanilla-a499416b96` | Built-in | 40.93 ms | 10/12 |

Report directories under `results/` use the `recipe-baseline-` prefix. The
table retains all windows. A separate heavy JFR diagnostic verified 2,048
crafts. Recipe lookup frames occurred in 247 of 350 server-thread
execution samples. This supports trying the lookup experiment for sustained
shift-click traffic with this pack. The profile does not estimate the share
on an ordinary populated server or isolate all costs of an individual craft.
