package dev.fastrecipes.experiment;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

public final class CraftingLookup<T> {
    private static final boolean MEASURE_SETUP = Boolean.getBoolean("fastrecipes.measureSetup");
    private static final int TREE_RECIPES_PER_STEP = 512;
    private static final long TREE_STEP_BUDGET_NS = 1_000_000;
    public static final int ADAPTIVE_CUTOFF = 256;
    public enum Mode {
        OFF, LOOP, OCCUPANCY, INGREDIENT, COMBINED, CACHE, COMBINED_CACHE, HASH_TREE, HASH_TREE_CACHE, ADAPTIVE_CACHE;

        public static Mode parse(String name) {
            for (Mode mode : values()) {
                if (mode.id().equals(name)) {
                    return mode;
                }
            }
            return OFF;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        private boolean isCombined() {
            return this == COMBINED || this == COMBINED_CACHE || this == ADAPTIVE_CACHE;
        }

        private boolean usesTree() {
            return this == HASH_TREE || this == HASH_TREE_CACHE;
        }

        private boolean usesCache() {
            return this == CACHE || this == COMBINED_CACHE || this == HASH_TREE_CACHE || this == ADAPTIVE_CACHE;
        }
    }

    private final Mode mode;
    private final List<T> ordered;
    private final Function<T, CraftingRecipe> recipeValue;
    private final Predicate<CraftingRecipe> indexable;
    private final OccupancyBuckets<T> occupancy;
    private final IngredientIndex<T> ingredients;
    private final IngredientHashTree<T> tree;
    private final RepeatedInputCache<T> cache;
    // Instances are owned by the calling thread. Each query keeps its own merge positions.
    private final List<IngredientIndex<T>> combined;
    private final Map<Integer, IngredientHashTree<T>> adaptiveTrees;
    private final Map<Integer, IngredientHashTree.Builder<T>> pendingTrees = new HashMap<>();
    private final int adaptiveCutoff;

    public CraftingLookup(Mode mode, List<T> recipes, Function<T, CraftingRecipe> recipeValue) {
        this(mode, recipes, recipeValue, ADAPTIVE_CUTOFF);
    }

    public CraftingLookup(Mode mode, List<T> recipes, Function<T, CraftingRecipe> recipeValue, int adaptiveCutoff) {
        this(mode, recipes, recipeValue, adaptiveCutoff, recipe -> true);
    }

    public CraftingLookup(Mode mode, List<T> recipes, Function<T, CraftingRecipe> recipeValue,
                          int adaptiveCutoff, Predicate<CraftingRecipe> vanillaIngredients) {
        this(mode, recipes, recipeValue, adaptiveCutoff, vanillaIngredients, 1);
    }

    public CraftingLookup(Mode mode, List<T> recipes, Function<T, CraftingRecipe> recipeValue,
                          int adaptiveCutoff, Predicate<CraftingRecipe> vanillaIngredients, int capacity) {
        if (adaptiveCutoff < 1) {
            throw new IllegalArgumentException("Adaptive cutoff must be positive");
        }
        if (capacity < 1 || capacity > 16) {
            throw new IllegalArgumentException("Crafting cache capacity must be between 1 and 16");
        }
        this.adaptiveCutoff = adaptiveCutoff;
        adaptiveTrees = mode == Mode.ADAPTIVE_CACHE ? new HashMap<>() : null;
        this.mode = mode;
        this.ordered = List.copyOf(recipes);
        this.recipeValue = recipeValue;
        indexable = recipe -> hasVanillaPredicate(recipe) && vanillaIngredients.test(recipe);
        occupancy = mode == Mode.OCCUPANCY || mode.isCombined() ? new OccupancyBuckets<>(ordered, recipeValue) : null;
        ingredients = mode == Mode.INGREDIENT ? new IngredientIndex<>(ordered, recipeValue, indexable) : null;
        tree = mode.usesTree() ? new IngredientHashTree<>(ordered, recipeValue, indexable) : null;
        cache = mode.usesCache() ? new RepeatedInputCache<>(ordered, recipeValue, indexable, capacity) : null;
        combined = new ArrayList<>();
        if (mode.isCombined()) {
            for (int count = 0; count <= 9; count++) {
                combined.add(null);
            }
        }
    }

    static boolean hasVanillaPredicate(CraftingRecipe recipe) {
        return recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class;
    }

    public T find(CraftingInput input, Level level) {
        if (input.isEmpty()) {
            return null;
        }
        if (cache != null) {
            return cache.find(input, level, () -> findUncached(input, level));
        }
        return findUncached(input, level);
    }

    private T findUncached(CraftingInput input, Level level) {
        Predicate<T> matches = entry -> recipeValue.apply(entry).matches(input, level);
        return switch (mode) {
            case HASH_TREE, HASH_TREE_CACHE -> tree.find(input, matches);
            case INGREDIENT -> ingredients.find(input, matches);
            case COMBINED, COMBINED_CACHE, ADAPTIVE_CACHE -> findCombined(input, matches);
            case OCCUPANCY -> scan(occupancy.candidates(input), matches);
            case OFF, LOOP, CACHE -> scan(ordered, matches);
        };
    }

    private T findCombined(CraftingInput input, Predicate<T> matches) {
        int count = input.ingredientCount();
        if (count >= combined.size()) {
            return scan(ordered, matches);
        }
        IngredientIndex<T> index = combined.get(count);
        if (index == null) {
            long start = MEASURE_SETUP ? System.nanoTime() : 0;
            List<T> candidates = occupancy.candidates(input);
            index = new IngredientIndex<>(candidates, recipeValue, indexable);
            combined.set(count, index);
            if (MEASURE_SETUP) {
                LogUtils.getLogger().info("Fast Recipes setup ingredient postings slots={} recipes={} wall_ns={}",
                        count, candidates.size(), System.nanoTime() - start);
            }
        }
        if (adaptiveTrees == null) {
            return index.find(input, matches);
        }
        return index.find(input, matches, adaptiveCutoff, () -> advanceAdaptiveTree(input),
                firstOrdinal -> adaptiveTrees.get(count).find(input, matches, firstOrdinal));
    }

    private boolean advanceAdaptiveTree(CraftingInput input) {
        int count = input.ingredientCount();
        if (adaptiveTrees.containsKey(count)) {
            return true;
        }
        long start = MEASURE_SETUP ? System.nanoTime() : 0;
        List<T> candidates = occupancy.candidates(input);
        var builder = pendingTrees.computeIfAbsent(count,
                ignored -> new IngredientHashTree.Builder<>(candidates, recipeValue, indexable));
        var tree = builder.advance(TREE_RECIPES_PER_STEP, TREE_STEP_BUDGET_NS);
        if (tree != null) {
            pendingTrees.remove(count);
            adaptiveTrees.put(count, tree);
        }
        if (MEASURE_SETUP) {
            LogUtils.getLogger().info("Fast Recipes setup adaptive tree step slots={} recipes={} ready={} wall_ns={}",
                    count, candidates.size(), tree != null, System.nanoTime() - start);
        }
        return tree != null;
    }

    static <T> T scan(List<T> recipes, Predicate<T> matches) {
        return scan(recipes, matches, 0);
    }

    static <T> T scan(List<T> recipes, Predicate<T> matches, int firstOrdinal) {
        for (int index = firstOrdinal; index < recipes.size(); index++) {
            T entry = recipes.get(index);
            if (matches.test(entry)) {
                return entry;
            }
        }
        return null;
    }
}
