package com.huanghuang.rsintegration.crafting.cache;

import com.huanghuang.rsintegration.RSIntegrationMod;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tick-bucket LRU cache for crafting plan resolution results.
 * Cache key format: "recipeId|r=repeatCount|f=forcedSlotsHash"
 * Entries expire after TTL ticks. Maximum 128 entries.
 *
 * <p>This prevents cache-key collisions when repeatCount changes
 * (e.g. a plan for "stick x4" should not return the cached result
 * for "stick x1").</p>
 *
 * <h3>Wiring status</h3>
 * <b>Not wired</b> — plan resolution results are currently recomputed on every
 * request. This cache is a performance optimization for a bottleneck that has
 * not been observed yet. {@code CandidateEngine} + {@code CraftingResolver}
 * complete in &lt;500ms even for deep chains.
 *
 * <h3>When to wire</h3>
 * If profiling shows that {@code tryBuildPlan()} in {@code GenericCraftPacket}
 * is called frequently with the same parameters (e.g. multiple players viewing
 * the same recipe), add:
 * <pre>{@code
 *   PlanResponse cached = PlanCache.get(key);
 *   if (cached != null) { send(cached); return; }
 *   PlanResponse result = computePlan(...);
 *   PlanCache.put(key, result);
 * }</pre>
 * Also call {@link #onServerTick(long)} from the server tick handler.
 */
public final class PlanCache {
    private static final int MAX_ENTRIES = 128;
    private static final long TTL_TICKS = 3; // expire after 3 ticks (150ms)

    private record CacheEntry(Object value, long createdAtTick) {}

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static long currentTick;

    private PlanCache() {}

    /**
     * Build a cache key from recipe identifier, repeat count, and optional
     * forced-slots map (for OR-path selection in previews).
     */
    public static String buildKey(String recipeId, int repeatCount,
                                  Map<String, String> forcedSlots) {
        StringBuilder sb = new StringBuilder(recipeId);
        sb.append("|r=").append(repeatCount);
        if (forcedSlots != null && !forcedSlots.isEmpty()) {
            sb.append("|f=").append(forcedSlots.hashCode());
        }
        return sb.toString();
    }

    /**
     * Convenience: build a key without forced-slot overrides.
     */
    public static String buildKey(String recipeId, int repeatCount) {
        return buildKey(recipeId, repeatCount, null);
    }

    /** Must be called once per server tick. */
    public static void onServerTick(long tick) {
        currentTick = tick;
        // Clean expired entries (ConcurrentHashMap-safe iterator)
        for (Iterator<Map.Entry<String, CacheEntry>> it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            CacheEntry entry = it.next().getValue();
            if (tick - entry.createdAtTick > TTL_TICKS) {
                it.remove();
            }
        }
        // Also enforce max size
        if (CACHE.size() > MAX_ENTRIES) {
            long cutoff = tick - TTL_TICKS;
            CACHE.values().removeIf(e -> e.createdAtTick < cutoff);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        CacheEntry entry = CACHE.get(key);
        if (entry != null && currentTick - entry.createdAtTick <= TTL_TICKS) {
            return (T) entry.value;
        }
        CACHE.remove(key);
        return null;
    }

    public static void put(String key, Object value) {
        CACHE.put(key, new CacheEntry(value, currentTick));
        RSIntegrationMod.LOGGER.debug("[RSI-PlanCache] Cached key={} size={}", key, CACHE.size());
    }

    public static void clear() {
        CACHE.clear();
        RSIntegrationMod.LOGGER.debug("[RSI-PlanCache] Cleared all entries");
    }

    /** Diagnostic: current cache size. */
    public static int size() {
        return CACHE.size();
    }
}
