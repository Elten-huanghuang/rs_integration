package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns preview request generations, cancellation and background pure-plan dispatch. */
public final class PlanRequestService implements AutoCloseable {
    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
    private final AsyncPlanningCoordinator coordinator;
    private final AsyncPurePlanningService purePlanning;
    private final AsyncPlanResponseService responsePlanning;
    private final AsyncMaxCraftablePlanningService maxCraftablePlanning;
    private final int maxSearchStates;
    private final int maxMemoizedFailures;

    public PlanRequestService(int parallelism) {
        this(parallelism,
                com.huanghuang.rsintegration.config.RSIntegrationConfig
                        .DEFAULT_CRAFTING_PLANNING_QUEUE_CAPACITY);
    }

    public PlanRequestService(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity,
                com.huanghuang.rsintegration.config.RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_SEARCH_MAX_STATES,
                com.huanghuang.rsintegration.config.RSIntegrationConfig
                        .DEFAULT_CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES);
    }

    public PlanRequestService(int parallelism, int queueCapacity,
                              int maxSearchStates, int maxMemoizedFailures) {
        coordinator = new AsyncPlanningCoordinator(parallelism, queueCapacity);
        purePlanning = new AsyncPurePlanningService(coordinator);
        responsePlanning = new AsyncPlanResponseService(coordinator);
        maxCraftablePlanning = new AsyncMaxCraftablePlanningService(coordinator);
        this.maxSearchStates = Math.max(1, maxSearchStates);
        this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
    }

    public long begin(UUID playerId) {
        coordinator.cancel(playerId);
        return generations.merge(playerId, 1L, Long::sum);
    }

    public boolean isCurrent(UUID playerId, long generation) {
        return generation != 0L && java.util.Objects.equals(generations.get(playerId), generation);
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
                maxSearchStates, maxMemoizedFailures, commit, rollback);
    }

    public void submitResponse(PlanningSnapshot snapshot, PlanResponseDraft draft,
                               Executor serverExecutor,
                               Predicate<PlanningSnapshot> revalidator,
                               Consumer<PlanResponse> commit,
                               Consumer<Throwable> rollback) {
        responsePlanning.submit(snapshot, draft, serverExecutor, revalidator, commit, rollback);
    }

    public void submitMaxCraftable(PlanningSnapshot snapshot, int limit, Executor serverExecutor,
                                   int maxSteps, Consumer<MaxCraftableResult> commit,
                                   Consumer<Throwable> rollback) {
        maxCraftablePlanning.submit(snapshot, limit, serverExecutor, maxSteps,
                maxSearchStates, maxMemoizedFailures,
                result -> commit.accept(new MaxCraftableResult(result.maximum(), result.plan(),
                        result.snapshot())), rollback);
    }

    public record MaxCraftableResult(int maximum, PureRecipePlanner.Result plan,
                                     PlanningSnapshot snapshot) {}


    public void forget(UUID playerId) {
        coordinator.cancel(playerId);
        generations.remove(playerId);
    }

    public void cancelAll() {
        coordinator.cancelAll();
        generations.clear();
    }

    @Override
    public void close() {
        generations.clear();
        coordinator.close();
    }
}
