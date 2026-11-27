package dev.fastrecipes.experiment;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeInput;

/** Indexes each recipe by one required slot, with all ordinal positions kept in vanilla order. */
public final class OrderedIngredientLookup<T> {
    private static final int MAX_ITEMS = 64;
    private static final int[] EMPTY = new int[0];

    public record Requirement(int slot, Ingredient ingredient) {}

    private final List<T> ordered;
    private final List<Map<Item, int[]>> bySlot;
    private final int[] fallback;

    public OrderedIngredientLookup(List<T> entries, int slots, Function<T, List<Requirement>> requirements) {
        ordered = List.copyOf(entries);
        List<Map<Item, IntArrayList>> pending = new ArrayList<>();
        bySlot = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            pending.add(new IdentityHashMap<>());
            bySlot.add(new IdentityHashMap<>());
        }
        var unclassified = new IntArrayList();
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            int pivotSlot = 0;
            int limit = MAX_ITEMS + 1;
            List<Item> pivot = List.of();
            for (var requirement : requirements.apply(ordered.get(ordinal))) {
                var items = requirement.ingredient().items().map(Holder::value).distinct().limit(limit).toList();
                if (!items.isEmpty() && items.size() < limit) {
                    pivotSlot = requirement.slot();
                    pivot = items;
                    limit = items.size();
                    if (limit == 1) {
                        break;
                    }
                }
            }
            if (pivot.isEmpty()) {
                unclassified.add(ordinal);
            } else {
                for (Item item : pivot) {
                    pending.get(pivotSlot).computeIfAbsent(item, ignored -> new IntArrayList()).add(ordinal);
                }
            }
        }
        for (int slot = 0; slot < slots; slot++) {
            var target = bySlot.get(slot);
            pending.get(slot).forEach((item, ordinals) -> target.put(item, ordinals.toIntArray()));
        }
        fallback = unclassified.toIntArray();
    }

    public T first(RecipeInput input, Predicate<T> matches) {
        var candidates = new Candidates(input);
        for (int ordinal = candidates.next(); ordinal >= 0; ordinal = candidates.next()) {
            T entry = ordered.get(ordinal);
            if (matches.test(entry)) {
                return entry;
            }
        }
        return null;
    }

    public List<T> all(RecipeInput input, Predicate<T> matches) {
        var results = new ArrayList<T>();
        var candidates = new Candidates(input);
        for (int ordinal = candidates.next(); ordinal >= 0; ordinal = candidates.next()) {
            T entry = ordered.get(ordinal);
            if (matches.test(entry)) {
                results.add(entry);
            }
        }
        return List.copyOf(results);
    }

    private final class Candidates {
        private final int[][] postings = new int[bySlot.size() + 1][];
        private final int[] positions = new int[postings.length];

        private Candidates(RecipeInput input) {
            postings[0] = fallback;
            for (int slot = 0; slot < bySlot.size(); slot++) {
                postings[slot + 1] = bySlot.get(slot).getOrDefault(input.getItem(slot).getItem(), EMPTY);
            }
        }

        private int next() {
            int best = Integer.MAX_VALUE;
            int posting = -1;
            for (int index = 0; index < postings.length; index++) {
                int position = positions[index];
                if (position < postings[index].length && postings[index][position] < best) {
                    best = postings[index][position];
                    posting = index;
                }
            }
            if (posting == -1) {
                return -1;
            }
            positions[posting]++;
            return best;
        }
    }
}
