package dev.fastrecipes.benchmark;

import com.mojang.serialization.Lifecycle;
import com.sun.management.ThreadMXBean;
import dev.fastrecipes.experiment.CraftingLookup;
import dev.fastrecipes.experiment.CraftingLookup.Mode;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe.CraftingBookInfo;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Recipe.CommonInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;

public final class LookupBenchmark {
    private static final long SEED = 263;
    private static final int[] SIZES = {1_000, 5_000, 10_000, 20_000, 50_000};
    private static final int WARMUP_BATCHES = 10;
    private static final int MEASURED_BATCHES = 10;
    private static final int LOOKUPS_PER_BATCH = 512;
    private static final List<Mode> INDEX_MODES = List.of(Mode.LOOP, Mode.OCCUPANCY, Mode.INGREDIENT, Mode.COMBINED);
    private static final List<Mode> CACHE_MODES = List.of(Mode.LOOP, Mode.COMBINED, Mode.CACHE, Mode.COMBINED_CACHE);

    private static final List<Mode> TREE_MODES = List.of(Mode.COMBINED, Mode.COMBINED_CACHE, Mode.HASH_TREE, Mode.HASH_TREE_CACHE);
    private static volatile int checksum;

    public static void main(String[] args) {
        boolean verifyOnly = args.length == 1 && args[0].equals("--verify-only");
        boolean quick = args.length == 1 && args[0].equals("--quick");
        boolean cacheQuick = args.length == 1 && args[0].equals("--cache-quick");
        boolean treeQuick = args.length == 1 && args[0].equals("--tree-quick");
        boolean adaptive = args.length == 1 && args[0].equals("--adaptive");
        boolean cacheCapacity = args.length == 1 && args[0].equals("--cache-capacity");
        if (args.length != 0 && !verifyOnly && !quick && !cacheQuick && !treeQuick && !adaptive && !cacheCapacity) {
            throw new IllegalArgumentException("Unknown benchmark option");
        }
        List<Mode> modes = verifyOnly ? Arrays.stream(Mode.values()).filter(mode -> mode != Mode.OFF).toList()
                : treeQuick ? TREE_MODES : cacheQuick ? CACHE_MODES : INDEX_MODES;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ThreadMXBean measurements = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        measurements.setThreadCpuTimeEnabled(true);
        measurements.setThreadAllocatedMemoryEnabled(true);
        if (!verifyOnly) {
            Bootstrap.realStdoutPrintln("dataset,size,workload,batch,arm,lookups,wall_ns,cpu_ns,allocated_bytes,checksum");
        }
        if (adaptive) {
            runAdaptiveComparison(measurements);
            return;
        }
        if (cacheCapacity) {
            runCacheCapacityComparison(measurements);
            return;
        }
        run(loadVanillaRecipes(), "vanilla_datapack", input(1, 1, Items.OAK_LOG),
                input(2, 3, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.AIR, Items.STICK, Items.AIR, Items.STICK),
                input(1, 1, Items.BEDROCK), measurements, verifyOnly, modes);
        for (int size : verifyOnly || quick || cacheQuick || treeQuick ? new int[] {1_000} : SIZES) {
            runSynthetic(size, false, measurements, verifyOnly, modes);
        }
        runSynthetic(verifyOnly || cacheQuick || treeQuick ? 1_000 : 10_000, true, measurements, verifyOnly, modes);
        if (verifyOnly) {
            verifyCachePrecedence();
            verifyOverlappingIngredientRequirements();
            verifyAdaptiveSwitch();
            Bootstrap.realStdoutPrintln("Verified lookup parity on built-in, mixed-slot and same-slot recipes");
        }
    }

    private static void runCacheCapacityComparison(ThreadMXBean measurements) {
        var recipes = loadVanillaRecipes();
        var ordered = recipes.byType(RecipeType.CRAFTING).stream().map(RecipeHolder::value).toList();
        var arms = new ArrayList<Arm>();
        for (int capacity : new int[] {1, 4, 16}) {
            var lookup = new CraftingLookup<>(Mode.ADAPTIVE_CACHE, ordered, Function.identity(),
                    CraftingLookup.ADAPTIVE_CUTOFF, recipe -> true, capacity);
            arms.add(new Arm("capacity-" + capacity, input -> lookup.find(input, null)));
        }
        var first = input(1, 1, Items.OAK_LOG);
        var second = input(1, 1, Items.BEDROCK);
        var mixed = List.of(first, second, input(1, 1, Items.BIRCH_LOG), input(1, 1, Items.SPRUCE_LOG),
                input(1, 1, Items.JUNGLE_LOG), input(1, 1, Items.ACACIA_LOG));
        var unique = new ArrayList<CraftingInput>();
        BuiltInRegistries.ITEM.stream().limit(LOOKUPS_PER_BATCH)
                .forEach(item -> unique.add(input(1, 1, item)));
        var workloads = List.of(new Workload("repeated", List.of(first)),
                new Workload("alternating_hit_miss", List.of(first, second)),
                new Workload("six_inputs", mixed), new Workload("unique", unique));
        for (var workload : workloads) {
            for (var input : workload.inputs()) {
                verify(arms, input, recipes.getRecipesFor(RecipeType.CRAFTING, input, null)
                        .findFirst().map(RecipeHolder::value).orElse(null));
            }
        }
        measureWorkloads("vanilla_datapack", ordered.size(), arms, workloads, measurements);
    }

    private static RecipeMap loadVanillaRecipes() {
        return loadVanillaData(provider -> RecipeMap.create(provider.lookupOrThrow(Registries.RECIPE)));
    }

    static RecipeManager loadVanillaManager() {
        return loadVanillaData(RecipeManager::new);
    }

    private static <T> T loadVanillaData(Function<net.minecraft.core.HolderLookup.Provider, T> read) {
        var packConfig = new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(),
                WorldDataConfiguration.DEFAULT, true, false);
        var config = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED,
                PermissionSet.ALL_PERMISSIONS);
        return WorldLoader.load(config,
                context -> new WorldLoader.DataLoadOutput<>(null, context.datapackDimensions()),
                (packs, resources, registries, ignored) -> {
                    try (packs) {
                        resources.updateComponentsAndStaticRegistryTags();
                        return read.apply(resources.fullRegistries().lookup());
                    }
                }, Runnable::run, Runnable::run).join();
    }

    private static void runSynthetic(int size, boolean sameCount, ThreadMXBean measurements,
                                     boolean verifyOnly, List<Mode> modes) {
        var common = new CommonInfo(false);
        var book = new CraftingBookInfo(CraftingBookCategory.MISC, "");
        var result = new ItemStackTemplate(Items.COBBLESTONE);
        var plank = Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS);
        var stick = Ingredient.of(Items.STICK);
        List<CraftingRecipe> recipes = new ArrayList<>();
        int earlyCount = sameCount ? 3 : 1;
        recipes.add(new ShapelessRecipe(common, book, result, Collections.nCopies(earlyCount, stick)));
        for (int index = 1; index < size - 2; index++) {
            Ingredient filler = Ingredient.of(index % 2 == 0 ? Items.DIRT : Items.GRAVEL);
            recipes.add(new ShapelessRecipe(common, book, result,
                    Collections.nCopies(sameCount ? 3 : 1 + index % 9, filler)));
        }
        recipes.add(new ShapedRecipe(common, book,
                ShapedRecipePattern.of(Map.of('P', plank, 'S', stick), "PS", "P "), result));
        // A second match for sticks provides an ordering check.
        recipes.add(new ShapelessRecipe(common, book, result, Collections.nCopies(earlyCount, stick)));

        RecipeMap recipeMap = recipeMap(recipes);
        CraftingInput early = sameCount ? input(3, 1, Items.STICK, Items.STICK, Items.STICK) : input(1, 1, Items.STICK);
        CraftingInput late = input(2, 2, Items.STICK, Items.SPRUCE_PLANKS, Items.AIR, Items.OAK_PLANKS);
        CraftingInput miss = sameCount ? input(3, 1, Items.DIAMOND, Items.DIAMOND, Items.DIAMOND) : input(1, 1, Items.DIAMOND);
        List<Arm> arms = arms(recipeMap, modes);
        verifyCustomFallback(late);
        verify(arms, early, recipes.getFirst());
        verify(arms, late, recipes.get(size - 2));
        verify(arms, miss, null);
        run(recipeMap, sameCount ? "same_slots" : "synthetic", early, late, miss, measurements, verifyOnly, modes);
    }

    private static RecipeMap recipeMap(List<CraftingRecipe> recipes) {
        var registry = new MappedRegistry<Recipe<?>>(Registries.RECIPE, Lifecycle.stable());
        for (int index = 0; index < recipes.size(); index++) {
            var key = ResourceKey.create(Registries.RECIPE,
                    Identifier.fromNamespaceAndPath("fast_recipes_benchmark", "recipe_" + index));
            Registry.register(registry, key, recipes.get(index));
        }
        return RecipeMap.create(registry.freeze());
    }

    private static List<Arm> arms(RecipeMap recipeMap, List<Mode> modes) {
        var ordered = recipeMap.byType(RecipeType.CRAFTING).stream().map(RecipeHolder::value).toList();
        var arms = new ArrayList<Arm>();
        arms.add(new Arm("vanilla", input -> recipeMap.getRecipesFor(RecipeType.CRAFTING, input, null)
                .findFirst().map(RecipeHolder::value).orElse(null)));
        for (Mode mode : modes) {
            var lookup = new CraftingLookup<>(mode, ordered, Function.identity());
            arms.add(new Arm(mode.id(), input -> lookup.find(input, null)));
        }
        return arms;
    }

    private static void run(RecipeMap recipeMap, String dataset, CraftingInput early, CraftingInput late,
                            CraftingInput miss, ThreadMXBean measurements, boolean verifyOnly, List<Mode> modes) {
        var arms = arms(recipeMap, modes);
        var vanilla = arms.getFirst();
        int size = recipeMap.byType(RecipeType.CRAFTING).size();
        verify(arms, CraftingInput.EMPTY, null);
        for (var sample : List.of(early, late, miss)) {
            verify(arms, sample, vanilla.lookup.apply(sample));
        }

        Random random = new Random(SEED);
        for (int trial = 0; trial < 512; trial++) {
            ItemLike[] palette = {Items.AIR, Items.STICK, Items.OAK_PLANKS, Items.SPRUCE_PLANKS,
                    Items.DIRT, Items.GRAVEL, Items.DIAMOND};
            ItemLike[] slots = new ItemLike[9];
            for (int slot = 0; slot < slots.length; slot++) {
                slots[slot] = palette[random.nextInt(palette.length)];
            }
            var sample = input(3, 3, slots);
            verify(arms, sample, vanilla.lookup.apply(sample));
        }

        if (verifyOnly) {
            return;
        }
        var mixed = new ArrayList<CraftingInput>();
        for (int index = 0; index < LOOKUPS_PER_BATCH; index++) {
            mixed.add(switch (random.nextInt(3)) {
                case 0 -> early;
                case 1 -> late;
                default -> miss;
            });
        }
        var sharedIngredient = dataset.equals("same_slots")
                ? input(3, 1, Items.DIRT, Items.DIAMOND, Items.DIAMOND)
                : input(2, 1, Items.DIRT, Items.DIAMOND);
        verify(arms, sharedIngredient, vanilla.lookup.apply(sharedIngredient));
        measureWorkloads(dataset, size, arms, List.of(
                new Workload("first_input", List.of(early)),
                new Workload("second_input", List.of(late)),
                new Workload("miss", List.of(miss)),
                new Workload("shared_ingredient", List.of(sharedIngredient)),
                new Workload("mixed", mixed)), measurements);

    }

    private static void measureWorkloads(String dataset, int size, List<Arm> arms,
                                         List<Workload> workloads, ThreadMXBean measurements) {
        for (Workload workload : workloads) {
            for (int batch = -WARMUP_BATCHES; batch < MEASURED_BATCHES; batch++) {
                int order = Math.floorMod(batch, 2 * arms.size());
                for (int position = 0; position < arms.size(); position++) {
                    int armIndex = Math.floorMod(order / 2 + (order % 2 == 0 ? position : -position), arms.size());
                    measure(dataset, size, workload, arms.get(armIndex), batch, measurements);
                }
            }
        }
    }

    private static void runAdaptiveComparison(ThreadMXBean measurements) {
        var vanilla = loadVanillaRecipes();
        var log = input(1, 1, Items.OAK_LOG);
        var planks = input(2, 2, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS);
        var miss = input(2, 1, Items.DIRT, Items.DIAMOND);
        measureAdaptive("adaptive_builtin", vanilla, List.of(
                new Workload("mixed", List.of(log, log, planks, miss, planks)),
                new Workload("cold_mixed", List.of(log, planks, miss))), measurements);

        var dirt = Ingredient.of(Items.DIRT);
        var stick = Ingredient.of(Items.STICK);
        var oak = Ingredient.of(Items.OAK_PLANKS);
        var early = shapeless(dirt, stick, stick);
        var late = shapeless(dirt, oak, oak);
        for (int count : new int[] {96, 320, 1_024}) {
            List<CraftingRecipe> recipes = new ArrayList<>();
            recipes.add(early);
            for (int index = 0; index < count - 2; index++) {
                recipes.add(shapeless(dirt, dirt, dirt));
            }
            recipes.add(late);
            measureAdaptive("adaptive_dense", recipeMap(recipes), List.of(
                    new Workload("cold_early", permutations(Items.STICK)),
                    new Workload("cold_late", permutations(Items.OAK_PLANKS)),
                    new Workload("cold_miss", permutations(Items.DIAMOND)),
                    new Workload("repeated_miss", List.of(permutations(Items.DIAMOND).getFirst()))),
                    measurements);
            List<CraftingRecipe> horizontal = new ArrayList<>();
            var pattern = ShapedRecipePattern.of(Map.of('D', dirt), "DDD");
            for (int index = 0; index < count; index++) {
                horizontal.add(new ShapedRecipe(new CommonInfo(false),
                        new CraftingBookInfo(CraftingBookCategory.MISC, ""), pattern, new ItemStackTemplate(Items.STONE)));
            }
            measureAdaptive("adaptive_geometry", recipeMap(horizontal), List.of(
                    new Workload("cold_geometry_miss", List.of(
                            input(1, 3, Items.DIRT, Items.DIRT, Items.DIRT),
                            input(2, 2, Items.DIRT, Items.DIRT, Items.DIRT, Items.AIR)))), measurements);
        }
    }

    private static List<CraftingInput> permutations(ItemLike other) {
        return List.of(input(3, 1, Items.DIRT, other, other), input(3, 1, other, Items.DIRT, other));
    }

    private static CraftingRecipe shapeless(Ingredient... ingredients) {
        return new ShapelessRecipe(new CommonInfo(false), new CraftingBookInfo(CraftingBookCategory.MISC, ""),
                new ItemStackTemplate(Items.STONE), List.of(ingredients));
    }

    private static void measureAdaptive(String dataset, RecipeMap recipeMap, List<Workload> workloads,
                                        ThreadMXBean measurements) {
        var entries = recipeMap.byType(RecipeType.CRAFTING).stream().map(RecipeHolder::value).toList();
        var arms = new ArrayList<Arm>();
        // Record allocations during construction and the initial query. These are
        // transient allocation measurements, including the first-use indexes.
        for (Mode mode : List.of(Mode.COMBINED_CACHE, Mode.HASH_TREE_CACHE)) {
            addAdaptiveArm(arms, dataset, entries, mode, CraftingLookup.ADAPTIVE_CUTOFF,
                    workloads.getLast().inputs().getFirst(), measurements);
        }
        for (int cutoff : new int[] {64, 256, 512}) {
            addAdaptiveArm(arms, dataset, entries, Mode.ADAPTIVE_CACHE, cutoff,
                    workloads.getLast().inputs().getFirst(), measurements);
        }
        for (Workload workload : workloads) {
            for (CraftingInput input : workload.inputs()) {
                CraftingRecipe expected = recipeMap.getRecipesFor(RecipeType.CRAFTING, input, null)
                        .findFirst().map(RecipeHolder::value).orElse(null);
                verify(arms, input, expected);
            }
        }
        measureWorkloads(dataset, entries.size(), arms, workloads, measurements);
    }

    private static void addAdaptiveArm(List<Arm> arms, String dataset, List<CraftingRecipe> recipes,
                                       Mode mode, int cutoff, CraftingInput firstInput, ThreadMXBean measurements) {
        String name = mode == Mode.ADAPTIVE_CACHE ? "adaptive-" + cutoff : mode.id();
        long allocated = measurements.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        var lookup = new CraftingLookup<>(mode, recipes, Function.identity(), cutoff);
        lookup.find(firstInput, null);
        long wall = System.nanoTime() - start;
        long bytes = measurements.getCurrentThreadAllocatedBytes() - allocated;
        Bootstrap.realStdoutPrintln(String.format(Locale.ROOT, "adaptive_setup,%s,%d,%s,%d,%d",
                dataset, recipes.size(), name, wall, bytes));
        arms.add(new Arm(name, input -> lookup.find(input, null)));
    }

    private static void verifyAdaptiveSwitch() {
        var dirt = Ingredient.of(Items.DIRT);
        var diamond = Ingredient.of(Items.DIAMOND);
        int[] prefixCalls = {0};
        var custom = new ShapelessRecipe(new CommonInfo(false),
                new CraftingBookInfo(CraftingBookCategory.MISC, ""), new ItemStackTemplate(Items.STONE),
                List.of(dirt, diamond)) {
            @Override
            public boolean matches(CraftingInput input, Level level) {
                prefixCalls[0]++;
                return input.getItem(0).getCount() > 1;
            }
        };
        CraftingRecipe filler = shapeless(dirt, dirt);
        CraftingRecipe expected = shapeless(dirt, diamond);
        CraftingRecipe later = shapeless(dirt, diamond);
        var recipes = List.of(custom, filler, filler, filler, filler, expected, later);
        var lookup = new CraftingLookup<>(Mode.ADAPTIVE_CACHE, recipes, Function.identity(), 1);
        if (lookup.find(input(2, 1, Items.DIRT, Items.DIAMOND), null) != expected || prefixCalls[0] != 1) {
            throw new IllegalStateException("Adaptive switching replayed a prefix or changed recipe order");
        }
        var larger = CraftingInput.of(2, 1, List.of(new ItemStack(Items.DIRT, 2), new ItemStack(Items.DIAMOND)));
        if (lookup.find(larger, null) != custom || prefixCalls[0] != 2) {
            throw new IllegalStateException("Adaptive cache changed count-sensitive recipe priority");
        }
        List<CraftingRecipe> laterFallback = List.of(filler, filler, filler, filler, custom, expected, later);
        var secondLookup = new CraftingLookup<>(Mode.ADAPTIVE_CACHE, laterFallback, Function.identity(), 1);
        if (secondLookup.find(larger, null) != custom || prefixCalls[0] != 3) {
            throw new IllegalStateException("Tree switching lost an unclassified recipe after the checked prefix");
        }

        var dense = new ArrayList<CraftingRecipe>();
        dense.add(custom);
        for (int index = 0; index < 1200; index++) {
            dense.add(filler);
        }
        dense.add(expected);
        dense.add(later);
        var incremental = new CraftingLookup<>(Mode.ADAPTIVE_CACHE, dense, Function.identity(), 256);
        var left = input(2, 1, Items.DIRT, Items.DIAMOND);
        var right = input(2, 1, Items.DIAMOND, Items.DIRT);
        for (int step = 0; step < 32; step++) {
            if (incremental.find(step % 2 == 0 ? left : right, null) != expected || prefixCalls[0] != step + 4) {
                throw new IllegalStateException("Incremental tree changed priority or replayed the checked prefix");
            }
        }
    }

    private static CraftingInput input(int width, int height, ItemLike... items) {
        return CraftingInput.of(width, height,
                Arrays.stream(items).map(item -> new ItemStack(item)).toList());
    }

    private static void verifyCustomFallback(CraftingInput input) {
        var custom = new ShapelessRecipe(new CommonInfo(false),
                new CraftingBookInfo(CraftingBookCategory.MISC, ""),
                new ItemStackTemplate(Items.COBBLESTONE), List.of(Ingredient.of(Items.DIRT))) {
            @Override
            public boolean matches(CraftingInput input, Level level) {
                return input.ingredientCount() == 3;
            }
        };
        var regular = new ShapelessRecipe(new CommonInfo(false),
                new CraftingBookInfo(CraftingBookCategory.MISC, ""),
                new ItemStackTemplate(Items.STONE),
                List.of(Ingredient.of(Items.STICK), Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.SPRUCE_PLANKS)));
        for (Mode mode : List.of(Mode.OCCUPANCY, Mode.INGREDIENT, Mode.COMBINED, Mode.HASH_TREE)) {
            for (List<CraftingRecipe> order : List.<List<CraftingRecipe>>of(
                    List.of(custom, regular), List.of(regular, custom))) {
                var lookup = new CraftingLookup<>(mode, order, Function.identity());
                if (lookup.find(input, null) != order.getFirst()) {
                    throw new IllegalStateException(mode + " changed custom versus indexed recipe priority");
                }
            }
        }
    }

    private static void verifyCachePrecedence() {
        var common = new CommonInfo(false);
        var book = new CraftingBookInfo(CraftingBookCategory.MISC, "");
        var stick = Ingredient.of(Items.STICK);
        var output = new ItemStackTemplate(Items.STONE);
        var vanilla = new ShapelessRecipe(common, book, output, List.of(stick));
        var dynamic = new ShapelessRecipe(common, book, output, List.of(stick)) {
            @Override
            public boolean matches(CraftingInput input, Level level) {
                return input.getItem(0).getCount() > 1;
            }
        };
        var single = input(1, 1, Items.STICK);
        var multiple = CraftingInput.of(1, 1, List.of(new ItemStack(Items.STICK, 2)));
        for (Mode mode : List.of(Mode.CACHE, Mode.COMBINED_CACHE, Mode.HASH_TREE_CACHE, Mode.ADAPTIVE_CACHE)) {
            for (List<CraftingRecipe> recipes : List.<List<CraftingRecipe>>of(
                    List.of(dynamic, vanilla), List.of(dynamic))) {
                for (int capacity : new int[] {1, 4, 16}) {
                    var lookup = new CraftingLookup<>(mode, recipes, Function.identity(),
                            CraftingLookup.ADAPTIVE_CUTOFF, recipe -> true, capacity);
                    CraftingRecipe normalResult = recipes.size() == 2 ? vanilla : null;
                    var otherGrid = input(1, 1, Items.BEDROCK);
                    if (lookup.find(single, null) != normalResult
                            || lookup.find(otherGrid, null) != null
                            || lookup.find(multiple, null) != dynamic
                            || lookup.find(single, null) != normalResult) {
                        throw new IllegalStateException(mode + " changed a count-sensitive recipe's priority");
                    }
                }
            }
        }
    }

    private static void verifyOverlappingIngredientRequirements() {
        var noSharedStackAssignment = new ShapelessRecipe(new CommonInfo(false),
                new CraftingBookInfo(CraftingBookCategory.MISC, ""), new ItemStackTemplate(Items.STONE),
                List.of(Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS),
                        Ingredient.of(Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS)));
        for (Mode mode : TREE_MODES) {
            var lookup = new CraftingLookup<CraftingRecipe>(mode, List.of(noSharedStackAssignment), Function.identity());
            if (lookup.find(input(2, 1, Items.SPRUCE_PLANKS, Items.DIAMOND), null) != null
                    || lookup.find(input(2, 1, Items.SPRUCE_PLANKS, Items.OAK_PLANKS), null) != noSharedStackAssignment) {
                throw new IllegalStateException(mode + " changed overlapping ingredient assignment");
            }
        }
    }

    private static void verify(List<Arm> arms, CraftingInput input, CraftingRecipe expected) {
        for (Arm arm : arms) {
            if (arm.lookup.apply(input) != expected) {
                throw new IllegalStateException(arm.name + " selected a different recipe for " + input.items());
            }
        }
    }

    private static void measure(String dataset, int size, Workload workload, Arm arm, int batch,
                                ThreadMXBean measurements) {
        long cpu = measurements.getCurrentThreadCpuTime();
        long allocated = measurements.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        int found = 0;
        for (int index = 0; index < LOOKUPS_PER_BATCH; index++) {
            CraftingInput input = workload.inputs.get(index % workload.inputs.size());
            if (arm.lookup.apply(input) != null) {
                found++;
            }
        }
        long wall = System.nanoTime() - start;
        long bytes = measurements.getCurrentThreadAllocatedBytes() - allocated;
        long elapsedCpu = measurements.getCurrentThreadCpuTime() - cpu;
        checksum = found;
        if (batch >= 0) {
            Bootstrap.realStdoutPrintln(String.format(Locale.ROOT, "%s,%d,%s,%d,%s,%d,%d,%d,%d,%d",
                    dataset, size, workload.name, batch, arm.name, LOOKUPS_PER_BATCH, wall, elapsedCpu, bytes, checksum));
        }
    }

    private record Arm(String name, Function<CraftingInput, CraftingRecipe> lookup) {}

    private record Workload(String name, List<CraftingInput> inputs) {}
}
