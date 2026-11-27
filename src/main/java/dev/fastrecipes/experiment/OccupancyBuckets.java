package dev.fastrecipes.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class OccupancyBuckets<T> {
    private final List<List<T>> buckets = new ArrayList<>();
    private final List<T> recipes;

    public OccupancyBuckets(List<T> recipes, Function<T, CraftingRecipe> recipeValue) {
        this.recipes = List.copyOf(recipes);
        for (int count = 0; count <= 9; count++) {
            buckets.add(new ArrayList<>());
        }
        for (T recipe : this.recipes) {
            int count = requiredSlots(recipeValue.apply(recipe));
            if (count >= 1 && count <= 9) {
                buckets.get(count).add(recipe);
            } else {
                // Preserve the relative positions of recipes we cannot classify.
                for (var bucket : buckets) {
                    bucket.add(recipe);
                }
            }
        }
    }

    private static int requiredSlots(CraftingRecipe recipe) {
        if (recipe.getClass() == ShapedRecipe.class) {
            return (int) ((ShapedRecipe) recipe).getIngredients().stream().filter(slot -> slot.isPresent()).count();
        }
        if (recipe.getClass() == ShapelessRecipe.class && !recipe.placementInfo().isImpossibleToPlace()) {
            return recipe.placementInfo().ingredients().size();
        }
        return -1;
    }

    public List<T> candidates(CraftingInput input) {
        int count = input.ingredientCount();
        return count < buckets.size() ? buckets.get(count) : recipes;
    }
}
