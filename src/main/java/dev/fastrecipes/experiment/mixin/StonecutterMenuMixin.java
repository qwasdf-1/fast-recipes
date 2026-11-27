package dev.fastrecipes.experiment.mixin;

import dev.fastrecipes.experiment.StonecuttingIndexAccess;
import dev.fastrecipes.experiment.StonecuttingLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StonecutterMenu.class)
abstract class StonecutterMenuMixin {
    @Shadow @Final private Level level;

    @Unique
    private static final boolean fastrecipes$enabled = Boolean.getBoolean("fastrecipes.stonecuttingIndex");

    @Redirect(method = "setupRecipeList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/SelectableRecipe$SingleInputSet;selectByInput("
                    + "Lnet/minecraft/world/item/ItemStack;)"
                    + "Lnet/minecraft/world/item/crafting/SelectableRecipe$SingleInputSet;"))
    private SingleInputSet<StonecutterRecipe> fastrecipes$select(SingleInputSet<StonecutterRecipe> catalog,
                                                               ItemStack input) {
        var index = fastrecipes$index(catalog);
        return index == null ? catalog.selectByInput(input) : index.select(input);
    }

    @Redirect(method = "quickMoveStack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/SelectableRecipe$SingleInputSet;acceptsInput("
                    + "Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean fastrecipes$accepts(SingleInputSet<StonecutterRecipe> catalog, ItemStack input) {
        var index = fastrecipes$index(catalog);
        return index == null ? catalog.acceptsInput(input) : index.accepts(input);
    }

    @Unique
    private StonecuttingLookup fastrecipes$index(SingleInputSet<StonecutterRecipe> catalog) {
        if (!fastrecipes$enabled || !(level instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()) {
            return null;
        }
        var manager = serverLevel.recipeAccess();
        return manager.getClass() == RecipeManager.class && manager.stonecutterRecipes() == catalog
                ? ((StonecuttingIndexAccess) manager).fastrecipes$stonecuttingIndex(catalog) : null;
    }
}
