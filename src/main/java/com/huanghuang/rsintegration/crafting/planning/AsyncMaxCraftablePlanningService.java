package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.MaxCraftableSearch;
import com.huanghuang.rsintegration.crafting.plan.MaxCraftableSearch.Verdict;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Runs the entire maximum search against one immutable snapshot and one worker task. */
final class AsyncMaxCraftablePlanningService {
    private final AsyncPlanningCoordinator coordinator;

    AsyncMaxCraftablePlanningService(AsyncPlanningCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    void submit(PlanningSnapshot snapshot, int limit, Executor serverExecutor, int maxSteps,
                int maxSearchStates, int maxMemoizedFailures, int timeoutMs,
                Consumer<CompletedSearch> commit, Consumer<Throwable> rollback) {
        coordinator.submit(snapshot, ignored -> compute(snapshot, limit, maxSteps,
                        maxSearchStates, maxMemoizedFailures, timeoutMs), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(),
                commit, rollback);
    }

    static CompletedSearch compute(PlanningSnapshot snapshot, int limit, int maxSteps,
                                   int maxSearchStates, int maxMemoizedFailures) {
        return compute(snapshot, limit, maxSteps, maxSearchStates, maxMemoizedFailures,
                com.huanghuang.rsintegration.config.CraftingPlanningConfig.DEFAULT_PURE_TIMEOUT_MS);
    }

    static CompletedSearch compute(PlanningSnapshot snapshot, int limit, int maxSteps,
                                   int maxSearchStates, int maxMemoizedFailures,
                                   int timeoutMs) {
        Map<MaterialRef, Integer> stock =
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems());
        ImmutableRecipeGraph planningGraph = ImmutableRecipeGraphProjector.bindAvailability(
                snapshot.recipeGraph(), stock);
        RecipeNode target = planningGraph.recipesById().get(snapshot.recipeId());
        if (target == null) return new CompletedSearch(false, 0, null, snapshot);
        MaxCraftableSearch search = new MaxCraftableSearch(limit);
        PureRecipePlanner.Result best = null;
        long deadlineNanos = AsyncPurePlanningService.deadlineAfterMillis(
                System.nanoTime(), timeoutMs);
        OptionalInt probe;
        while ((probe = search.nextProbe()).isPresent()) {
            PlanningThreadContext.throwIfCancelled();
            int count = probe.getAsInt();
            PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                    planningGraph, stock, scale(target.inputs(), count), maxSteps,
                    maxSearchStates, maxMemoizedFailures, deadlineNanos);
            Verdict verdict = switch (result.feasibility()) {
                case FEASIBLE -> Verdict.FEASIBLE;
                case INFEASIBLE -> Verdict.INFEASIBLE;
                case UNKNOWN -> Verdict.UNKNOWN;
            };
            search.accept(count, verdict);
            if (verdict == Verdict.FEASIBLE) best = result;
        }
        if (search.isUnknown()) return new CompletedSearch(false, 0, best, snapshot);
        return new CompletedSearch(true, search.result(), best, snapshot);
    }

    private static List<IngredientRef> scale(List<IngredientRef> roots, int multiplier) {
        return roots.stream().map(root -> root.withCount(
                Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        (long) root.count() * multiplier)))).toList();
    }

    record CompletedSearch(boolean determined, int maximum, PureRecipePlanner.Result plan,
                           PlanningSnapshot snapshot) {}
}
