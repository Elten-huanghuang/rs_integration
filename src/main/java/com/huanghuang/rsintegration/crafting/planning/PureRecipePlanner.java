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

/** Pure bounded backtracking search over immutable recipe and inventory values. */
public final class PureRecipePlanner {
    private static final int MIN_SEARCH_STATES = 256;
    private static final int MAX_SEARCH_STATES = 65_536;
    private static final int DEFAULT_MAX_MEMOIZED_FAILURES = 8_192;
    private static final int MAX_SEARCH_CALL_DEPTH = 512;

    private PureRecipePlanner() {}

    public static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots, int maxSteps) {
        int searchBudget = (int) Math.max(MIN_SEARCH_STATES, Math.min(MAX_SEARCH_STATES,
                (long) Math.max(1, maxSteps) * 32L));
        return resolve(graph, available, roots, maxSteps, searchBudget,
                DEFAULT_MAX_MEMOIZED_FAILURES);
    }

    static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                DEFAULT_MAX_MEMOIZED_FAILURES);
    }

    static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures) {
        Search search = new Search(graph, available, maxSteps, maxSearchStates, maxMemoizedFailures);
        List<Task> pending = roots.stream().map(DemandTask::new).map(Task.class::cast).toList();
        Status status;
        try {
            status = search.solve(pending) ? Status.SUCCESS
                    : search.stepLimitReached ? Status.STEP_LIMIT : Status.UNRESOLVABLE;
        } catch (SearchLimitException ignored) {
            status = Status.SEARCH_LIMIT;
        }
        List<IngredientRef> missing = status == Status.SUCCESS || roots.isEmpty()
                ? List.of() : List.of(search.deepestFailure != null ? search.deepestFailure : roots.get(0));
        List<PlannedStep> resultSteps = status == Status.SUCCESS ? search.steps : List.of();
        Map<MaterialRef, Integer> resultStock = status == Status.SUCCESS ? search.stock : search.initialStock;
        return new Result(status == Status.SUCCESS, resultSteps, missing, resultStock, status,
                search.expandedStates, search.backtracks, search.memoHits);
    }

    public enum Status { SUCCESS, UNRESOLVABLE, STEP_LIMIT, SEARCH_LIMIT }

    public record PlannedStep(ResourceLocation recipeId, int batches) {}

    public record Result(boolean feasible, List<PlannedStep> steps,
                         List<IngredientRef> missing, Map<MaterialRef, Integer> remaining,
                         Status status, int expandedStates, int backtracks, int memoHits) {
        public Result(boolean feasible, List<PlannedStep> steps,
                      List<IngredientRef> missing, Map<MaterialRef, Integer> remaining) {
            this(feasible, steps, missing, remaining,
                    feasible ? Status.SUCCESS : Status.UNRESOLVABLE, 0, 0, 0);
        }

        public Result {
            steps = List.copyOf(steps);
            missing = List.copyOf(missing);
            remaining = Map.copyOf(remaining);
            if (status == null || feasible != (status == Status.SUCCESS)) {
                throw new IllegalArgumentException("inconsistent pure-plan status");
            }
        }
    }

    private sealed interface Task permits DemandTask, CompleteRecipeTask {}
    private record DemandTask(IngredientRef ingredient) implements Task {}
    private record CompleteRecipeTask(MaterialRef output, int outputCount, int consumeCount,
                                      ResourceLocation recipeId, int batches) implements Task {}
    private record FailureKey(List<Task> pending, Map<MaterialRef, Integer> stock,
                              Set<MaterialRef> resolving, int stepCount) {}

    private static final class Search {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Integer> initialStock;
        private final Map<MaterialRef, Integer> stock = new HashMap<>();
        private final int maxSteps;
        private final int maxSearchStates;
        private final int maxMemoizedFailures;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> resolving = new HashSet<>();
        private final Set<FailureKey> failedStates = new HashSet<>();
        private int expandedStates;
        private int backtracks;
        private int memoHits;
        private int deepestPending = Integer.MAX_VALUE;
        private IngredientRef deepestFailure;
        private boolean stepLimitReached;
        private int callDepth;

        private Search(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (count != null && count > 0) stock.put(material, count);
            });
            initialStock = Map.copyOf(stock);
            this.maxSteps = Math.max(1, maxSteps);
            this.maxSearchStates = Math.max(1, maxSearchStates);
            this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
        }

        private boolean solve(List<Task> pending) {
            PlanningThreadContext.throwIfCancelled();
            if (++callDepth > MAX_SEARCH_CALL_DEPTH) {
                callDepth--;
                throw new SearchLimitException();
            }
            try {
                return solveWithinDepth(pending);
            } finally {
                callDepth--;
            }
        }

        private boolean solveWithinDepth(List<Task> pending) {
            if (++expandedStates > maxSearchStates) throw new SearchLimitException();
            if (pending.isEmpty()) return true;

            FailureKey key = new FailureKey(List.copyOf(pending), Map.copyOf(stock),
                    Set.copyOf(resolving), steps.size());
            if (failedStates.contains(key)) {
                memoHits++;
                return false;
            }

            Task current = pending.get(0);
            List<Task> rest = pending.subList(1, pending.size());
            boolean solved = current instanceof DemandTask demand
                    ? solveDemand(demand.ingredient(), rest, pending.size())
                    : completeRecipe((CompleteRecipeTask) current, rest);
            if (!solved && failedStates.size() < maxMemoizedFailures) failedStates.add(key);
            return solved;
        }

        private boolean solveDemand(IngredientRef ingredient, List<Task> rest, int pendingCount) {
            for (MaterialRef alternative : ingredient.alternatives()) {
                int have = stock.getOrDefault(alternative, 0);
                if (have < ingredient.count()) continue;
                setStock(alternative, have - ingredient.count());
                if (solve(rest)) return true;
                setStock(alternative, have);
                backtracks++;
            }

            for (MaterialRef wanted : ingredient.alternatives()) {
                PlanningThreadContext.throwIfCancelled();
                if (resolving.contains(wanted)) continue;
                int have = stock.getOrDefault(wanted, 0);
                int needed = ingredient.count() - have;
                if (needed <= 0) continue;
                for (RecipeNode candidate : graph.recipesByOutput().getOrDefault(wanted, List.of())) {
                    if (steps.size() + scheduledRecipes(rest) >= maxSteps) {
                        stepLimitReached = true;
                        continue;
                    }
                    int batches = batchesFor(needed, candidate.outputCount());
                    if (batches <= 0) continue;
                    List<Task> branch = recipeBranch(candidate, batches, ingredient.count(), rest);
                    if (branch == null) continue;
                    resolving.add(wanted);
                    if (solve(branch)) return true;
                    resolving.remove(wanted);
                    backtracks++;
                }
            }
            if (pendingCount < deepestPending) {
                deepestPending = pendingCount;
                deepestFailure = ingredient;
            }
            return false;
        }

        private boolean completeRecipe(CompleteRecipeTask completed, List<Task> rest) {
            int before = stock.getOrDefault(completed.output(), 0);
            long afterProduction = (long) before
                    + (long) completed.outputCount() * completed.batches();
            if (afterProduction < completed.consumeCount() || afterProduction > Integer.MAX_VALUE) {
                return false;
            }
            resolving.remove(completed.output());
            setStock(completed.output(), (int) afterProduction - completed.consumeCount());
            steps.add(new PlannedStep(completed.recipeId(), completed.batches()));
            if (solve(rest)) return true;
            steps.remove(steps.size() - 1);
            setStock(completed.output(), before);
            resolving.add(completed.output());
            backtracks++;
            return false;
        }

        private List<Task> recipeBranch(RecipeNode candidate, int batches, int consumeCount,
                                        List<Task> rest) {
            List<Task> branch = new ArrayList<>(candidate.inputs().size() + 1 + rest.size());
            for (IngredientRef input : candidate.inputs()) {
                long scaled = (long) input.count() * batches;
                if (scaled > Integer.MAX_VALUE) return null;
                branch.add(new DemandTask(new IngredientRef(input.alternatives(), (int) scaled)));
            }
            branch.add(new CompleteRecipeTask(candidate.output(), candidate.outputCount(),
                    consumeCount, candidate.recipeId(), batches));
            branch.addAll(rest);
            return List.copyOf(branch);
        }

        private static int batchesFor(int needed, int outputCount) {
            long batches = ((long) needed + outputCount - 1L) / outputCount;
            return batches > Integer.MAX_VALUE ? -1 : (int) Math.max(1L, batches);
        }

        private static int scheduledRecipes(List<Task> pending) {
            int count = 0;
            for (Task task : pending) {
                if (task instanceof CompleteRecipeTask) count++;
            }
            return count;
        }

        private void setStock(MaterialRef material, int count) {
            if (count <= 0) stock.remove(material);
            else stock.put(material, count);
        }
    }

    private static final class SearchLimitException extends RuntimeException {
        private SearchLimitException() {
            super(null, null, false, false);
        }
    }
}
