package com.huanghuang.rsintegration.crafting.planning;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns preview request generations, cancellation and background pure-plan dispatch. */
public final class PlanRequestService implements AutoCloseable {
    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
    private final AsyncPlanningCoordinator coordinator;
    private final AsyncPurePlanningService purePlanning;

    public PlanRequestService(int parallelism) {
        coordinator = new AsyncPlanningCoordinator(parallelism);
        purePlanning = new AsyncPurePlanningService(coordinator);
    }

    public long begin(UUID playerId) {
        coordinator.cancel(playerId);
        return generations.merge(playerId, 1L, Long::sum);
    }

    public boolean isCurrent(UUID playerId, long generation) {
        return generation != 0L && java.util.Objects.equals(generations.get(playerId), generation);
    }

    public void submit(PlanningSnapshot snapshot, Executor serverExecutor, int maxSteps,
                       Consumer<PureRecipePlanner.Result> commit, Consumer<Throwable> rollback) {
        purePlanning.submit(snapshot, serverExecutor, maxSteps, commit, rollback);
    }

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
