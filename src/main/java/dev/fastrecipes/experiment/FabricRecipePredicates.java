package dev.fastrecipes.experiment;

import dev.fastrecipes.experiment.mixin.ShapelessIngredientsAccessor;
import java.util.Optional;
import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

public final class FabricRecipePredicates {
    private FabricRecipePredicates() {}

    public static boolean hasOnlyVanillaIngredients(CraftingRecipe recipe) {
        var ingredients = recipe instanceof ShapedRecipe shaped
                ? shaped.getIngredients().stream().flatMap(Optional::stream)
                : ((ShapelessIngredientsAccessor) recipe).fastrecipes$getIngredients().stream();
        return ingredients.noneMatch(FabricRecipePredicates::isCustom);
    }

    private static boolean isCustom(Ingredient ingredient) {
        return ((FabricIngredient) (Object) ingredient).getCustomIngredient() != null;
    }

    public static boolean isVanillaIngredient(Ingredient ingredient) {
        return !isCustom(ingredient);
    }
}
