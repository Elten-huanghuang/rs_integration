package com.huanghuang.rsintegration.crafting.planning;

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
                       Consumer<PureRecipePlanner.Result> commit,
                       Consumer<Throwable> rollback) {
        if (snapshot.mainThreadOnly()) {
            serverExecutor.execute(() -> rollback.accept(
                    new PlanningThreadContext.MainThreadPlanningFallbackException("special recipe planning")));
            return;
        }
        coordinator.submit(snapshot, ignored -> compute(snapshot, maxSteps), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(), commit, rollback);
    }

    private static PureRecipePlanner.Result compute(PlanningSnapshot snapshot, int maxSteps) {
        RecipeNode target = snapshot.recipeGraph().recipesByOutput().values().stream()
                .flatMap(List::stream)
                .filter(node -> node.recipeId().equals(snapshot.recipeId()))
                .findFirst().orElse(null);
        if (target == null) {
            return new PureRecipePlanner.Result(false, List.of(), List.of(), Map.of());
        }
        Map<ImmutableRecipeGraph.MaterialRef, Integer> stock =
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems());
        return PureRecipePlanner.resolve(snapshot.recipeGraph(), stock, target.inputs(), maxSteps);
    }
}
