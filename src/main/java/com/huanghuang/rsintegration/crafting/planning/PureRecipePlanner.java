package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
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
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, Long.MAX_VALUE);
    }

    static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos) {
        Search search = new Search(graph, available, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos);
        List<Task> pending = PureDemandNormalizer.mergeEquivalent(roots).stream()
                .map(DemandTask::new).map(Task.class::cast).toList();
        Status status;
        try {
            status = search.solve(pending) ? Status.SUCCESS
                    : search.stepLimitReached ? Status.STEP_LIMIT : Status.UNRESOLVABLE;
        } catch (SearchLimitException ignored) {
            status = Status.SEARCH_LIMIT;
        } catch (TimeLimitException ignored) {
            status = Status.TIME_LIMIT;
        }
        List<IngredientRef> missing = status == Status.SUCCESS || roots.isEmpty()
                ? List.of() : List.of(search.deepestFailure != null ? search.deepestFailure : roots.get(0));
        List<PlannedStep> resultSteps = status == Status.SUCCESS ? search.steps : List.of();
        Map<MaterialRef, Integer> resultStock = status == Status.SUCCESS ? search.stock : search.initialStock;
        return new Result(Feasibility.from(status), resultSteps, missing, resultStock, status,
                search.expandedStates, search.backtracks, search.memoHits);
    }

    public enum Status { SUCCESS, UNRESOLVABLE, STEP_LIMIT, SEARCH_LIMIT, TIME_LIMIT }

    public enum Feasibility {
        FEASIBLE,
        INFEASIBLE,
        UNKNOWN;

        static Feasibility from(Status status) {
            return switch (status) {
                case SUCCESS -> FEASIBLE;
                case UNRESOLVABLE -> INFEASIBLE;
                case STEP_LIMIT, SEARCH_LIMIT, TIME_LIMIT -> UNKNOWN;
            };
        }
    }

    public record PlannedStep(ResourceLocation recipeId, int batches) {}

    public record Result(Feasibility feasibility, List<PlannedStep> steps,
                         List<IngredientRef> missing, Map<MaterialRef, Integer> remaining,
                         Status status, int expandedStates, int backtracks, int memoHits) {
        public Result(boolean feasible, List<PlannedStep> steps,
                      List<IngredientRef> missing, Map<MaterialRef, Integer> remaining) {
            this(feasible ? Feasibility.FEASIBLE : Feasibility.INFEASIBLE,
                    steps, missing, remaining,
                    feasible ? Status.SUCCESS : Status.UNRESOLVABLE, 0, 0, 0);
        }

        public boolean feasible() {
            return feasibility == Feasibility.FEASIBLE;
        }

        public Result {
            steps = List.copyOf(steps);
            missing = List.copyOf(missing);
            remaining = Map.copyOf(remaining);
            if (status == null || feasibility == null || feasibility != Feasibility.from(status)) {
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
        private final long deadlineNanos;
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
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                       long deadlineNanos) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (count != null && count > 0) stock.put(material, count);
            });
            initialStock = Map.copyOf(stock);
            this.maxSteps = Math.max(1, maxSteps);
            this.maxSearchStates = Math.max(1, maxSearchStates);
            this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
            this.deadlineNanos = deadlineNanos;
        }

        private boolean solve(List<Task> pending) {
            PlanningThreadContext.throwIfCancelled();
            if (deadlineNanos != Long.MAX_VALUE
                    && System.nanoTime() - deadlineNanos >= 0L) {
                throw new TimeLimitException();
            }
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
            List<MaterialRef> orderedAlternatives = inventoryFirst(ingredient.alternatives());
            for (MaterialRef alternative : orderedAlternatives) {
                int have = stock.getOrDefault(alternative, 0);
                if (have < ingredient.count()) continue;
                setStock(alternative, have - ingredient.count());
                if (solve(rest)) return true;
                setStock(alternative, have);
                backtracks++;
            }

            Map<MaterialRef, Integer> combinedConsumption = consumeAcrossAlternatives(
                    ingredient, rest);
            if (combinedConsumption != null) {
                if (solve(rest)) return true;
                combinedConsumption.forEach(this::setStock);
                backtracks++;
            }

            for (MaterialRef wanted : orderedAlternatives) {
                PlanningThreadContext.throwIfCancelled();
                if (resolving.contains(wanted)) continue;
                int have = stock.getOrDefault(wanted, 0);
                int needed = ingredient.count() - have;
                if (needed <= 0) continue;
                for (RecipeNode candidate : inventoryFirstCandidates(wanted)) {
                    if (steps.size() + scheduledRecipes(rest) >= maxSteps) {
                        stepLimitReached = true;
                        continue;
                    }
                    int selfConsumed = selfConsumedPerBatch(candidate, wanted);
                    int netGain = candidate.outputCount() - selfConsumed;
                    int batches = batchesFor(needed,
                            selfConsumed > 0 ? netGain : candidate.outputCount());
                    if (batches <= 0) continue;
                    if (selfConsumed == 0
                            && isUnseededReverseConversion(candidate, wanted, batches)) {
                        continue;
                    }
                    int scheduledBatches = batches;
                    int consumeCount = ingredient.count();
                    List<Task> continuation = rest;
                    if (selfConsumed > 0) {
                        if (netGain <= 0) continue;
                        scheduledBatches = Math.min(batches, have / selfConsumed);
                        if (scheduledBatches <= 0) continue;
                        consumeCount = 0;
                        continuation = new ArrayList<>(rest.size() + 1);
                        continuation.add(new DemandTask(ingredient));
                        continuation.addAll(rest);
                    }
                    List<Task> branch = recipeBranch(candidate, scheduledBatches,
                            consumeCount, continuation);
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

        /**
         * A tag ingredient is satisfied by the aggregate stock of all matching variants.
         * Keep singleton demands in the remaining task list reserved where possible so a
         * broad tag (for example wool or fuels) does not consume a later exact material.
         */
        private Map<MaterialRef, Integer> consumeAcrossAlternatives(
                IngredientRef ingredient, List<Task> rest) {
            List<MaterialRef> stocked = ingredient.alternatives().stream()
                    .filter(material -> stock.getOrDefault(material, 0) > 0)
                    .sorted(Comparator
                            .comparingLong((MaterialRef material) ->
                                    (long) stock.getOrDefault(material, 0)
                                            - singletonDemand(rest, material))
                            .reversed()
                            .thenComparingInt(material -> -stock.getOrDefault(material, 0)))
                    .toList();
            if (stocked.size() < 2) return null;

            long total = 0L;
            for (MaterialRef material : stocked) {
                total += stock.getOrDefault(material, 0);
                if (total >= ingredient.count()) break;
            }
            if (total < ingredient.count()) return null;

            int remaining = ingredient.count();
            int contributors = 0;
            Map<MaterialRef, Integer> previous = new HashMap<>();
            for (MaterialRef material : stocked) {
                int have = stock.getOrDefault(material, 0);
                int take = Math.min(have, remaining);
                if (take <= 0) continue;
                previous.put(material, have);
                setStock(material, have - take);
                remaining -= take;
                contributors++;
                if (remaining == 0) break;
            }
            if (contributors > 1) return previous;
            previous.forEach(this::setStock);
            return null;
        }

        private static long singletonDemand(List<Task> tasks, MaterialRef material) {
            long demand = 0L;
            for (Task task : tasks) {
                if (!(task instanceof DemandTask needed)
                        || needed.ingredient().alternatives().size() != 1
                        || !needed.ingredient().alternatives().get(0).equals(material)) continue;
                demand = Math.min(Integer.MAX_VALUE,
                        demand + needed.ingredient().count());
            }
            return demand;
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
            List<IngredientRef> inputs = PureDemandNormalizer.mergeEquivalent(candidate.inputs());
            List<Task> branch = new ArrayList<>(inputs.size() + 1 + rest.size());
            for (IngredientRef input : inputs) {
                long scaled = (long) input.count() * batches;
                if (scaled > Integer.MAX_VALUE) return null;
                branch.add(new DemandTask(new IngredientRef(input.alternatives(), (int) scaled)));
            }
            branch.add(new CompleteRecipeTask(candidate.output(), candidate.outputCount(),
                    consumeCount, candidate.recipeId(), batches));
            branch.addAll(rest);
            return List.copyOf(branch);
        }

        /**
         * Broad tag ingredients can expose hundreds of variants. Variants already present in
         * inventory are the best recursive production seed and are attempted before absent ones.
         */
        private List<MaterialRef> inventoryFirst(List<MaterialRef> alternatives) {
            if (alternatives.size() < 2) return alternatives;
            List<MaterialRef> ordered = new ArrayList<>(alternatives.size());
            for (MaterialRef material : alternatives) {
                if (stock.getOrDefault(material, 0) > 0) ordered.add(material);
            }
            if (ordered.isEmpty() || ordered.size() == alternatives.size()) return alternatives;
            for (MaterialRef material : alternatives) {
                if (stock.getOrDefault(material, 0) <= 0) ordered.add(material);
            }
            return ordered;
        }

        private List<RecipeNode> inventoryFirstCandidates(MaterialRef wanted) {
            List<RecipeNode> candidates = graph.recipesByOutput()
                    .getOrDefault(wanted, List.of());
            if (candidates.size() < 2) return candidates;
            List<RecipeNode> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator.comparingDouble(this::inputStockCoverage).reversed());
            return ordered;
        }

        private double inputStockCoverage(RecipeNode candidate) {
            long required = 0L;
            long covered = 0L;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                required += input.count();
                covered += Math.min(input.count(), stockAcross(input));
            }
            return required <= 0L ? 1.0D : (double) covered / (double) required;
        }

        /**
         * Reject an unseeded decompression branch when every producer of one of its inputs
         * directly consumes the material currently being resolved. This removes A -> B -> A
         * compression loops while preserving conversions backed by inventory.
         */
        private boolean isUnseededReverseConversion(RecipeNode candidate, MaterialRef wanted,
                                                    int batches) {
            if (stock.getOrDefault(wanted, 0) > 0) return false;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                long required = (long) input.count() * batches;
                if (required <= stockAcross(input)) continue;
                boolean sawProducer = false;
                boolean reverseOnly = true;
                for (MaterialRef alternative : input.alternatives()) {
                    List<RecipeNode> producers = graph.recipesByOutput()
                            .getOrDefault(alternative, List.of());
                    if (producers.isEmpty()) {
                        reverseOnly = false;
                        break;
                    }
                    sawProducer = true;
                    if (producers.stream().anyMatch(producer ->
                            producer.inputs().stream().noneMatch(ingredient ->
                                    ingredient.alternatives().contains(wanted)))) {
                        reverseOnly = false;
                        break;
                    }
                }
                if (sawProducer && reverseOnly) return true;
            }
            return false;
        }

        private long stockAcross(IngredientRef ingredient) {
            long total = 0L;
            for (MaterialRef alternative : ingredient.alternatives()) {
                total += stock.getOrDefault(alternative, 0);
                if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
            return total;
        }

        private static int batchesFor(int needed, int outputCount) {
            if (outputCount <= 0) return -1;
            long batches = ((long) needed + outputCount - 1L) / outputCount;
            return batches > Integer.MAX_VALUE ? -1 : (int) Math.max(1L, batches);
        }

        private static int selfConsumedPerBatch(RecipeNode candidate, MaterialRef output) {
            long consumed = 0;
            for (IngredientRef input : candidate.inputs()) {
                if (input.alternatives().contains(output)) {
                    consumed += input.count();
                    if (consumed > Integer.MAX_VALUE) return Integer.MAX_VALUE;
                }
            }
            return (int) consumed;
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

    private static final class TimeLimitException extends RuntimeException {
        private TimeLimitException() {
            super(null, null, false, false);
        }
    }
}
