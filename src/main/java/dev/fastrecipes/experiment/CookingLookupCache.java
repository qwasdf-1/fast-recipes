package dev.fastrecipes.experiment;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;

/** Caches previously measured unhinted outcomes for a single cooking type and recipe snapshot. */
public final class CookingLookupCache<T> {
    private static final int CAPACITY = 64;
    private final Map<Holder<?>, Optional<T>> hits;
    private final Map<Holder<?>, Optional<T>> misses;

    public CookingLookupCache(boolean cacheHits, boolean cacheMisses) {
        hits = cacheHits ? new IdentityHashMap<>() : null;
        misses = cacheMisses ? new IdentityHashMap<>() : null;
    }

    /** Null denotes an uncached input; an empty optional denotes a cached miss. */
    public Optional<T> get(Holder<?> item) {
        var hit = hits == null ? null : hits.get(item);
        return hit != null || misses == null ? hit : misses.get(item);
    }

    public void put(Holder<?> item, Optional<T> result) {
        var target = result.isPresent() ? hits : misses;
        if (target == null || target.containsKey(item)) {
            return;
        }
        if (target.size() == CAPACITY) {
            target.clear();
        }
        target.put(item, result);
    }
}
