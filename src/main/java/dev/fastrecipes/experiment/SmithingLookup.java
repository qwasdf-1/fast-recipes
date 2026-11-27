package dev.fastrecipes.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.level.Level;

public final class SmithingLookup {
    private final OrderedIngredientLookup<RecipeHolder<SmithingRecipe>> index;

    public SmithingLookup(List<RecipeHolder<SmithingRecipe>> ordered, Predicate<Ingredient> vanillaIngredient) {
        index = new OrderedIngredientLookup<>(ordered, 3, holder -> requirements(holder.value(), vanillaIngredient));
    }

    private static List<OrderedIngredientLookup.Requirement> requirements(SmithingRecipe recipe,
                                                                           Predicate<Ingredient> vanillaIngredient) {
        if (recipe.getClass() != SmithingTransformRecipe.class && recipe.getClass() != SmithingTrimRecipe.class) {
            return List.of();
        }
        var requirements = new ArrayList<OrderedIngredientLookup.Requirement>(3);
        recipe.templateIngredient().ifPresent(ingredient ->
                requirements.add(new OrderedIngredientLookup.Requirement(0, ingredient)));
        requirements.add(new OrderedIngredientLookup.Requirement(1, recipe.baseIngredient()));
        recipe.additionIngredient().ifPresent(ingredient ->
                requirements.add(new OrderedIngredientLookup.Requirement(2, ingredient)));
        return requirements.stream().allMatch(r -> vanillaIngredient.test(r.ingredient())) ? requirements : List.of();
    }

    public RecipeHolder<SmithingRecipe> find(SmithingRecipeInput input, Level level) {
        return input.isEmpty() ? null : index.first(input, holder -> holder.value().matches(input, level));
    }
}
