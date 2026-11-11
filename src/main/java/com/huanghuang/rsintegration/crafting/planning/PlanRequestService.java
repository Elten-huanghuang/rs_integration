package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import java.util.concurrent.atomic.AtomicLong;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns preview request generations, cancellation and background pure-plan dispatch. */
public final class PlanRequestService implements AutoCloseable {
    private final ConcurrentHashMap<PlanningRequestKey, Boolean> requests = new ConcurrentHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final int capacity;
    private final AsyncPlanningCoordinator coordinator;
    private final AsyncPurePlanningService purePlanning;
    private final AsyncPlanResponseService responsePlanning;
    private final AsyncMaxCraftablePlanningService maxCraftablePlanning;
    private final int maxSearchStates;
    private final int maxMemoizedFailures;
    private final int pureTimeoutMs;

    public PlanRequestService(int parallelism) {
        this(parallelism,
                RSIntegrationConfig
                        .DEFAULT_CRAFTING_PLANNING_QUEUE_CAPACITY);
    }

    public PlanRequestService(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity,
                RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_SEARCH_MAX_STATES,
                RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES,
                RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_PLANNING_TIMEOUT_MS);
    }

    public PlanRequestService(int parallelism, int queueCapacity,
                              int maxSearchStates, int maxMemoizedFailures) {
        this(parallelism, queueCapacity, maxSearchStates, maxMemoizedFailures,
                RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_PLANNING_TIMEOUT_MS);
    }

    public PlanRequestService(int parallelism, int queueCapacity,
                              int maxSearchStates, int maxMemoizedFailures,
                              int pureTimeoutMs) {
        this(parallelism, queueCapacity, maxSearchStates, maxMemoizedFailures, pureTimeoutMs,
                CraftingPlanningConfig.DEFAULT_SLICE_MS, CraftingPlanningConfig.DEFAULT_BACKGROUND_TIMEOUT_MS);
    }

    public PlanRequestService(int parallelism, int queueCapacity, int maxSearchStates,
                              int maxMemoizedFailures, int pureTimeoutMs, int sliceMs, int timeoutMs) {
        coordinator = new AsyncPlanningCoordinator(parallelism, queueCapacity, sliceMs, timeoutMs);
        capacity = Math.addExact(Math.max(1, parallelism), Math.max(1, queueCapacity));
        purePlanning = new AsyncPurePlanningService(coordinator);
        responsePlanning = new AsyncPlanResponseService(coordinator);
        maxCraftablePlanning = new AsyncMaxCraftablePlanningService(coordinator);
        this.maxSearchStates = Math.max(1, maxSearchStates);
        this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
        this.pureTimeoutMs = Math.max(1, pureTimeoutMs);
    }

    public synchronized long begin(UUID playerId) {
        if (requests.size() >= capacity) return 0L;
        long generation = nextGeneration.incrementAndGet();
        requests.put(new PlanningRequestKey(playerId, generation), Boolean.TRUE);
        return generation;
    }

    /** 保留单调递增代数，使已取消请求的迟到回调不能重新成为当前请求。 */
    public boolean cancel(UUID playerId, long generation) {
        boolean removed = complete(playerId, generation);
        // 终态发布可能先释放请求身份；仍须停止尚未退出的计算线程。
        coordinator.cancel(playerId, generation);
        return removed;
    }

    public boolean complete(UUID playerId, long generation) {
        return requests.remove(new PlanningRequestKey(playerId, generation)) != null;
    }

    public boolean isCurrent(UUID playerId, long generation) {
        return generation != 0L && requests.containsKey(new PlanningRequestKey(playerId, generation));
    }

    public void submit(PlanningSnapshot snapshot, Executor serverExecutor, int maxSteps,
                       Consumer<AsyncPurePlanningService.CompletedPlan> commit,
                       Consumer<Throwable> rollback) {
        purePlanning.submit(snapshot, serverExecutor, maxSteps,
                maxSearchStates, maxMemoizedFailures,
                commit, rollback);
    }

    public void submit(PlanningSnapshot snapshot, int repeatCount, Executor serverExecutor,
                       int maxSteps, Consumer<AsyncPurePlanningService.CompletedPlan> commit,
                       Consumer<Throwable> rollback) {
        purePlanning.submit(snapshot, repeatCount, serverExecutor, maxSteps,
                maxSearchStates, maxMemoizedFailures, pureTimeoutMs, commit, rollback);
    }

    public void submitResponse(PlanningSnapshot snapshot, PlanResponseDraft draft,
                               Executor serverExecutor,
                               Predicate<PlanningSnapshot> revalidator,
                               Consumer<PlanResponse> commit,
                               Consumer<Throwable> rollback) {
        responsePlanning.submit(snapshot, draft, serverExecutor, revalidator, commit, rollback);
    }

    public void submitRouted(PlanningSnapshot snapshot, AsyncPurePlanningService.RouteInputs routing,
                             int repeatCount, Executor serverExecutor, int maxSteps,
                             Consumer<AsyncPurePlanningService.RoutedPlan> commit,
                             Consumer<Throwable> rollback) {
        purePlanning.submitRouted(snapshot, routing, repeatCount, serverExecutor, maxSteps,
                maxSearchStates, maxMemoizedFailures, pureTimeoutMs, commit, rollback);
    }

    public void submitMaxCraftable(PlanningSnapshot snapshot, int limit, Executor serverExecutor,
                                   int maxSteps, Consumer<MaxCraftableResult> commit,
                                   Consumer<Throwable> rollback) {
        PlanningProgressServer.queued(snapshot.playerId(), snapshot.requestGeneration(),
                PlanningProgressSnapshot.Phase.SEARCH);
        maxCraftablePlanning.submit(snapshot, limit, serverExecutor, maxSteps,
                maxSearchStates, maxMemoizedFailures, pureTimeoutMs,
                result -> commit.accept(new MaxCraftableResult(result.determined(), result.maximum(),
                        result.plan(), result.snapshot())), rollback);
    }

    public record MaxCraftableResult(boolean determined, int maximum,
                                     PureRecipePlanner.Result plan,
                                     PlanningSnapshot snapshot) {}


    public void forget(UUID playerId) {
        coordinator.cancel(playerId);
        requests.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public void cancelAll() {
        coordinator.cancelAll();
        requests.clear();
    }

    @Override
    public void close() {
        requests.clear();
        coordinator.close();
    }
}
