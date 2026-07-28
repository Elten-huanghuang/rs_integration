package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure recursive calculation over immutable values. */
public final class PureRecipePlanner {
    private PureRecipePlanner() {}

    public static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots, int maxSteps) {
        State state = new State(graph, new HashMap<>(available), maxSteps);
        List<IngredientRef> missing = new ArrayList<>();
        for (IngredientRef root : roots) {
            if (!state.ensure(root)) missing.add(root);
        }
        return new Result(missing.isEmpty(), state.steps, missing, state.stock);
    }

    public record PlannedStep(ResourceLocation recipeId, int batches) {}
    public record Result(boolean feasible, List<PlannedStep> steps,
                         List<IngredientRef> missing, Map<MaterialRef, Integer> remaining) {
        public Result {
            steps = List.copyOf(steps);
            missing = List.copyOf(missing);
            remaining = Map.copyOf(remaining);
        }
    }

    private static final class State {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Integer> stock;
        private final int maxSteps;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> resolving = new HashSet<>();

        private State(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> stock, int maxSteps) {
            this.graph = graph;
            this.stock = stock;
            this.maxSteps = Math.max(1, maxSteps);
        }

        private boolean ensure(IngredientRef ingredient) {
            MaterialRef stocked = ingredient.alternatives().stream()
                    .filter(key -> stock.getOrDefault(key, 0) >= ingredient.count()).findFirst().orElse(null);
            if (stocked != null) {
                stock.merge(stocked, -ingredient.count(), Integer::sum);
                return true;
            }
            for (MaterialRef wanted : ingredient.alternatives()) {
                int have = stock.getOrDefault(wanted, 0);
                int needed = ingredient.count() - have;
                for (RecipeNode candidate : graph.recipesByOutput().getOrDefault(wanted, List.of())) {
                    if (steps.size() >= maxSteps || !resolving.add(wanted)) continue;
                    Map<MaterialRef, Integer> stockBefore = new HashMap<>(stock);
                    int stepsBefore = steps.size();
                    int batches = Math.max(1, (needed + candidate.outputCount() - 1) / candidate.outputCount());
                    boolean ok = true;
                    for (IngredientRef input : candidate.inputs()) {
                        IngredientRef scaled = new IngredientRef(input.alternatives(), input.count() * batches);
                        if (!ensure(scaled)) { ok = false; break; }
                    }
                    resolving.remove(wanted);
                    if (!ok) {
                        stock.clear(); stock.putAll(stockBefore);
                        while (steps.size() > stepsBefore) steps.remove(steps.size() - 1);
                        continue;
                    }
                    stock.merge(wanted, candidate.outputCount() * batches, Integer::sum);
                    steps.add(new PlannedStep(candidate.recipeId(), batches));
                    stock.merge(wanted, -ingredient.count(), Integer::sum);
                    return true;
                }
            }
            return false;
        }
    }
}
