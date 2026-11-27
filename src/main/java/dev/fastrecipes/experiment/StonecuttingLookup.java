package dev.fastrecipes.experiment;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry;
import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

public final class StonecuttingLookup {
    private final OrderedIngredientLookup<SingleInputEntry<StonecutterRecipe>> index;

    public StonecuttingLookup(SingleInputSet<StonecutterRecipe> catalog, Predicate<Ingredient> vanillaIngredient) {
        index = new OrderedIngredientLookup<>(catalog.entries(), 1, entry ->
                vanillaIngredient.test(entry.input())
                        ? List.of(new OrderedIngredientLookup.Requirement(0, entry.input())) : List.of());
    }

    public SingleInputSet<StonecutterRecipe> select(ItemStack input) {
        return new SingleInputSet<>(index.all(new SingleRecipeInput(input), entry -> entry.input().test(input)));
    }

    public boolean accepts(ItemStack input) {
        return index.first(new SingleRecipeInput(input), entry -> entry.input().test(input)) != null;
    }
}
