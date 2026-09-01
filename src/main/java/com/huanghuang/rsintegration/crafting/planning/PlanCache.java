package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPreviewPolicy;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Bounded, short-lived preview cache independent from packet transport code. */
public final class PlanCache {
    public static final long DEFAULT_TTL_NANOS =
            CraftingPreviewPolicy.DEFAULT_CACHE_TTL_MS * 1_000_000L;
    static final int DEFAULT_MAX_ENTRIES = CraftingPreviewPolicy.DEFAULT_CACHE_MAX_ENTRIES;
    private final LongSupplier ttlNanos;
    private final IntSupplier maxEntries;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    /** Latest plan actually shown to each player, retained until confirmation or replacement. */
    private final ConcurrentHashMap<UUID, ExecutionLease> executionLeases =
            new ConcurrentHashMap<>();

    public PlanCache() {
        this(PlanCache::configuredTtlNanos, PlanCache::configuredMaxEntries);
    }

    public PlanCache(long ttlNanos) {
        this(ttlNanos, DEFAULT_MAX_ENTRIES);
    }

    PlanCache(long ttlNanos, int maxEntries) {
        this(() -> ttlNanos, () -> maxEntries);
    }

    private PlanCache(LongSupplier ttlNanos, IntSupplier maxEntries) {
        if (ttlNanos.getAsLong() <= 0) throw new IllegalArgumentException("ttlNanos");
        if (maxEntries.getAsInt() <= 0) throw new IllegalArgumentException("maxEntries");
        this.ttlNanos = ttlNanos;
        this.maxEntries = maxEntries;
    }

    public Entry get(Key key, long now) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (now - entry.createdNanos() >= ttlNanos.getAsLong()) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot, long now) {
        put(key, plan, snapshot, null, now);
    }

    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot,
                    PureRecipePlanner.Result purePlan, long now) {
        put(key, plan, snapshot, purePlan, null, now);
    }

    /**
     * Stores the server-authoritative graph produced by the typed resolver as
     * well as the client response.  Confirming a preview can then reuse this
     * graph after state revalidation instead of running the bounded resolver
     * again on the server thread.
     */
    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot,
                    PureRecipePlanner.Result purePlan, CraftPlanGraph resolvedGraph, long now) {
        Entry entry = new Entry(plan, snapshot, purePlan, resolvedGraph, now);
        entries.put(key, entry);
        executionLeases.put(key.playerId(), new ExecutionLease(key, entry));
        prune(now);
    }

    /**
     * Consumes the plan currently displayed by this player, independently of
     * the short preview deduplication TTL. The caller must still revalidate the
     * snapshot before executing it.
     */
    public Entry takeForExecution(Key key) {
        ExecutionLease lease = executionLeases.get(key.playerId());
        if (lease == null || !lease.key().equals(key)) return null;
        return executionLeases.remove(key.playerId(), lease) ? lease.entry() : null;
    }

    public void clear() {
        entries.clear();
        executionLeases.clear();
    }

    public void removePlayer(UUID playerId) {
        entries.keySet().removeIf(key -> key.playerId().equals(playerId));
        executionLeases.remove(playerId);
    }

    public int size() {
        return entries.size();
    }

    private void prune(long now) {
        long cutoff = now - ttlNanos.getAsLong();
        entries.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
        int maximum = maxEntries.getAsInt();
        if (entries.size() <= maximum) return;
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(Entry::createdNanos)))
                .limit(entries.size() - maximum)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private static long configuredTtlNanos() {
        try {
            return CraftingPreviewPolicy.load().cacheTtlMs() * 1_000_000L;
        } catch (Exception ignored) {
            return DEFAULT_TTL_NANOS;
        }
    }

    private static int configuredMaxEntries() {
        try {
            return CraftingPreviewPolicy.load().cacheMaxEntries();
        } catch (Exception ignored) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    public record Key(UUID playerId, ResourceLocation recipeId,
                      Map<String, String> forcedRecipes, int repeatCount,
                      String clickedOutputToken, Map<String, String> materialLocks) {
        public Key(UUID playerId, ResourceLocation recipeId,
                   Map<String, String> forcedRecipes, int repeatCount,
                   String clickedOutputToken) {
            this(playerId, recipeId, forcedRecipes, repeatCount, clickedOutputToken, Map.of());
        }

        public Key {
            forcedRecipes = Map.copyOf(forcedRecipes);
            materialLocks = Map.copyOf(materialLocks);
        }
    }

    public record Entry(PlanResponse plan, PlanningSnapshot snapshot,
                        PureRecipePlanner.Result purePlan,
                        CraftPlanGraph resolvedGraph, long createdNanos) {}

    private record ExecutionLease(Key key, Entry entry) {}
}
