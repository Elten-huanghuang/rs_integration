package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.batch.LegacyExecutionMetrics;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.SynchronousFallbackReason;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Lightweight performance instrumentation.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Tick timing statistics (avg/max execution time per tick)</li>
 *   <li>Resolution timeout count ({@code MAX_RESOLVE_NANOS} exceeded)</li>
 *   <li>Active {@code AsyncCraftChain} count snapshot</li>
 * </ul>
 *
 * <p>All counters are atomic — safe to call from any thread.</p>
 */
public final class PerformanceMonitor {

    private static final AtomicLong resolveTimeouts = new AtomicLong();
    private static final AtomicLong tickCount = new AtomicLong();
    private static final AtomicLong totalTickNanos = new AtomicLong();
    private static final AtomicLong maxTickNanos = new AtomicLong();
    private static final AtomicLong planBuildCount = new AtomicLong();
    private static final AtomicLong totalPlanBuildNanos = new AtomicLong();
    private static final AtomicLong maxPlanBuildNanos = new AtomicLong();
    private static final AtomicLong totalPlanNodes = new AtomicLong();
    private static final AtomicLong maxPlanNodes = new AtomicLong();
    private static final AtomicLong planPacketBytes = new AtomicLong();
    private static final AtomicLong maxPlanPacketBytes = new AtomicLong();
    private static final AtomicLong progressPacketBytes = new AtomicLong();
    private static final AtomicLong maxProgressPacketBytes = new AtomicLong();
    private static final AtomicLong resonanceScannedStacks = new AtomicLong();
    private static final AtomicLong resonanceMatchedStacks = new AtomicLong();
    private static final AtomicLong networkResolveCalls = new AtomicLong();
    private static final AtomicLong networkResolveCacheHits = new AtomicLong();
    private static final AtomicLong networkResolveSuccesses = new AtomicLong();
    private static final AtomicLong recipeGraphProjectionCalls = new AtomicLong();
    private static final AtomicLong recipeGraphProjectionHits = new AtomicLong();
    private static final AtomicLong recipeGraphProjectionBuildNanos = new AtomicLong();
    private static final AtomicLong recipeGraphProjectionMaxNanos = new AtomicLong();
    private static final AtomicLong recipeCatalogBuilds = new AtomicLong();
    private static final AtomicLong recipeCatalogBuildNanos = new AtomicLong();
    private static final AtomicLong recipeCatalogBuildMaxNanos = new AtomicLong();
    private static final AtomicLong recipeCatalogGraphNanos = new AtomicLong();
    private static final AtomicLong recipeCatalogRecipes = new AtomicLong();
    private static final AtomicLong planningSubmitted = new AtomicLong();
    private static final AtomicLong planningCompleted = new AtomicLong();
    private static final AtomicLong planningRejected = new AtomicLong();
    private static final AtomicLong planningCancelled = new AtomicLong();
    private static final AtomicLong planningExecutionNanos = new AtomicLong();
    private static final AtomicLong planningExecutionMaxNanos = new AtomicLong();
    private static final AtomicLong planningWorkerActive = new AtomicLong();
    private static final AtomicLong planningQueueDepth = new AtomicLong();
    private static final AtomicLong purePlanningSearches = new AtomicLong();
    private static final AtomicLong purePlanningExpandedStates = new AtomicLong();
    private static final AtomicLong purePlanningBacktracks = new AtomicLong();
    private static final AtomicLong purePlanningMemoHits = new AtomicLong();
    private static final AtomicLong purePlanningStepLimits = new AtomicLong();
    private static final AtomicLong purePlanningSearchLimits = new AtomicLong();
    private static final AtomicLong planningSnapshotCaptures = new AtomicLong();
    private static final AtomicLong planningSnapshotNanos = new AtomicLong();
    private static final AtomicLong planningSnapshotMaxNanos = new AtomicLong();
    private static final AtomicLong demandTreeInspections = new AtomicLong();
    private static final AtomicLong demandTreeNanos = new AtomicLong();
    private static final AtomicLong demandTreeMaxNanos = new AtomicLong();
    private static final AtomicLong purePlanningNanos = new AtomicLong();
    private static final AtomicLong purePlanningMaxNanos = new AtomicLong();
    private static final AtomicLong typedResolverCalls = new AtomicLong();
    private static final AtomicLong typedResolverNanos = new AtomicLong();
    private static final AtomicLong typedResolverMaxNanos = new AtomicLong();
    private static final AtomicLong typedPreviewQueued = new AtomicLong();
    private static final AtomicLong typedPreviewReplaced = new AtomicLong();
    private static final AtomicLong typedPreviewRejected = new AtomicLong();
    private static final AtomicLong typedPreviewAdmitted = new AtomicLong();
    private static final AtomicLong typedPreviewQueueDepth = new AtomicLong();
    private static final AtomicLong vanillaTickOperations = new AtomicLong();
    private static final AtomicLong vanillaTickBudget = new AtomicLong();
    private static final AtomicLong vanillaDeferredChains = new AtomicLong();
    private static final AtomicLongArray synchronousPlanningFallbacks =
            new AtomicLongArray(SynchronousFallbackReason.values().length);
    private static final AtomicLong delegateObserveCalls = new AtomicLong();
    private static final AtomicLong delegateObserveNanos = new AtomicLong();
    private static final AtomicLong delegateObserveMaxNanos = new AtomicLong();
    private static final Map<String, DelegateStats> delegateStats = new ConcurrentHashMap<>();
    private record DelegateStats(AtomicLong calls, AtomicLong totalNanos, AtomicLong maxNanos) {}

    private PerformanceMonitor() {}

    /** Record a resolution that hit the deadline. */
    public static void recordResolveTimeout() {
        resolveTimeouts.incrementAndGet();
    }

    /** Record one tick's execution time. Called from server tick handler. */
    public static void recordTick(long nanosElapsed) {
        tickCount.incrementAndGet();
        totalTickNanos.addAndGet(nanosElapsed);
        maxTickNanos.updateAndGet(prev -> Math.max(prev, nanosElapsed));
    }

    public static void recordPlanBuild(long nanosElapsed, int nodes) {
        planBuildCount.incrementAndGet();
        totalPlanBuildNanos.addAndGet(nanosElapsed);
        maxPlanBuildNanos.updateAndGet(prev -> Math.max(prev, nanosElapsed));
        totalPlanNodes.addAndGet(nodes);
        maxPlanNodes.updateAndGet(prev -> Math.max(prev, nodes));
    }
    public static void recordPlanPacketBytes(int bytes) {
        planPacketBytes.addAndGet(bytes);
        maxPlanPacketBytes.updateAndGet(prev -> Math.max(prev, bytes));
    }
    public static void recordProgressPacketBytes(int bytes) {
        progressPacketBytes.addAndGet(bytes);
        maxProgressPacketBytes.updateAndGet(prev -> Math.max(prev, bytes));
    }
    public static void recordResonanceScan(int scanned, int matched) {
        resonanceScannedStacks.addAndGet(scanned);
        resonanceMatchedStacks.addAndGet(matched);
    }
    public static void recordNetworkResolve(boolean cacheHit, boolean success) {
        networkResolveCalls.incrementAndGet();
        if (cacheHit) networkResolveCacheHits.incrementAndGet();
        if (success) networkResolveSuccesses.incrementAndGet();
    }
    public static void recordRecipeGraphProjection(boolean cacheHit, long buildNanos) {
        recipeGraphProjectionCalls.incrementAndGet();
        if (cacheHit) {
            recipeGraphProjectionHits.incrementAndGet();
            return;
        }
        recipeGraphProjectionBuildNanos.addAndGet(Math.max(0L, buildNanos));
        recipeGraphProjectionMaxNanos.updateAndGet(previous -> Math.max(previous, buildNanos));
    }
    public static void recordRecipeCatalogBuild(long buildNanos, long graphNanos, int recipes) {
        recipeCatalogBuilds.incrementAndGet();
        recipeCatalogBuildNanos.addAndGet(Math.max(0L, buildNanos));
        recipeCatalogBuildMaxNanos.updateAndGet(previous -> Math.max(previous, buildNanos));
        recipeCatalogGraphNanos.addAndGet(Math.max(0L, graphNanos));
        recipeCatalogRecipes.set(Math.max(0, recipes));
    }
    public static void recordPlanningSubmitted(int activeWorkers, int queuedTasks) {
        planningSubmitted.incrementAndGet();
        recordPlanningExecutorState(activeWorkers, queuedTasks);
    }
    public static void recordPlanningRejected(int activeWorkers, int queuedTasks) {
        planningRejected.incrementAndGet();
        recordPlanningExecutorState(activeWorkers, queuedTasks);
    }
    public static void recordPlanningCancelled(int activeWorkers, int queuedTasks) {
        planningCancelled.incrementAndGet();
        recordPlanningExecutorState(activeWorkers, queuedTasks);
    }
    public static void recordPlanningExecution(long nanosElapsed, int activeWorkers, int queuedTasks) {
        planningCompleted.incrementAndGet();
        planningExecutionNanos.addAndGet(Math.max(0L, nanosElapsed));
        planningExecutionMaxNanos.updateAndGet(previous -> Math.max(previous, nanosElapsed));
        recordPlanningExecutorState(activeWorkers, queuedTasks);
    }
    public static void recordPlanningExecutorState(int activeWorkers, int queuedTasks) {
        planningWorkerActive.set(Math.max(0, activeWorkers));
        planningQueueDepth.set(Math.max(0, queuedTasks));
    }
    public static void recordPurePlanningSearch(PureRecipePlanner.Result result) {
        recordPurePlanningSearch(result, 0L);
    }
    public static void recordPurePlanningSearch(PureRecipePlanner.Result result, long nanosElapsed) {
        purePlanningSearches.incrementAndGet();
        purePlanningExpandedStates.addAndGet(result.expandedStates());
        purePlanningBacktracks.addAndGet(result.backtracks());
        purePlanningMemoHits.addAndGet(result.memoHits());
        purePlanningNanos.addAndGet(Math.max(0L, nanosElapsed));
        purePlanningMaxNanos.updateAndGet(previous -> Math.max(previous, nanosElapsed));
        if (result.status() == PureRecipePlanner.Status.STEP_LIMIT) {
            purePlanningStepLimits.incrementAndGet();
        } else if (result.status() == PureRecipePlanner.Status.SEARCH_LIMIT) {
            purePlanningSearchLimits.incrementAndGet();
        } else if (result.status() == PureRecipePlanner.Status.TIME_LIMIT) {
            resolveTimeouts.incrementAndGet();
        }
    }
    public static void recordPlanningSnapshot(long nanosElapsed) {
        planningSnapshotCaptures.incrementAndGet();
        planningSnapshotNanos.addAndGet(Math.max(0L, nanosElapsed));
        planningSnapshotMaxNanos.updateAndGet(previous -> Math.max(previous, nanosElapsed));
    }
    public static void recordDemandTreeInspection(long nanosElapsed) {
        demandTreeInspections.incrementAndGet();
        demandTreeNanos.addAndGet(Math.max(0L, nanosElapsed));
        demandTreeMaxNanos.updateAndGet(previous -> Math.max(previous, nanosElapsed));
    }
    public static void recordTypedResolver(long nanosElapsed) {
        typedResolverCalls.incrementAndGet();
        typedResolverNanos.addAndGet(Math.max(0L, nanosElapsed));
        typedResolverMaxNanos.updateAndGet(previous -> Math.max(previous, nanosElapsed));
    }
    public static void recordVanillaTickBudget(int used, int budget, int deferredChains) {
        vanillaTickOperations.addAndGet(Math.max(0, used));
        vanillaTickBudget.addAndGet(Math.max(0, budget));
        vanillaDeferredChains.addAndGet(Math.max(0, deferredChains));
    }
    public static void recordTypedPreviewQueued(boolean replaced, int queueDepth) {
        typedPreviewQueued.incrementAndGet();
        if (replaced) typedPreviewReplaced.incrementAndGet();
        typedPreviewQueueDepth.set(Math.max(0, queueDepth));
    }
    public static void recordTypedPreviewRejected(int queueDepth) {
        typedPreviewRejected.incrementAndGet();
        typedPreviewQueueDepth.set(Math.max(0, queueDepth));
    }
    public static void recordTypedPreviewAdmitted(int admitted, int queueDepth) {
        typedPreviewAdmitted.addAndGet(Math.max(0, admitted));
        typedPreviewQueueDepth.set(Math.max(0, queueDepth));
    }
    public static void recordSynchronousPlanningFallback(
            SynchronousFallbackReason reason, ResourceLocation recipeId) {
        synchronousPlanningFallbacks.incrementAndGet(reason.ordinal());
        Diagnostics.record(Diagnostics.Category.PLANNING_FALLBACK,
                "reason=" + reason, recipeId, null);
    }
    public static long getSynchronousPlanningFallbackCount(SynchronousFallbackReason reason) {
        return synchronousPlanningFallbacks.get(reason.ordinal());
    }
    static void resetSynchronousPlanningFallbacksForTest() {
        for (SynchronousFallbackReason reason : SynchronousFallbackReason.values()) {
            synchronousPlanningFallbacks.set(reason.ordinal(), 0L);
        }
    }
    private static String synchronousPlanningFallbackSummary() {
        return Arrays.stream(SynchronousFallbackReason.values())
                .map(reason -> reason.name().toLowerCase(java.util.Locale.ROOT) + ":"
                        + getSynchronousPlanningFallbackCount(reason))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    public static void recordDelegateObserve(long nanosElapsed) {
        delegateObserveCalls.incrementAndGet();
        delegateObserveNanos.addAndGet(nanosElapsed);
        delegateObserveMaxNanos.updateAndGet(prev -> Math.max(prev, nanosElapsed));
    }
    public static void recordDelegateObserve(String type, long nanosElapsed) {
        recordDelegateObserve(nanosElapsed);
        DelegateStats stats = delegateStats.computeIfAbsent(type,
                ignored -> new DelegateStats(new AtomicLong(), new AtomicLong(), new AtomicLong()));
        stats.calls().incrementAndGet();
        stats.totalNanos().addAndGet(nanosElapsed);
        stats.maxNanos().updateAndGet(prev -> Math.max(prev, nanosElapsed));
    }

    // ── Queries ────────────────────────────────────────────────────

    public static long getResolveTimeouts() {
        return resolveTimeouts.get();
    }

    public static long getTickCount() {
        return tickCount.get();
    }

    public static long getAvgTickMicros() {
        long n = tickCount.get();
        return n > 0 ? totalTickNanos.get() / n / 1000 : 0;
    }

    public static long getMaxTickMicros() {
        return maxTickNanos.get() / 1000;
    }

    public static int getActiveChainCount() {
        return AsyncCraftManager.getInstance().getActiveChainCount();
    }

    /** Snapshot for debug commands / logs. */
    public static String snapshot() {
        return "PerformanceMonitor: ticks=" + getTickCount()
             + " avg=" + getAvgTickMicros() + "μs"
             + " max=" + getMaxTickMicros() + "μs"
             + " timeout=" + getResolveTimeouts()
             + " plans=" + planBuildCount.get()
             + " planAvg=" + (planBuildCount.get() > 0 ? totalPlanBuildNanos.get() / planBuildCount.get() / 1000 : 0) + "us"
             + " planMax=" + maxPlanBuildNanos.get() / 1000 + "us"
             + " planNodes=" + (planBuildCount.get() > 0 ? totalPlanNodes.get() / planBuildCount.get() : 0)
             + " planBytes=" + planPacketBytes.get() + "/" + maxPlanPacketBytes.get()
             + " progressBytes=" + progressPacketBytes.get() + "/" + maxProgressPacketBytes.get()
             + " resonanceStacks=" + resonanceScannedStacks.get() + "/" + resonanceMatchedStacks.get()
             + " networkResolve=" + networkResolveCalls.get() + "/" + networkResolveCacheHits.get()
             + " networkSuccess=" + networkResolveSuccesses.get()
             + " recipeGraph=" + recipeGraphProjectionCalls.get() + "/" + recipeGraphProjectionHits.get()
             + "/" + (recipeGraphProjectionCalls.get() > recipeGraphProjectionHits.get()
                     ? recipeGraphProjectionBuildNanos.get()
                     / (recipeGraphProjectionCalls.get() - recipeGraphProjectionHits.get()) / 1000 : 0)
             + "/" + recipeGraphProjectionMaxNanos.get() / 1000 + "us"
             + " recipeCatalog=" + recipeCatalogBuilds.get() + "/"
             + (recipeCatalogBuilds.get() == 0 ? 0
                     : recipeCatalogBuildNanos.get() / recipeCatalogBuilds.get() / 1000) + "/"
             + recipeCatalogBuildMaxNanos.get() / 1000 + "us"
             + " graph=" + (recipeCatalogBuilds.get() == 0 ? 0
                     : recipeCatalogGraphNanos.get() / recipeCatalogBuilds.get() / 1000) + "us"
             + " recipes=" + recipeCatalogRecipes.get()
             + " planningPool=" + planningSubmitted.get() + "/" + planningCompleted.get()
             + "/" + planningRejected.get() + "/" + planningCancelled.get()
             + " active=" + planningWorkerActive.get()
             + " queued=" + planningQueueDepth.get()
             + " exec=" + (planningCompleted.get() == 0 ? 0
                     : planningExecutionNanos.get() / planningCompleted.get() / 1000)
             + "/" + planningExecutionMaxNanos.get() / 1000 + "us"
             + " pureSearch=" + purePlanningSearches.get()
             + "/" + purePlanningExpandedStates.get()
             + "/" + purePlanningBacktracks.get()
             + "/" + purePlanningMemoHits.get()
             + " limits=" + purePlanningStepLimits.get() + "/" + purePlanningSearchLimits.get()
             + " phaseSnapshot=" + planningSnapshotCaptures.get() + "/"
             + (planningSnapshotCaptures.get() == 0 ? 0
                     : planningSnapshotNanos.get() / planningSnapshotCaptures.get() / 1000) + "/"
             + planningSnapshotMaxNanos.get() / 1000 + "us"
             + " demandTree=" + demandTreeInspections.get() + "/"
             + (demandTreeInspections.get() == 0 ? 0
                     : demandTreeNanos.get() / demandTreeInspections.get() / 1000) + "/"
             + demandTreeMaxNanos.get() / 1000 + "us"
             + " phasePure=" + purePlanningSearches.get() + "/"
             + (purePlanningSearches.get() == 0 ? 0
                     : purePlanningNanos.get() / purePlanningSearches.get() / 1000) + "/"
             + purePlanningMaxNanos.get() / 1000 + "us"
             + " phaseTyped=" + typedResolverCalls.get() + "/"
             + (typedResolverCalls.get() == 0 ? 0
                     : typedResolverNanos.get() / typedResolverCalls.get() / 1000) + "/"
             + typedResolverMaxNanos.get() / 1000 + "us"
             + " typedQueue=" + typedPreviewQueued.get() + "/"
             + typedPreviewReplaced.get() + "/" + typedPreviewRejected.get()
             + "/" + typedPreviewAdmitted.get() + " depth=" + typedPreviewQueueDepth.get()
             + " vanillaTick=" + vanillaTickOperations.get() + "/"
             + vanillaTickBudget.get() + " deferred=" + vanillaDeferredChains.get()
             + " syncFallback=" + synchronousPlanningFallbackSummary()
             + " delegateObserve=" + delegateObserveCalls.get() + "/"
             + (delegateObserveCalls.get() == 0 ? 0 : delegateObserveNanos.get() / delegateObserveCalls.get() / 1000)
             + "/" + delegateObserveMaxNanos.get() / 1000 + "us"
             + " delegateTypes=" + delegateStats.entrySet().stream().limit(8)
             .map(e -> e.getKey() + ":" + e.getValue().totalNanos().get() / e.getValue().calls().get() / 1000
                     + "/" + e.getValue().maxNanos().get() / 1000 + "us")
             .collect(java.util.stream.Collectors.joining(","))
             + " legacy=" + LegacyExecutionMetrics.summary()
             + " chains=" + getActiveChainCount();
    }
}
