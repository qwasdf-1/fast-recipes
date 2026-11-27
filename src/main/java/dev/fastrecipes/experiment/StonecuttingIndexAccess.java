package dev.fastrecipes.experiment;

import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet;
import net.minecraft.world.item.crafting.StonecutterRecipe;

public interface StonecuttingIndexAccess {
    StonecuttingLookup fastrecipes$stonecuttingIndex(SingleInputSet<StonecutterRecipe> catalog);
}
