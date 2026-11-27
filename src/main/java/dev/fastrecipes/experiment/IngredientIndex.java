package dev.fastrecipes.experiment;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** An ordered candidate filter for vanilla crafting predicates. */
final class IngredientIndex<T> {
    private static final int MAX_PIVOT_ITEMS = 64;
    private static final int EARLY_MATCH_PROBES = 4;
    private static final int[] EMPTY = new int[0];

    private final List<T> recipes;
    private final Map<Item, int[]> postings = new IdentityHashMap<>();
    private final int[] fallback;

    IngredientIndex(List<T> recipes, Function<T, CraftingRecipe> recipeValue,
                       Predicate<CraftingRecipe> indexable) {
        this.recipes = recipes;
        Map<Item, IntArrayList> pending = new IdentityHashMap<>();
        var unclassified = new IntArrayList();
        for (int ordinal = 0; ordinal < recipes.size(); ordinal++) {
            CraftingRecipe recipe = recipeValue.apply(recipes.get(ordinal));
            List<Item> pivot = indexable.test(recipe) ? pivot(recipe) : List.of();
            if (pivot.isEmpty()) {
                unclassified.add(ordinal);
                continue;
            }
            for (Item item : pivot) {
                pending.computeIfAbsent(item, ignored -> new IntArrayList()).add(ordinal);
            }
        }
        pending.forEach((item, ordinals) -> postings.put(item, ordinals.toIntArray()));
        fallback = unclassified.toIntArray();
    }

    private static List<Item> pivot(CraftingRecipe recipe) {
        List<Item> best = List.of();
        int limit = MAX_PIVOT_ITEMS + 1;
        for (var ingredient : recipe.placementInfo().ingredients()) {
            var items = ingredient.items().map(Holder::value).distinct().limit(limit).toList();
            if (!items.isEmpty() && items.size() < limit) {
                best = items;
                limit = best.size();
                if (limit == 1) {
                    break;
                }
            }
        }
        return best;
    }

    T find(CraftingInput input, Predicate<T> matches) {
        return find(input, matches, Integer.MAX_VALUE, null, null);
    }

    T find(CraftingInput input, Predicate<T> matches, int cutoff, BooleanSupplier prepareTree,
           IntFunction<T> denseSearch) {
        if (input.size() == 1) {
            return findSingle(input, matches);
        }
        int[][] lists = new int[input.size() + 1][];
        lists[0] = fallback;
        int count = 1;
        long indexedUpperBound = 0;
        for (int slot = 0; slot < input.size(); slot++) {
            var stack = input.getItem(slot);
            if (!stack.isEmpty()) {
                int[] ordinals = postings.get(stack.getItem());
                if (ordinals != null && (denseSearch == null || !contains(lists, count, ordinals))) {
                    lists[count++] = ordinals;
                    indexedUpperBound += ordinals.length;
                }
            }
        }

        // Merge sorted postings by original recipe position. Advance all equal heads
        // so a recipe appearing under several input items is matched only once.
        int[] positions = new int[count];
        boolean dense = denseSearch != null && input.ingredientCount() > 1
                && Math.min(indexedUpperBound, recipes.size() - fallback.length) >= cutoff;
        int probes = 0;
        while (true) {
            int next = Integer.MAX_VALUE;
            for (int list = 0; list < count; list++) {
                if (positions[list] < lists[list].length) {
                    next = Math.min(next, lists[list][positions[list]]);
                }
            }
            if (next == Integer.MAX_VALUE) {
                return null;
            }
            if (dense && probes++ == EARLY_MATCH_PROBES) {
                // This is the next untested ordinal in this bucket.
                if (prepareTree.getAsBoolean()) {
                    return denseSearch.apply(next);
                }
                dense = false;
            }
            T recipe = recipes.get(next);
            if (matches.test(recipe)) {
                return recipe;
            }
            for (int list = 0; list < count; list++) {
                if (positions[list] < lists[list].length && lists[list][positions[list]] == next) {
                    positions[list]++;
                }
            }
        }
    }

    private static boolean contains(int[][] lists, int count, int[] posting) {
        for (int index = 0; index < count; index++) {
            if (lists[index] == posting) {
                return true;
            }
        }
        return false;
    }

    private T findSingle(CraftingInput input, Predicate<T> matches) {
        int[] indexed = postings.getOrDefault(input.getItem(0).getItem(), EMPTY);
        int itemPosition = 0;
        int fallbackPosition = 0;
        while (itemPosition < indexed.length || fallbackPosition < fallback.length) {
            boolean takeIndexed = fallbackPosition == fallback.length
                    || (itemPosition < indexed.length && indexed[itemPosition] < fallback[fallbackPosition]);
            T recipe = recipes.get(takeIndexed ? indexed[itemPosition++] : fallback[fallbackPosition++]);
            if (matches.test(recipe)) {
                return recipe;
            }
        }
        return null;
    }
}
