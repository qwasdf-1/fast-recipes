# R-002 Vanilla execution paths

The audit uses the official unobfuscated 26.3 bundled server, inner server JAR
SHA-256 `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`.
Method anchors below refer to that exact build. Inspect them with
`javap -p -c -classpath minecraft-extracted_server.jar <class>` or locally
decompile with Vineflower 1.12.0.

| Path | Observed behavior | Implication |
| --- | --- | --- |
| `RecipeManager.<init>`, `RecipeMap.create`, `RecipeMap.getRecipesFor` | Snapshot the recipe registry by key and type. The nonempty matching stream iterates in type order and evaluates exact `matches(input, level)`. Unhinted lookup takes the first match. `finalizeRecipeLoading` builds property/display data. | Preserve the source collection's order and keep a candidate index owned by each snapshot. |
| `RecipeManager.getRecipeFor`, `RecipeManager.createCheck` | A valid holder hint takes precedence over general matching. The key overload resolves the key in the active map. `CachedCheck` retains the most recent successful key. | Leave both hint paths intact. |
| `CraftingMenu.slotChangedCraftingGrid`, `slotsChanged`, `finishPlacingRecipe`, `InventoryMenu.slotsChanged` | Server-side result recomputation uses unhinted lookup for ordinary edits. The table's recipe placement passes the selected recipe as a hint. Matching, assembly, feature checks, result assignment and slot sync occur in sequence. | First investigate unhinted matching. Retain assembly, synchronization and placement behavior. |
| `ResultSlot.getRemainingItems`, `onTake` | Server-side remainder resolution makes an unhinted lookup of its own. Ingredient removal and replacement can invoke grid change handling. | Any batching must first capture intermediate result, inventory and listener behavior. |
| `AbstractFurnaceBlockEntity.serverTick`, `getTotalCookTime`, `getBurnDuration`, `awardUsedRecipesAndPopExperience` | Empty input skips matching. Nonempty input enters matching while lit or when fuel is available. The furnace uses `quickCheck`, assembles a result, checks output capacity, and updates fuel/progress. Fuel properties and recipe awards have separate paths. | Test repeated invalid-input misses on fueled furnaces before building another cache. |
| `CrafterBlock.dispenseFrom`, `getPotentialResults`, `RecipeCache.get` | A shared ten-entry cache stores hits and misses, compares trimmed dimensions plus item/components, and clears on manager identity change. The crafter assembles and handles remainders and routing during its scheduled pulse. | Repeated identical lookups are already cached. Diverse misses warrant separate profiling. |
| `SmithingMenu.createResult`, `StonecutterMenu.slotsChanged` | Smithing calls typed recipe lookup when inputs are ready. Stonecutting uses the manager's derived option set. | A crafting index has no coverage of either station. |

The audit establishes call paths. It leaves match frequency, hook conflicts,
custom-recipe behavior and the full reload lifecycle for differential runtime
tests.
