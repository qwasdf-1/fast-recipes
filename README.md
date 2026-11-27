# Fast Recipes

Fast Recipes is a standalone Fabric server mod for Minecraft Java 26.3. It
offers opt-in recipe lookup, crafting update, and furnace cache optimizations.
The alpha ships with all optimizations off. Players can join with an unmodified
Minecraft 26.3 client.

## Install

Use a dedicated Minecraft 26.3 server with Java 25 and Fabric Loader 0.20.1
or later. Put `fast-recipes-0.1.0-alpha.1.jar` in the server's `mods` directory.
Add the selected `-D` options before `-jar` in the Java launch command.

| JVM option | Behavior |
| --- | --- |
| `-Dfastrecipes.lookup=adaptive-cache` | Use the adaptive crafting lookup. The default is `off`. |
| `-Dfastrecipes.craftingCacheEntries=4` | Keep more recent grids when a cached crafting mode is selected. Default 1, range 1–16. |
| `-Dfastrecipes.batchCrafting=true` | Coalesce shift-click crafting updates or, on mixed recipe sets, result-slot packets. |
| `-Dfastrecipes.smithingIndex=true` | Index eligible smithing recipes. |
| `-Dfastrecipes.stonecuttingIndex=true` | Index the server stonecutter catalog and preserve the order of choices. |
| `-Dfastrecipes.furnaceHits=true` | Cache eligible successful cooking lookups after vanilla's last-recipe hint. |
| `-Dfastrecipes.furnaceMisses=true` | Cache eligible failed cooking lookups. |

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
