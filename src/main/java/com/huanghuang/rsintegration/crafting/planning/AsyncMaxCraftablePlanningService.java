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
                int maxSearchStates, int maxMemoizedFailures,
                Consumer<CompletedSearch> commit, Consumer<Throwable> rollback) {
        coordinator.submit(snapshot, ignored -> compute(snapshot, limit, maxSteps,
                        maxSearchStates, maxMemoizedFailures), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(),
                commit, rollback);
    }

    static CompletedSearch compute(PlanningSnapshot snapshot, int limit, int maxSteps,
                                   int maxSearchStates, int maxMemoizedFailures) {
        RecipeNode target = snapshot.recipeGraph().recipesById().get(snapshot.recipeId());
        if (target == null) return new CompletedSearch(false, 0, null, snapshot);
        Map<MaterialRef, Integer> stock =
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems());
        MaxCraftableSearch search = new MaxCraftableSearch(limit);
        PureRecipePlanner.Result best = null;
        OptionalInt probe;
        while ((probe = search.nextProbe()).isPresent()) {
            PlanningThreadContext.throwIfCancelled();
            int count = probe.getAsInt();
            PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                    snapshot.recipeGraph(), stock, scale(target.inputs(), count), maxSteps,
                    maxSearchStates, maxMemoizedFailures);
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
        return roots.stream().map(root -> new IngredientRef(root.alternatives(),
                Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        (long) root.count() * multiplier)))).toList();
    }

    record CompletedSearch(boolean determined, int maximum, PureRecipePlanner.Result plan,
                           PlanningSnapshot snapshot) {}
}
