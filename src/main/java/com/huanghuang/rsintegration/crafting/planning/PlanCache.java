package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPreviewPolicy;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
    private final IntSupplier maxNodes;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    /** Latest plan actually shown to each player, retained until confirmation or replacement. */
    private final ConcurrentHashMap<UUID, ExecutionLease> executionLeases =
            new ConcurrentHashMap<>();

    public PlanCache() {
        this(PlanCache::configuredTtlNanos, PlanCache::configuredMaxEntries,
                PlanCache::configuredMaxNodes);
    }

    public PlanCache(long ttlNanos) {
        this(ttlNanos, DEFAULT_MAX_ENTRIES, Integer.MAX_VALUE);
    }

    PlanCache(long ttlNanos, int maxEntries) {
        this(ttlNanos, maxEntries, Integer.MAX_VALUE);
    }

    PlanCache(long ttlNanos, int maxEntries, int maxNodes) {
        this(() -> ttlNanos, () -> maxEntries, () -> maxNodes);
    }

    private PlanCache(LongSupplier ttlNanos, IntSupplier maxEntries, IntSupplier maxNodes) {
        if (ttlNanos.getAsLong() <= 0) throw new IllegalArgumentException("ttlNanos");
        if (maxEntries.getAsInt() <= 0) throw new IllegalArgumentException("maxEntries");
        if (maxNodes.getAsInt() <= 0) throw new IllegalArgumentException("maxNodes");
        this.ttlNanos = ttlNanos;
        this.maxEntries = maxEntries;
        this.maxNodes = maxNodes;
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

    /**
     * 为一次新的预览响应重新授予一次性执行租约。该方法只允许在服务端
     * 已完成状态复验、准备把缓存方案再次展示给客户端时调用。
     */
    public boolean grantExecutionLease(Key key, long now) {
        Entry entry = get(key, now);
        if (entry == null) return false;
        executionLeases.put(key.playerId(), new ExecutionLease(key, entry));
        return true;
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
        long nodeBudget = maxNodes.getAsInt();
        long totalNodes = entries.values().stream().mapToLong(PlanCache::entryWeight).sum();
        if (entries.size() <= maximum && totalNodes <= nodeBudget) return;
        for (Map.Entry<Key, Entry> oldest : entries.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(Entry::createdNanos)))
                .toList()) {
            if (entries.size() <= maximum && totalNodes <= nodeBudget) break;
            Entry removed = entries.remove(oldest.getKey());
            if (removed != null) totalNodes -= entryWeight(removed);
        }
    }

    static long entryWeight(Entry entry) {
        if (entry == null) return 0L;
        long planNodes = entry.plan().graph() == null
                ? 0L : entry.plan().graph().nodes().size();
        long resolvedNodes = entry.resolvedGraph() == null
                ? 0L : entry.resolvedGraph().nodes().size();
        return Math.max(1L, planNodes + resolvedNodes);
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

    private static int configuredMaxNodes() {
        try {
            return RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_NODES.get();
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_PLAN_CACHE_MAX_NODES;
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
