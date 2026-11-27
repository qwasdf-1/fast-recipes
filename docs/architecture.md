# Architecture

The alpha JAR contains the runtime mixins and shared lookup code from
`src/main`. The Fabric metadata pins Minecraft 26.3 and the build uses
Loom's server-only Minecraft JAR. Runtime optimizations default to off.

The isolated fixtures in `benchmarks/java` exercise the same compiled
lookup code and stay outside the alpha JAR. Dedicated checks under
`scripts/` and `benchmarks/` use sibling mc-testkit and mc-lab.

The experiment can use Fabric Recipe API's ingredient inspection methods
when that module is installed. The build dependency is compile-only.
Recrafted custom ingredient predicates and custom recipe classes follow the
ordered fallback in the lookup modes.

The experiment JAR declares a loader conflict with FastSuite and Recipe
Essentials because they overlap recipe lookup. Crafting, smithing, and
stonecutting have independent hooks and can share a server. Positive and
negative furnace results have separate bounds and share the unhinted
lookup path. The vanilla successful-recipe hint takes precedence.

Crafting batching is scoped to a single result take. On supported
recipe sets it combines intermediate crafting grid updates. Mixed
recipe sets run the intermediate callbacks, including result assembly
and state-ID updates, and coalesce the result-slot packets for the
active menu and connection only.

Any future lookup hook must preserve the original recipe order and hinted
selection, then run exact matches in the correct level against the active
recipe snapshot. Inventory changes, output assembly, remainders, redstone
timing, crafting awards and synchronization remain part of vanilla's
transaction. See [vanilla execution paths](research/R-002-vanilla-paths.md)
for the current source trace.
