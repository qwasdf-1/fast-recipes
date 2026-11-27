package dev.fastrecipes.experiment.mixin;

import dev.fastrecipes.FastRecipesConfig;
import com.mojang.logging.LogUtils;
import dev.fastrecipes.experiment.CookingLookupCache;
import dev.fastrecipes.experiment.FabricRecipePredicates;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeManager.class)
abstract class CookingCacheMixin {
    @Unique
    private static final String fastrecipes$cookingLookup = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;"
            + "Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;";
    @Unique
    private static final boolean fastrecipes$missesEnabled = FastRecipesConfig.current().furnaceMisses();
    @Unique
    private static final boolean fastrecipes$hitsEnabled = FastRecipesConfig.current().furnaceHits();
    @Unique
    private static final boolean fastrecipes$fabricIngredients =
            FabricLoader.getInstance().isModLoaded("fabric-recipe-api-v1");

    @Shadow
    @Final
    private RecipeMap recipes;

    @Unique
    private Map<RecipeType<?>, CookingLookupCache<RecipeHolder<?>>> fastrecipes$caches;
    @Unique
    private boolean fastrecipes$loggedMissHit;
    @Unique
    private boolean fastrecipes$loggedPositiveHit;

    // The two vanilla hinted overloads return a matching hint before they call this method.
    @Inject(method = fastrecipes$cookingLookup, at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private <I extends RecipeInput, T extends Recipe<I>> void fastrecipes$useCachedResult(
            RecipeType<T> type, I input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<T>>> callback) {
        var cache = fastrecipes$getCache(type, input, level);
        if (cache == null) {
            return;
        }
        var result = cache.get(((SingleRecipeInput) input).item().typeHolder());
        if (result == null) {
            return;
        }
        if (result.isEmpty() && !fastrecipes$loggedMissHit) {
            LogUtils.getLogger().info("Fast Recipes furnace miss experiment reused a negative result for {}", type);
            fastrecipes$loggedMissHit = true;
        } else if (result.isPresent() && !fastrecipes$loggedPositiveHit) {
            LogUtils.getLogger().info("Fast Recipes furnace hit experiment reused a successful result for {}", type);
            fastrecipes$loggedPositiveHit = true;
        }
        callback.setReturnValue((Optional<RecipeHolder<T>>) (Optional<?>) result);
    }

    @Inject(method = fastrecipes$cookingLookup, at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private <I extends RecipeInput, T extends Recipe<I>> void fastrecipes$recordResult(
            RecipeType<T> type, I input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<T>>> callback) {
        var result = callback.getReturnValue();
        if (result.isEmpty() ? !fastrecipes$missesEnabled : !fastrecipes$hitsEnabled) {
            return;
        }
        var cache = fastrecipes$getCache(type, input, level);
        if (cache != null) {
            cache.put(((SingleRecipeInput) input).item().typeHolder(), (Optional<RecipeHolder<?>>) (Optional<?>) result);
        }
    }

    @Unique
    private <I extends RecipeInput, T extends Recipe<I>> CookingLookupCache<RecipeHolder<?>> fastrecipes$getCache(
            RecipeType<T> type, I input, Level level) {
        if ((!fastrecipes$hitsEnabled && !fastrecipes$missesEnabled)
                || !(input instanceof SingleRecipeInput single) || single.isEmpty()
                || !(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()
                || ((Object) this).getClass() != RecipeManager.class) {
            return null;
        }
        Class<?> vanilla = fastrecipes$cookingClass(type);
        if (vanilla == null) {
            return null;
        }
        if (fastrecipes$caches == null) {
            fastrecipes$caches = new IdentityHashMap<>();
        }
        if (!fastrecipes$caches.containsKey(type)) {
            boolean vanillaOnly = recipes.byType(type).stream().allMatch(holder -> holder.value().getClass() == vanilla
                    && (!fastrecipes$fabricIngredients
                        || FabricRecipePredicates.isVanillaIngredient(((SingleItemRecipe) holder.value()).input())));
            fastrecipes$caches.put(type, vanillaOnly
                    ? new CookingLookupCache<>(fastrecipes$hitsEnabled, fastrecipes$missesEnabled) : null);
        }
        return fastrecipes$caches.get(type);
    }

    @Unique
    private static Class<?> fastrecipes$cookingClass(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) {
            return SmeltingRecipe.class;
        }
        if (type == RecipeType.BLASTING) {
            return BlastingRecipe.class;
        }
        if (type == RecipeType.SMOKING) {
            return SmokingRecipe.class;
        }
        return null;
    }
}
