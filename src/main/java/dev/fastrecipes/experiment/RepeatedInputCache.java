package dev.fastrecipes.experiment;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.Level;

/** A bounded cache owned by the active recipe manager and its lookup thread. */
final class RepeatedInputCache<T> {
    private final Map<T, Integer> ordinals = new IdentityHashMap<>();
    private final List<DynamicRecipe<T>> dynamic = new ArrayList<>();
    private final Function<T, CraftingRecipe> recipeValue;
    private final List<Entry<T>> older;
    private final int olderCapacity;
    private Entry<T> last;

    RepeatedInputCache(List<T> ordered, Function<T, CraftingRecipe> recipeValue,
                       Predicate<CraftingRecipe> indexable, int capacity) {
        this.recipeValue = recipeValue;
        olderCapacity = capacity - 1;
        older = olderCapacity == 0 ? null : new ArrayList<>(olderCapacity);
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            T recipe = ordered.get(ordinal);
            if (indexable.test(recipeValue.apply(recipe))) {
                ordinals.putIfAbsent(recipe, ordinal);
            } else {
                dynamic.add(new DynamicRecipe<>(ordinal, recipe));
            }
        }
    }

    T find(CraftingInput input, Level level, Supplier<T> search) {
        // Keep a local snapshot in case a recipe predicate performs a nested lookup.
        Entry<T> entry = last;
        if (entry != null && !entry.matches(input)) {
            entry = findOlder(input);
        }
        if (entry != null) {
            for (DynamicRecipe<T> candidate : dynamic) {
                if (candidate.ordinal() >= entry.ordinal()) {
                    break;
                }
                if (recipeValue.apply(candidate.recipe()).matches(input, level)) {
                    return candidate.recipe();
                }
            }
            return entry.result();
        }

        T result = search.get();
        Integer ordinal = result == null ? Integer.valueOf(Integer.MAX_VALUE) : ordinals.get(result);
        if (ordinal != null) {
            if (older != null) {
                // A nested lookup may already have inserted this key.
                older.removeIf(candidate -> candidate.matches(input));
                if (last != null && !last.matches(input)) {
                    if (older.size() == olderCapacity) {
                        older.removeLast();
                    }
                    older.addFirst(last);
                }
            }
            last = new Entry<>(input, ordinal, result);
        }
        return result;
    }

    private Entry<T> findOlder(CraftingInput input) {
        if (older != null) {
            for (int index = 0; index < older.size(); index++) {
                Entry<T> candidate = older.get(index);
                if (candidate.matches(input)) {
                    older.remove(index);
                    older.addFirst(last);
                    last = candidate;
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Holder<?> itemAt(CraftingInput input, int slot) {
        var stack = input.getItem(slot);
        return stack.isEmpty() ? null : stack.typeHolder();
    }

    private record DynamicRecipe<T>(int ordinal, T recipe) {}

    private record Entry<T>(int width, int height, Holder<?>[] items, int ordinal, T result) {
        Entry(CraftingInput input, int ordinal, T result) {
            this(input.width(), input.height(), new Holder<?>[input.size()], ordinal, result);
            // Vanilla 26.3 accounts for one item per nonempty slot. Dynamic recipes
            // are evaluated with the actual count, components and level each time.
            for (int slot = 0; slot < items.length; slot++) {
                items[slot] = itemAt(input, slot);
            }
        }

        boolean matches(CraftingInput input) {
            if (input.width() != width || input.height() != height) {
                return false;
            }
            for (int slot = 0; slot < items.length; slot++) {
                if (itemAt(input, slot) != items[slot]) {
                    return false;
                }
            }
            return true;
        }
    }
}
