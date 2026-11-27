package dev.fastrecipes.experiment.mixin;

import dev.fastrecipes.FastRecipesConfig;
import com.mojang.logging.LogUtils;
import dev.fastrecipes.experiment.CraftingLookup;
import dev.fastrecipes.experiment.CraftingLookup.Mode;
import dev.fastrecipes.experiment.FabricRecipePredicates;
import dev.fastrecipes.experiment.SmithingLookup;
import dev.fastrecipes.experiment.StonecuttingIndexAccess;
import dev.fastrecipes.experiment.StonecuttingLookup;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeManager.class)
abstract class RecipeManagerMixin implements StonecuttingIndexAccess {
    @Shadow
    @Final
    private RecipeMap recipes;

    @Unique
    private static final String fastrecipes$modeName = FastRecipesConfig.current().lookup().id();
    @Unique
    private static final Mode fastrecipes$mode = FastRecipesConfig.current().lookup();
    @Unique
    private static final int fastrecipes$cacheEntries = FastRecipesConfig.current().craftingCacheEntries();
    @Unique
    private static final boolean fastrecipes$fabricRecipeApi = FabricLoader.getInstance().isModLoaded("fabric-recipe-api-v1");
    @Unique
    private static final boolean fastrecipes$measureSetup = Boolean.getBoolean("fastrecipes.measureSetup");
    @Unique
    private static final boolean fastrecipes$smithingEnabled = FastRecipesConfig.current().smithingIndex();
    @Unique
    private CraftingLookup<RecipeHolder<CraftingRecipe>> fastrecipes$lookup;
    @Unique
    private SmithingLookup fastrecipes$smithing;

    @Unique
    private StonecuttingLookup fastrecipes$stonecutting;
    @Unique
    private SingleInputSet<StonecutterRecipe> fastrecipes$stonecuttingCatalog;

    @Override
    public StonecuttingLookup fastrecipes$stonecuttingIndex(SingleInputSet<StonecutterRecipe> catalog) {
        if (fastrecipes$stonecuttingCatalog != catalog) {
            fastrecipes$stonecutting = new StonecuttingLookup(catalog,
                    fastrecipes$fabricRecipeApi ? FabricRecipePredicates::isVanillaIngredient : ingredient -> true);
            fastrecipes$stonecuttingCatalog = catalog;
            LogUtils.getLogger().info("Fast Recipes stonecutting index experiment initialized");
        }
        return fastrecipes$stonecutting;
    }

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;"
            + "Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private <I extends RecipeInput, T extends Recipe<I>> void fastrecipes$findFirst(
            RecipeType<T> type, I input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<T>>> callback) {
        boolean smithing = fastrecipes$smithingEnabled && type == RecipeType.SMITHING
                && input instanceof SmithingRecipeInput;
        if (!smithing && (fastrecipes$mode == Mode.OFF
                || type != RecipeType.CRAFTING || input.getClass() != CraftingInput.class)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()
                || ((Object) this).getClass() != RecipeManager.class) {
            return;
        }
        if (smithing) {
            if (fastrecipes$smithing == null) {
                fastrecipes$smithing = new SmithingLookup(List.copyOf(recipes.byType(RecipeType.SMITHING)),
                        fastrecipes$fabricRecipeApi ? FabricRecipePredicates::isVanillaIngredient : ingredient -> true);
                LogUtils.getLogger().info("Fast Recipes smithing index experiment initialized");
            }
            var result = fastrecipes$smithing.find((SmithingRecipeInput) input, level);
            callback.setReturnValue(Optional.ofNullable((RecipeHolder<T>) (RecipeHolder<?>) result));
            return;
        }

        CraftingInput craftingInput = (CraftingInput) input;
        if (craftingInput.isEmpty()) {
            callback.setReturnValue(Optional.empty());
            return;
        }
        if (fastrecipes$lookup == null) {
            long start = fastrecipes$measureSetup ? System.nanoTime() : 0;
            var ordered = List.copyOf(recipes.byType(RecipeType.CRAFTING));
            Predicate<CraftingRecipe> vanillaIngredients = fastrecipes$fabricRecipeApi
                    ? FabricRecipePredicates::hasOnlyVanillaIngredients : recipe -> true;
            fastrecipes$lookup = new CraftingLookup<>(fastrecipes$mode, ordered, RecipeHolder::value,
                    CraftingLookup.ADAPTIVE_CUTOFF, vanillaIngredients, fastrecipes$cacheEntries);
            if (fastrecipes$measureSetup) {
                LogUtils.getLogger().info("Fast Recipes setup lookup mode={} recipes={} wall_ns={}",
                        fastrecipes$modeName, ordered.size(), System.nanoTime() - start);
            }
            LogUtils.getLogger().info("Fast Recipes recipe experiment initialized: {} ({} crafting recipes)",
                    fastrecipes$modeName, ordered.size());
        }
        RecipeHolder<CraftingRecipe> holder = fastrecipes$lookup.find(craftingInput, level);
        callback.setReturnValue(Optional.ofNullable((RecipeHolder<T>) (RecipeHolder<?>) holder));
    }
}
