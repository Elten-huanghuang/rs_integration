package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Finalizes an immutable response draft away from the Minecraft server thread. */
public final class AsyncPlanResponseService {
    private final AsyncPlanningCoordinator coordinator;

    public AsyncPlanResponseService(AsyncPlanningCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void submit(PlanningSnapshot snapshot, PlanResponseDraft draft,
                       Executor serverExecutor, Predicate<PlanningSnapshot> revalidator,
                       Consumer<PlanResponse> commit, Consumer<Throwable> rollback) {
        coordinator.submit(snapshot, ignored -> {
            PlanningThreadContext.throwIfCancelled();
            return draft.toResponse();
        }, serverExecutor, revalidator, commit, rollback);
    }
}
