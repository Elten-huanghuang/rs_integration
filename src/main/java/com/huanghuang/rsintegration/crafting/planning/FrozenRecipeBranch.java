package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.util.PerformanceMonitor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 一次候选试算的值快照。库存只包含这个分支能访问的精确变体。 */
public record FrozenRecipeBranch(Map<MaterialRef, List<RecipeNode>> producers,
                                 Map<MaterialRef, Long> stock, IngredientRef target,
                                 int maxSteps, int maxStates) {
    public FrozenRecipeBranch {
        producers = producers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        stock = Map.copyOf(stock);
    }

    public record Candidate(List<PureRecipePlanner.PlannedStep> steps, Map<MaterialRef, Long> remaining) {
        public Candidate {
            steps = List.copyOf(steps);
            remaining = Map.copyOf(remaining);
        }
    }

    public interface Dispatcher {
        /** 完成回调在服务端线程调用，空值表示该分支回到原来的候选。 */
        void submit(FrozenRecipeBranch branch, Consumer<Candidate> completed);
    }

    /** 单个原版网格大小、无替代搜索的静态配方可以使用窗口余额。 */
    boolean fitsShortSlice() {
        if (maxSteps != 1 || producers.size() != 1 || stock.size() > 9 || target.alternatives().size() != 1) return false;
        var recipes = producers.get(target.alternatives().get(0));
        if (recipes == null || recipes.size() != 1) return false;
        var recipe = recipes.get(0);
        return recipe.guaranteedOutputs().size() == 1 && recipe.magicCosts().isEmpty()
                && !recipe.dynamicIdentityTargetable() && !recipe.hasFluidOutputs()
                && recipe.inputs().size() <= 9
                && recipe.inputs().stream().allMatch(input -> input.alternatives().size() == 1);
    }

    public Candidate solve() {
        long start = System.nanoTime();
        try {
            var graph = new ImmutableRecipeGraph(producers);
            var result = DeterministicRecipePlanner.tryPlan(graph, stock, List.of(target),
                    Set.of(), Map.of(), PlanningPreferences.empty(),
                    maxSteps, maxStates, PlanningThreadContext::throwIfCancelled);
            return result == null ? null : new Candidate(result.steps(), result.remaining());
        } catch (ArithmeticException | DeterministicRecipePlanner.ProbeLimitReached unsupported) {
            return null;
        } finally {
            PerformanceMonitor.recordPlanningLatency(PerformanceMonitor.PlanningLatencyPhase.HYBRID_WORKER,
                    System.nanoTime() - start);
        }
    }
}
