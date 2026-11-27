package dev.fastrecipes.experiment;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

/** A trie over multisets of required ingredient hashes. Hashes are candidate hints. */
final class IngredientHashTree<T> {
    private static final int MAX_INGREDIENT_ITEMS = 64;
    private static final int MAX_QUERY_HASHES = 128;

    private final Map<Item, IntOpenHashSet> hashesByItem = new IdentityHashMap<>();
    private final IntArrayList fallback = new IntArrayList();
    private final Node root = new Node();
    private final List<T> ordered;

    IngredientHashTree(List<T> ordered, Function<T, CraftingRecipe> recipeValue,
                       Predicate<CraftingRecipe> indexable) {
        this(ordered);
        new Builder<>(this, recipeValue, indexable).advance(ordered.size(), Long.MAX_VALUE);
    }

    private IngredientHashTree(List<T> ordered) {
        this.ordered = ordered;
    }

    static final class Builder<T> {
        private final IngredientHashTree<T> tree;
        private final Function<T, CraftingRecipe> recipeValue;
        private final Predicate<CraftingRecipe> indexable;
        private final Map<Ingredient, Token> tokens = new HashMap<>();
        private int next;

        Builder(List<T> recipes, Function<T, CraftingRecipe> recipeValue, Predicate<CraftingRecipe> indexable) {
            this(new IngredientHashTree<>(recipes), recipeValue, indexable);
        }

        private Builder(IngredientHashTree<T> tree, Function<T, CraftingRecipe> recipeValue,
                        Predicate<CraftingRecipe> indexable) {
            this.tree = tree;
            this.recipeValue = recipeValue;
            this.indexable = indexable;
        }

        IngredientHashTree<T> advance(int limit, long budgetNs) {
            long start = System.nanoTime();
            int stop = next + Math.min(limit, tree.ordered.size() - next);
            while (next < stop) {
                addRecipe(next++);
                if (System.nanoTime() - start >= budgetNs) {
                    break;
                }
            }
            if (next == tree.ordered.size()) {
                return tree;
            }
            return null;
        }

        private void addRecipe(int ordinal) {
            CraftingRecipe recipe = recipeValue.apply(tree.ordered.get(ordinal));
            if (!indexable.test(recipe)) {
                tree.fallback.add(ordinal);
                return;
            }

            List<Ingredient> requirements = recipe.placementInfo().ingredients();
            if (requirements.isEmpty()) {
                tree.fallback.add(ordinal);
                return;
            }
            int[] hashes = new int[requirements.size()];
            for (int index = 0; index < hashes.length; index++) {
                Token token = tokens.computeIfAbsent(requirements.get(index), this::register);
                if (!token.usable()) {
                    tree.fallback.add(ordinal);
                    return;
                }
                hashes[index] = token.hash();
            }
            Arrays.sort(hashes);
            Node node = tree.root;
            for (int hash : hashes) {
                node = node.children.computeIfAbsent(hash, ignored -> new Node());
            }
            node.recipes.add(ordinal);
        }

        private Token register(Ingredient ingredient) {
            var items = ingredient.items().map(Holder::value).distinct().limit(MAX_INGREDIENT_ITEMS + 1).toList();
            if (items.isEmpty() || items.size() > MAX_INGREDIENT_ITEMS) {
                return new Token(0, false);
            }
            int hash = items.size();
            for (Item item : items) {
                hash += System.identityHashCode(item);
            }
            for (Item item : items) {
                tree.hashesByItem.computeIfAbsent(item, ignored -> new IntOpenHashSet()).add(hash);
            }
            return new Token(hash, true);
        }

        private record Token(int hash, boolean usable) {}
    }

    T find(CraftingInput input, Predicate<T> matches) {
        return find(input, matches, 0);
    }

    T find(CraftingInput input, Predicate<T> matches, int firstOrdinal) {
        var available = new Int2IntOpenHashMap();
        for (int slot = 0; slot < input.size(); slot++) {
            var stack = input.getItem(slot);
            IntOpenHashSet hashes = stack.isEmpty() ? null : hashesByItem.get(stack.getItem());
            if (hashes != null) {
                for (var iterator = hashes.iterator(); iterator.hasNext();) {
                    available.addTo(iterator.nextInt(), 1);
                }
                if (available.size() > MAX_QUERY_HASHES) {
                    return CraftingLookup.scan(ordered, matches, firstOrdinal);
                }
            }
        }
        var candidates = new IntArrayList(fallback);
        root.collect(available.keySet().toIntArray(), available, candidates);
        candidates.sort(null);
        for (int index = 0; index < candidates.size(); index++) {
            int ordinal = candidates.getInt(index);
            if (ordinal >= firstOrdinal) {
                T entry = ordered.get(ordinal);
                if (matches.test(entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static final class Node {
        private final Int2ObjectOpenHashMap<Node> children = new Int2ObjectOpenHashMap<>();
        private final IntArrayList recipes = new IntArrayList();

        private void collect(int[] hashes, Int2IntOpenHashMap available, IntArrayList candidates) {
            candidates.addAll(recipes);
            if (children.isEmpty()) {
                return;
            }
            for (int hash : hashes) {
                Node child = children.get(hash);
                int count = available.get(hash);
                if (child != null && count > 0) {
                    available.put(hash, count - 1);
                    child.collect(hashes, available, candidates);
                    available.put(hash, count);
                }
            }
        }
    }
}
