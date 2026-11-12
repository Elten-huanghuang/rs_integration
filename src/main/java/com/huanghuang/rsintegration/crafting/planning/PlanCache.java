package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPreviewPolicy;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
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
    /** 已发布预览按请求保存，后台完成另一个任务不能替换玩家正在确认的路线。 */
    private final ConcurrentHashMap<PreviewKey, ExecutionLease> requestLeases = new ConcurrentHashMap<>();

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
     * 保存与客户端响应同源的权威终端输入图，包括 typed 解析结果和直接材料分配图。
     * 确认预览后可在状态复验通过时复用它，避免重新搜索并选择另一条生产路线。
     */
    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot,
                    PureRecipePlanner.Result purePlan, CraftPlanGraph resolvedGraph, long now) {
        put(key, plan, snapshot, purePlan, resolvedGraph, false, now);
    }

    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot,
                    PureRecipePlanner.Result purePlan, CraftPlanGraph resolvedGraph,
                    boolean terminalEmbedded, long now) {
        Entry entry = new Entry(plan, snapshot, purePlan, resolvedGraph, terminalEmbedded, now);
        entries.put(key, entry);
        Key executionKey = key.withoutPreferences();
        executionLeases.put(key.playerId(), new ExecutionLease(executionKey, entry));
        prune(now);
    }

    /**
     * Consumes the plan currently displayed by this player, independently of
     * the short preview deduplication TTL. The caller must still revalidate the
     * snapshot before executing it.
     */
    public Entry takeForExecution(Key key) {
        Entry entry = peekForExecution(key);
        if (entry == null) return null;
        return consumeForExecution(key, entry) ? entry : null;
    }

    /**
     * 查看当前执行租约但不消费。调用方必须先复验返回条目携带的完整快照，
     * 再调用 {@link #consumeForExecution(Key, Entry)}。
     */
    public Entry peekForExecution(Key key) {
        Key executionKey = key.withoutPreferences();
        ExecutionLease lease = executionLeases.get(executionKey.playerId());
        if (lease == null || !lease.key().equals(executionKey)) return null;
        return lease.entry();
    }

    /** 查看玩家当前展示的租约，调用方必须按业务字段和完整快照自行复验。 */
    @Nullable
    public Entry peekLatestForExecution(UUID playerId) {
        ExecutionLease lease = executionLeases.get(playerId);
        return lease == null ? null : lease.entry();
    }

    /** 从有界的已发布预览中寻找重算路线提示；不延长、授予或消费执行租约。 */
    @Nullable
    public Entry findForReplan(Key expected) {
        Key identity = expected.withoutPreferences();
        return requestLeases.values().stream()
                .filter(lease -> lease.key().equals(identity)
                        && lease.entry().snapshot().preferences().equals(expected.preferences()))
                .map(ExecutionLease::entry)
                .max(Comparator.comparingLong(Entry::createdNanos)).orElse(null);
    }

    /** 在发布响应时关联客户端请求；保留完整服务端图，不接受客户端上传的计划。 */
    public void retainForRequest(UUID playerId, long requestId, PlanningSnapshot snapshot) {
        if (requestId <= 0L || !playerId.equals(snapshot.playerId())) return;
        ExecutionLease lease = executionLeases.get(playerId);
        if (lease == null || !lease.entry().snapshot().equals(snapshot)) {
            lease = entries.entrySet().stream()
                    .filter(entry -> entry.getKey().playerId().equals(playerId)
                            && entry.getValue().snapshot().equals(snapshot))
                    .map(entry -> new ExecutionLease(entry.getKey().withoutPreferences(), entry.getValue()))
                    .findFirst().orElse(null);
        }
        if (lease == null) return;
        requestLeases.putIfAbsent(new PreviewKey(playerId, requestId), lease);
        // 使用已有缓存条数和图节点预算，不为多任务凭据引入无界保留或新经验上限。
        long weight = requestLeases.values().stream().mapToLong(value -> entryWeight(value.entry())).sum();
        if (requestLeases.size() <= maxEntries.getAsInt() && weight <= maxNodes.getAsInt()) return;
        for (var oldest : requestLeases.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().entry().createdNanos())).toList()) {
            if (requestLeases.size() <= maxEntries.getAsInt() && weight <= maxNodes.getAsInt()) break;
            if (requestLeases.remove(oldest.getKey(), oldest.getValue())) weight -= entryWeight(oldest.getValue().entry());
        }
    }

    /** 仅用于恢复库存提示；不授予执行权限，也不消费任务凭据。 */
    @Nullable
    public PlanResponse publishedPlan(UUID playerId, long requestId) {
        ExecutionLease lease = requestLeases.get(new PreviewKey(playerId, requestId));
        return lease != null && CraftPlanningRevision.isCurrent(lease.key().recipeRevision())
                ? lease.entry().plan() : null;
    }

    /** 仍需由调用方校验产物、路线、网络、机器及当前材料；此处仅检查请求与锁定选材身份。 */
    public Entry peekForRequest(Key expected, long requestId) {
        ExecutionLease lease = requestLeases.get(new PreviewKey(expected.playerId(), requestId));
        if (lease == null || !lease.key().recipeId().equals(expected.recipeId())
                || lease.key().repeatCount() != expected.repeatCount()
                || lease.key().recipeRevision() != expected.recipeRevision()
                || !lease.key().materialLocks().equals(expected.materialLocks())) return null;
        return lease.entry();
    }

    public boolean consumeForRequest(UUID playerId, long requestId, Entry expected) {
        PreviewKey key = new PreviewKey(playerId, requestId);
        ExecutionLease lease = requestLeases.get(key);
        if (lease == null || lease.entry() != expected || !requestLeases.remove(key, lease)) return false;
        executionLeases.computeIfPresent(playerId,
                (ignored, current) -> current.entry() == expected ? null : current);
        return true;
    }

    /** 仅消费调用方已经复验过的同一个缓存条目。 */
    public boolean consumeForExecution(Key key, Entry expectedEntry) {
        if (expectedEntry == null) return false;
        Key executionKey = key.withoutPreferences();
        ExecutionLease lease = executionLeases.get(executionKey.playerId());
        if (lease == null || !lease.key().equals(executionKey)
                || lease.entry() != expectedEntry) {
            return false;
        }
        return executionLeases.remove(key.playerId(), lease);
    }

    /** 消费已经按业务字段和完整快照复验过的玩家当前租约。 */
    public boolean consumeLatestForExecution(UUID playerId, Entry expectedEntry) {
        if (expectedEntry == null) return false;
        ExecutionLease lease = executionLeases.get(playerId);
        if (lease == null || lease.entry() != expectedEntry) return false;
        return executionLeases.remove(playerId, lease);
    }

    /**
     * 为一次新的预览响应重新授予一次性执行租约。该方法只允许在服务端
     * 已完成状态复验、准备把缓存方案再次展示给客户端时调用。
     */
    public boolean grantExecutionLease(Key key, long now) {
        Entry entry = get(key, now);
        if (entry == null) return false;
        Key executionKey = key.withoutPreferences();
        executionLeases.put(key.playerId(), new ExecutionLease(executionKey, entry));
        return true;
    }

    public void clear() {
        entries.clear();
        executionLeases.clear();
        requestLeases.clear();
    }

    public void removePlayer(UUID playerId) {
        entries.keySet().removeIf(key -> key.playerId().equals(playerId));
        executionLeases.remove(playerId);
        requestLeases.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    /** 重算同一预览前移除其结果和租约，避免立刻命中刚刚过期的库存快照。 */
    public void invalidateSnapshot(PlanningSnapshot snapshot) {
        entries.entrySet().removeIf(entry -> entry.getValue().snapshot().equals(snapshot));
        requestLeases.entrySet().removeIf(entry -> entry.getValue().entry().snapshot().equals(snapshot));
        ExecutionLease lease = executionLeases.get(snapshot.playerId());
        if (lease != null && lease.entry().snapshot().equals(snapshot)) {
            executionLeases.remove(snapshot.playerId(), lease);
        }
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

    private record PreviewKey(UUID playerId, long requestId) {}

    public record Key(UUID playerId, ResourceLocation recipeId,
                      Map<String, String> forcedRecipes, int repeatCount,
                      String clickedOutputToken, Map<String, String> materialLocks,
                      PlanningPreferences preferences, long recipeRevision) {
        public Key(UUID playerId, ResourceLocation recipeId,
                   Map<String, String> forcedRecipes, int repeatCount,
                   String clickedOutputToken) {
            this(playerId, recipeId, forcedRecipes, repeatCount, clickedOutputToken,
                    Map.of(), PlanningPreferences.empty(), CraftPlanningRevision.current());
        }

        public Key(UUID playerId, ResourceLocation recipeId,
                   Map<String, String> forcedRecipes, int repeatCount,
                   String clickedOutputToken, Map<String, String> materialLocks) {
            this(playerId, recipeId, forcedRecipes, repeatCount, clickedOutputToken,
                    materialLocks, PlanningPreferences.empty(), CraftPlanningRevision.current());
        }

        public Key(UUID playerId, ResourceLocation recipeId,
                   Map<String, String> forcedRecipes, int repeatCount,
                   String clickedOutputToken, Map<String, String> materialLocks,
                   PlanningPreferences preferences) {
            this(playerId, recipeId, forcedRecipes, repeatCount, clickedOutputToken,
                    materialLocks, preferences, CraftPlanningRevision.current());
        }

        public Key {
            forcedRecipes = Map.copyOf(forcedRecipes);
            materialLocks = Map.copyOf(materialLocks);
            preferences = preferences == null
                    ? PlanningPreferences.empty() : preferences;
            if (recipeRevision <= 0L) {
                throw new IllegalArgumentException("recipe revision must be positive");
            }
        }

        private Key withoutPreferences() {
            if (preferences.isEmpty()) return this;
            return new Key(playerId, recipeId, forcedRecipes, repeatCount,
                    clickedOutputToken, materialLocks, PlanningPreferences.empty(),
                    recipeRevision);
        }
    }

    public record Entry(PlanResponse plan, PlanningSnapshot snapshot,
                        PureRecipePlanner.Result purePlan,
                        CraftPlanGraph resolvedGraph, boolean terminalEmbedded, long createdNanos) {
        public Entry(PlanResponse plan, PlanningSnapshot snapshot,
                     PureRecipePlanner.Result purePlan,
                     CraftPlanGraph resolvedGraph, long createdNanos) {
            this(plan, snapshot, purePlan, resolvedGraph, false, createdNanos);
        }
    }

    /**
     * 租约直接持有完整 PlanningSnapshot，不能由调用方用部分 key 重新构造。
     */
    private record ExecutionLease(Key key, Entry entry) {
        private ExecutionLease {
            if (entry == null || entry.snapshot() == null) {
                throw new IllegalArgumentException("execution lease requires a full snapshot");
            }
        }
    }
}
