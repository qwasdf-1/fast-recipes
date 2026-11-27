# Fast Recipes

Fast Recipes is a standalone Fabric server mod for Minecraft Java 26.3. It
offers opt-in recipe lookup, crafting update, and furnace cache optimizations.
The alpha ships with all optimizations off. Players can join with an unmodified
Minecraft 26.3 client.

## Install

Use a dedicated Minecraft 26.3 server with Java 25 and Fabric Loader 0.20.1
or later. Put the Fast Recipes release JAR in the server's `mods` directory.
Starting with `0.1.0-alpha.2`, the server creates
`config/fast-recipes.toml` on first startup. Edit the file and restart the
server. Existing JVM flags take precedence over the corresponding TOML
settings. Put JVM flags before `-jar` in the launch command.

| TOML setting | JVM equivalent | Default and behavior |
| --- | --- | --- |
| `lookup` | `-Dfastrecipes.lookup=adaptive-cache` | `"off"`. Selects a crafting lookup mode. |
| `crafting_cache_entries` | `-Dfastrecipes.craftingCacheEntries=4` | `1`. Stores 1–16 recent grids in a cached lookup mode. |
| `batch_crafting` | `-Dfastrecipes.batchCrafting=true` | `false`. Coalesces shift-click crafting updates or result-slot packets. |
| `smithing_index` | `-Dfastrecipes.smithingIndex=true` | `false`. Indexes eligible smithing recipes. |
| `stonecutting_index` | `-Dfastrecipes.stonecuttingIndex=true` | `false`. Indexes the server stonecutter catalog in recipe order. |
| `furnace_hits` | `-Dfastrecipes.furnaceHits=true` | `false`. Caches eligible successful cooking lookups after vanilla's last-recipe hint. |
| `furnace_misses` | `-Dfastrecipes.furnaceMisses=true` | `false`. Caches eligible failed cooking lookups. |

For example, to enable adaptive crafting and retain four recent grids:

```toml
lookup = "adaptive-cache"
crafting_cache_entries = 4
```

Omitted settings use their defaults. A malformed file or an invalid value
for a recognized setting logs a warning and uses defaults before applying
JVM overrides. Fast Recipes preserves the file for editing.

Other lookup modes are `loop`, `occupancy`, `ingredient`, `combined`, `cache`,
`combined-cache`, `hash-tree`, and `hash-tree-cache`. Choose one lookup mode.
The other switches are independent.

Fabric API is optional. The tested Recrafted aggregate uses Fabric API
0.165.0. Recrafted's custom crafting predicates and intermediate updates are
preserved. Cooking types with custom recipes or ingredients use vanilla
lookup. Lithium 0.26.4 passed the recorded compatibility checks. Fabric
Loader blocks combining this JAR with FastSuite or Recipe Essentials.
[Verification](docs/verification.md)

## Build and verify

Use Java 25 and the checked-in Gradle 9.9.0 wrapper. Run `./gradlew build` to
build the installable JAR and run isolated lookup parity. The
[benchmark instructions](benchmarks/README.md) cover the dedicated test
harnesses. The [research decision](docs/research/R-006-decision.md) records
performance results and the default-enablement gate.

See the [documentation index](docs/README.md) for the architecture and
project reference.
