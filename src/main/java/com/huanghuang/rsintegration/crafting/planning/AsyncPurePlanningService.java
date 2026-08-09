package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Executes the value-only planner and hands its result back to the server executor. */
public final class AsyncPurePlanningService {
    private final AsyncPlanningCoordinator coordinator;

    public AsyncPurePlanningService(AsyncPlanningCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void submit(PlanningSnapshot snapshot, Executor serverExecutor, int maxSteps,
                       Consumer<CompletedPlan> commit,
                       Consumer<Throwable> rollback) {
        submit(snapshot, 1, serverExecutor, maxSteps,
                CraftingPlanningConfig.DEFAULT_SEARCH_STATES,
                CraftingPlanningConfig.DEFAULT_MEMOIZED_FAILURES,
                CraftingPlanningConfig.DEFAULT_PURE_TIMEOUT_MS, commit, rollback);
    }

    public void submit(PlanningSnapshot snapshot, Executor serverExecutor, int maxSteps,
                       int maxSearchStates, int maxMemoizedFailures,
                       Consumer<CompletedPlan> commit, Consumer<Throwable> rollback) {
        submit(snapshot, 1, serverExecutor, maxSteps, maxSearchStates,
                maxMemoizedFailures, CraftingPlanningConfig.DEFAULT_PURE_TIMEOUT_MS,
                commit, rollback);
    }

    public void submit(PlanningSnapshot snapshot, int repeatCount, Executor serverExecutor,
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                       int timeoutMs,
                       Consumer<CompletedPlan> commit, Consumer<Throwable> rollback) {
        if (snapshot.mainThreadOnly()) {
            serverExecutor.execute(() -> rollback.accept(
                    new PlanningThreadContext.MainThreadPlanningFallbackException("special recipe planning")));
            return;
        }
        coordinator.submit(snapshot, ignored -> compute(snapshot, repeatCount, maxSteps,
                        maxSearchStates, maxMemoizedFailures, timeoutMs), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(),
                result -> commit.accept(new CompletedPlan(snapshot, result)), rollback);
    }

    private static PureRecipePlanner.Result compute(PlanningSnapshot snapshot, int repeatCount,
                                                     int maxSteps,
                                                     int maxSearchStates, int maxMemoizedFailures,
                                                     int timeoutMs) {
        PlanningThreadContext.throwIfCancelled();
        RecipeNode target = snapshot.recipeGraph().recipesById().get(snapshot.recipeId());
        if (target == null) {
            return new PureRecipePlanner.Result(false, List.of(), List.of(), Map.of());
        }
        Map<ImmutableRecipeGraph.MaterialRef, Integer> stock =
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems());
        List<IngredientRef> roots = target.inputs().stream()
                .map(root -> new IngredientRef(root.alternatives(),
                        Math.toIntExact(Math.min(Integer.MAX_VALUE,
                                (long) root.count() * Math.max(1, repeatCount)))))
                .toList();
        long searchStarted = System.nanoTime();
        long deadlineNanos = deadlineAfterMillis(searchStarted, timeoutMs);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                snapshot.recipeGraph(), stock, roots, maxSteps,
                maxSearchStates, maxMemoizedFailures, deadlineNanos);
        com.huanghuang.rsintegration.command.PerformanceMonitor.recordPurePlanningSearch(
                result, System.nanoTime() - searchStarted);
        return result;
    }

    static long deadlineAfterMillis(long startedNanos, int timeoutMs) {
        long budgetNanos = Math.max(1L, timeoutMs) * 1_000_000L;
        long deadline = startedNanos + budgetNanos;
        return deadline < startedNanos ? Long.MAX_VALUE : deadline;
    }

    /** Keeps a background result inseparable from the immutable state that produced it. */
    public record CompletedPlan(PlanningSnapshot snapshot, PureRecipePlanner.Result result) {}
}
