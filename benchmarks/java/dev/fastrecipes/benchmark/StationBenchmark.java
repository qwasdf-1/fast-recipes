package dev.fastrecipes.benchmark;

import com.sun.management.ThreadMXBean;
import dev.fastrecipes.experiment.CookingLookupCache;
import dev.fastrecipes.experiment.SmithingLookup;
import dev.fastrecipes.experiment.StonecuttingLookup;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.resources.ResourceKey;

public final class StationBenchmark {
    private static final int LOOKUPS = 4096;
    private static volatile int checksum;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var manager = LookupBenchmark.loadVanillaManager();
        manager.finalizeRecipeLoading(FeatureFlags.DEFAULT_FLAGS);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadCpuTimeEnabled(true);
        smithing(manager, bean);
        stonecutting(manager, bean);
        furnace(manager, bean);
    }

    private static void smithing(RecipeManager manager, ThreadMXBean bean) {
        var recipes = manager.getRecipes().stream().filter(holder -> holder.value() instanceof SmithingRecipe)
                .map(holder -> {
                    @SuppressWarnings("unchecked")
                    var typed = (net.minecraft.world.item.crafting.RecipeHolder<SmithingRecipe>)
                            (net.minecraft.world.item.crafting.RecipeHolder<?>) holder;
                    return typed;
                }).toList();
        var index = new SmithingLookup(recipes, ingredient -> true);
        var inputs = List.of(
                new SmithingRecipeInput(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE.getDefaultInstance(),
                        Items.DIAMOND_SWORD.getDefaultInstance(), Items.NETHERITE_INGOT.getDefaultInstance()),
                new SmithingRecipeInput(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE.getDefaultInstance(),
                        Items.IRON_CHESTPLATE.getDefaultInstance(), Items.DIAMOND.getDefaultInstance()),
                new SmithingRecipeInput(ItemStack.EMPTY, Items.DIRT.getDefaultInstance(), ItemStack.EMPTY));
        for (var input : inputs) {
            if (index.find(input, null) != manager.getRecipeFor(RecipeType.SMITHING, input, null).orElse(null)) {
                throw new IllegalStateException("Smithing index changed recipe identity");
            }
        }
        measure("smithing", bean, cycle(inputs, input -> manager.getRecipeFor(RecipeType.SMITHING, input, null).isPresent()),
                cycle(inputs, input -> index.find(input, null) != null));
    }

    private static void stonecutting(RecipeManager manager, ThreadMXBean bean) {
        var catalog = manager.stonecutterRecipes();
        var index = new StonecuttingLookup(catalog, ingredient -> true);
        var inputs = List.of(Items.STONE.getDefaultInstance(), Items.QUARTZ_BLOCK.getDefaultInstance(),
                Items.TUFF.getDefaultInstance(), Items.DIRT.getDefaultInstance(), ItemStack.EMPTY);
        for (var input : inputs) {
            if (!index.select(input).equals(catalog.selectByInput(input))
                    || index.accepts(input) != catalog.acceptsInput(input)) {
                throw new IllegalStateException("Stonecutting index changed option identity or order");
            }
        }
        measure("stonecutting_select", bean, cycle(inputs, input -> !catalog.selectByInput(input).isEmpty()),
                cycle(inputs, input -> !index.select(input).isEmpty()));
        measure("stonecutting_accept", bean, cycle(inputs, catalog::acceptsInput), cycle(inputs, index::accepts));
    }

    private static void furnace(RecipeManager manager, ThreadMXBean bean) {
        var iron = new SingleRecipeInput(Items.RAW_IRON.getDefaultInstance());
        var copper = new SingleRecipeInput(Items.RAW_COPPER.getDefaultInstance());
        measure("furnace_alternating_vs_repeated", bean,
                hintedCooking(manager, List.of(iron, copper)), hintedCooking(manager, List.of(iron)));
        var inputs = List.of(iron, copper);
        var cache = new CookingLookupCache<RecipeHolder<SmeltingRecipe>>(true, true);
        for (var input : inputs) {
            cache.put(input.item().typeHolder(), manager.getRecipeFor(RecipeType.SMELTING, input, null));
        }
        var miss = new SingleRecipeInput(Items.BEDROCK.getDefaultInstance());
        cache.put(miss.item().typeHolder(), manager.getRecipeFor(RecipeType.SMELTING, miss, null));
        if (cache.get(miss.item().typeHolder()) == null || cache.get(miss.item().typeHolder()).isPresent()) {
            throw new IllegalStateException("A cooking miss was not preserved alongside successful recipes");
        }
        measure("furnace_alternating_positive_cache", bean, hintedCooking(manager, inputs), new IntSupplier() {
            private ResourceKey<Recipe<?>> hint;
            private int next;
            public int getAsInt() {
                var input = inputs.get(next++ % inputs.size());
                var preferred = hint == null ? null : manager.byKey(hint).orElse(null);
                if (preferred == null || !((SmeltingRecipe) preferred.value()).matches(input, null)) {
                    preferred = cache.get(input.item().typeHolder()).orElseThrow();
                }
                hint = preferred.id();
                return 1;
            }
        });
    }

    private static IntSupplier hintedCooking(RecipeManager manager, List<SingleRecipeInput> inputs) {
        return new IntSupplier() {
            private ResourceKey<Recipe<?>> hint;
            private int next;

            public int getAsInt() {
                var input = inputs.get(next++ % inputs.size());
                var result = manager.getRecipeFor(RecipeType.SMELTING, input, null, hint).orElseThrow();
                hint = result.id();
                return 1;
            }
        };
    }

    private static <T> IntSupplier cycle(List<T> inputs, java.util.function.Predicate<T> findsResult) {
        return new IntSupplier() {
            private int next;
            public int getAsInt() {
                return findsResult.test(inputs.get(next++ % inputs.size())) ? 1 : 0;
            }
        };
    }

    private static void measure(String label, ThreadMXBean bean, IntSupplier first, IntSupplier second) {
        var arms = List.of(first, second);
        long[][] samples = new long[2][12];
        for (int batch = -12; batch < 12; batch++) {
            for (int offset = 0; offset < arms.size(); offset++) {
                int arm = Math.floorMod(batch + offset, arms.size());
                int found = 0;
                long start = bean.getCurrentThreadCpuTime();
                for (int call = 0; call < LOOKUPS; call++) {
                    found += arms.get(arm).getAsInt();
                }
                long elapsed = bean.getCurrentThreadCpuTime() - start;
                checksum = found;
                if (batch >= 0) {
                    samples[arm][batch] = elapsed;
                    Bootstrap.realStdoutPrintln(label + ",arm=" + arm + ",batch=" + batch + ",cpu_ns="
                            + elapsed + ",lookups=" + LOOKUPS + ",results=" + found);
                }
            }
        }
        for (int arm = 0; arm < arms.size(); arm++) {
            Arrays.sort(samples[arm]);
            Bootstrap.realStdoutPrintln(label + ",arm=" + arm + ",median_ns_per_lookup="
                    + (samples[arm][5] + samples[arm][6]) / (2.0 * LOOKUPS));
        }
    }
}
