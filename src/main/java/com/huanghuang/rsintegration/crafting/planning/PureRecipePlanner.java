package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Pure bounded backtracking search over immutable recipe and inventory values. */
public final class PureRecipePlanner {
    private static final int MIN_SEARCH_STATES = 256;
    private static final int MAX_SEARCH_STATES = 65_536;
    private static final int DEFAULT_MAX_MEMOIZED_FAILURES = 8_192;
    private static final int MAX_SEARCH_CALL_DEPTH = 512;
    private static final int MAX_REPORTED_MISSING = 64;
    private static final int MAX_DIAGNOSTIC_OPERATIONS = 32_768;

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
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, System::nanoTime);
    }

    static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos,
                          LongSupplier nanoTime) {
        List<IngredientRef> normalizedRoots = PureDemandNormalizer.mergeEquivalent(roots);
        Search search = new Search(graph, available, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, nanoTime);
        List<Task> pending = normalizedRoots.stream()
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
        IngredientRef unresolved = search.deepestFailure != null
                ? search.deepestFailure : (roots.isEmpty() ? null : roots.get(0));
        List<IngredientRef> missing = status == Status.SUCCESS || unresolved == null
                ? List.of() : List.of(unresolved);
        List<PlannedStep> resultSteps = switch (status) {
            case SUCCESS -> search.steps;
            case UNRESOLVABLE -> search.bestFailureSteps;
            case STEP_LIMIT, SEARCH_LIMIT, TIME_LIMIT -> List.of();
        };
        if ((status == Status.UNRESOLVABLE || status == Status.TIME_LIMIT) && !roots.isEmpty()) {
            PartialPlanBuilder partialBuilder = new PartialPlanBuilder(
                    graph, available, maxSteps,
                    status == Status.UNRESOLVABLE
                            ? search::checkBudget
                            : new DiagnosticBudget(MAX_DIAGNOSTIC_OPERATIONS));
            try {
                PartialTrace partial = partialBuilder.build(normalizedRoots);
                if (!partial.missing().isEmpty()
                        && partial.steps().size() >= resultSteps.size()) {
                    missing = partial.missing();
                    resultSteps = partial.steps();
                }
            } catch (TimeLimitException ignored) {
                // Feasibility is already known. Keep the search trace when the optional
                // display-oriented partial expansion exhausts the remaining time budget.
            } catch (DiagnosticLimitException ignored) {
                PartialTrace partial = partialBuilder.snapshot();
                if (!partial.missing().isEmpty()) {
                    missing = partial.missing();
                    resultSteps = partial.steps();
                }
            }
        }
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

    private static long scaledInputCount(IngredientRef input, long batches) {
        return input.role() == DemandRole.CATALYST
                ? input.count() : (long) input.count() * batches;
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
        private final LongSupplier nanoTime;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> resolving = new HashSet<>();
        private final Set<FailureKey> failedStates = new HashSet<>();
        private final SeededReachability reachability;
        private final Map<List<MaterialRef>, BroadFamilyAnalysis> broadFamilyAnalyses =
                new HashMap<>();
        private static final long FAMILY_COST_UNKNOWN = Long.MAX_VALUE / 4L;
        private int expandedStates;
        private int backtracks;
        private int memoHits;
        private IngredientRef deepestFailure;
        private int bestFailureDepth = -1;
        private int bestFailureStepCount = -1;
        private List<PlannedStep> bestFailureSteps = List.of();
        private boolean stepLimitReached;
        private int callDepth;

        private Search(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                       long deadlineNanos, LongSupplier nanoTime) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (count != null && count > 0) stock.put(material, count);
            });
            initialStock = Map.copyOf(stock);
            this.maxSteps = Math.max(1, maxSteps);
            this.maxSearchStates = Math.max(1, maxSearchStates);
            this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
            this.deadlineNanos = deadlineNanos;
            this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
            this.reachability = new SeededReachability(graph, available, this::checkBudget);
        }

        private boolean solve(List<Task> pending) {
            checkBudget();
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
                    ? solveDemand(demand.ingredient(), rest, pending)
                    : completeRecipe((CompleteRecipeTask) current, rest);
            if (!solved && failedStates.size() < maxMemoizedFailures) failedStates.add(key);
            return solved;
        }

        private boolean solveDemand(IngredientRef ingredient, List<Task> rest,
                                    List<Task> pending) {
            boolean catalyst = ingredient.role() == DemandRole.CATALYST;
            List<MaterialRef> orderedAlternatives = inventoryFirst(ingredient.alternatives());
            if (catalyst) {
                if (stockAcross(ingredient) >= ingredient.count()) {
                    if (solve(rest)) return true;
                    backtracks++;
                }
            } else {
                for (MaterialRef alternative : orderedAlternatives) {
                    int have = stock.getOrDefault(alternative, 0);
                    if (have < ingredient.count()) continue;
                    setStock(alternative, have - ingredient.count());
                    if (solve(rest)) return true;
                    setStock(alternative, have);
                    backtracks++;
                }
            }

            Map<MaterialRef, Integer> combinedConsumption = catalyst ? null
                    : consumeAcrossAlternatives(ingredient, rest);
            if (combinedConsumption != null) {
                if (solve(rest)) return true;
                combinedConsumption.forEach(this::setStock);
                backtracks++;
            }

            for (MaterialRef wanted : orderedAlternatives) {
                checkBudget();
                if (resolving.contains(wanted)) continue;
                int have = stock.getOrDefault(wanted, 0);
                long present = catalyst ? stockAcross(ingredient) : have;
                int needed = (int) Math.max(0L, (long) ingredient.count() - present);
                if (needed <= 0) continue;
                boolean broadFamily = ingredient.alternatives().size() > 1;
                if (!broadFamily && !reachability.canReach(wanted)) continue;
                for (RecipeNode candidate : inventoryFirstCandidates(wanted, !broadFamily)) {
                    if (steps.size() + scheduledRecipes(rest) >= maxSteps) {
                        stepLimitReached = true;
                        continue;
                    }
                    int selfConsumed = selfConsumedPerBatch(candidate, wanted);
                    int netGain = candidate.outputCount() - selfConsumed;
                    int batches = batchesFor(needed,
                            selfConsumed > 0 ? netGain : candidate.outputCount());
                    if (batches <= 0) continue;
                    if (isNonProductiveBroadFamilyConversion(
                            candidate, ingredient.alternatives(), batches)) continue;
                    // Broad tags are pruned by family gain before reachability. In large
                    // modpacks, probing all log/chest variants first can traverse most of the
                    // recipe graph even though their recipes are only neutral conversion rings.
                    if (broadFamily && !reachability.canReach(candidate)) continue;
                    if (selfConsumed == 0
                            && isUnseededReverseConversion(candidate, wanted, batches)) {
                        continue;
                    }
                    int scheduledBatches = batches;
                    int consumeCount = catalyst ? 0 : ingredient.count();
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
            recordFailure(ingredient, pending);
            return false;
        }

        private void recordFailure(IngredientRef ingredient, List<Task> pending) {
            int depth = resolving.size();
            List<PlannedStep> partial = new ArrayList<>(steps);
            for (Task task : pending) {
                if (task instanceof CompleteRecipeTask completion) {
                    partial.add(new PlannedStep(completion.recipeId(), completion.batches()));
                }
            }
            if (depth < bestFailureDepth
                    || (depth == bestFailureDepth && partial.size() <= bestFailureStepCount)) {
                return;
            }
            bestFailureDepth = depth;
            bestFailureStepCount = partial.size();
            bestFailureSteps = List.copyOf(partial);
            deepestFailure = ingredient;
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
                long scaled = scaledInputCount(input, batches);
                if (scaled > Integer.MAX_VALUE) return null;
                branch.add(new DemandTask(input.withCount((int) scaled)));
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
            List<MaterialRef> ordered = new ArrayList<>(alternatives);
            // Do not probe reachability for every member just to sort a broad tag.
            // A large tag (logs, chests, fuels, ...) can contain hundreds of entries;
            // reachability is lazy and will be queried only as each branch is visited.
            ordered.sort(Comparator.comparingInt(this::alternativeRank));
            return ordered;
        }

        private int alternativeRank(MaterialRef material) {
            if (stock.getOrDefault(material, 0) > 0) return 0;
            return 1;
        }

        private List<RecipeNode> inventoryFirstCandidates(MaterialRef wanted, boolean pruneUnseeded) {
            List<RecipeNode> candidates = graph.recipesByOutput()
                    .getOrDefault(wanted, List.of());
            List<RecipeNode> ordered = candidates.stream()
                    .filter(candidate -> !pruneUnseeded || reachability.canReach(candidate))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (ordered.size() < 2) return ordered;
            Comparator<RecipeNode> coverage = Comparator
                    .comparingDouble(this::inputStockCoverage).reversed();
            // Avoid making the sort itself perform a reachability walk for every recipe of a
            // broad tag. Those candidates are checked lazily after the family-gain guard.
            ordered.sort(pruneUnseeded
                    ? coverage.thenComparingInt(reachability::depth)
                    : coverage);
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
                if (input.role() == DemandRole.CATALYST) continue;
                long required = scaledInputCount(input, batches);
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

        /**
         * Rejects neutral compression/conversion rings while resolving a broad tag. For example,
         * log -> board -> another log has no net gain in the logs family and should not be tried
         * once the aggregate log stock is short. A genuinely amplifying recipe (one family item
         * producing two) remains eligible. Missing non-family leaves are treated as zero-cost so
         * the normal planner can still report them as missing instead of this guard hiding them.
         */
        private boolean isNonProductiveBroadFamilyConversion(
                RecipeNode candidate, List<MaterialRef> familyAlternatives, int batches) {
            if (familyAlternatives.size() < 2
                    || !familyAlternatives.contains(candidate.output())) return false;
            long produced = (long) candidate.outputCount() * batches;
            if (produced <= 0L) return false;

            List<MaterialRef> familyKey = List.copyOf(familyAlternatives);
            BroadFamilyAnalysis analysis = broadFamilyAnalyses.computeIfAbsent(
                    familyKey, BroadFamilyAnalysis::new);
            long consumed = 0L;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                if (input.role() == DemandRole.CATALYST) continue;
                long required = scaledInputCount(input, batches);
                if (required <= 0L) continue;
                long cost = analysis.costForIngredient(input, required, new HashSet<>());
                if (cost >= FAMILY_COST_UNKNOWN) return false;
                consumed = Math.min(FAMILY_COST_UNKNOWN, consumed + cost);
                if (consumed >= produced) return true;
            }
            return consumed >= produced;
        }

        private record FamilyCostKey(MaterialRef material, long quantity) {}

        /** Inventory-independent family cost, shared by every candidate of one broad tag. */
        private final class BroadFamilyAnalysis {
            private final Set<MaterialRef> family;
            private final Map<FamilyCostKey, Long> memo = new HashMap<>();

            private BroadFamilyAnalysis(List<MaterialRef> family) {
                this.family = Set.copyOf(family);
            }

            private long costForIngredient(IngredientRef input, long quantity,
                                           Set<MaterialRef> visiting) {
                checkBudget();
                long best = FAMILY_COST_UNKNOWN;
                for (MaterialRef alternative : input.alternatives()) {
                    best = Math.min(best, costForMaterial(alternative, quantity, visiting));
                    if (best == 0L) break;
                }
                return best;
            }

            private long costForMaterial(MaterialRef material, long quantity,
                                         Set<MaterialRef> visiting) {
                checkBudget();
                if (quantity <= 0L) return 0L;
                if (family.contains(material)) return quantity;

                FamilyCostKey key = new FamilyCostKey(material, quantity);
                Long cached = memo.get(key);
                if (cached != null) return cached;
                if (!visiting.add(material)) return FAMILY_COST_UNKNOWN;

                long best = FAMILY_COST_UNKNOWN;
                try {
                    List<RecipeNode> producers = graph.recipesByOutput()
                            .getOrDefault(material, List.of());
                    // A leaf outside the family is an external cost, not consumed family stock.
                    if (producers.isEmpty()) {
                        memo.put(key, 0L);
                        return 0L;
                    }
                    for (RecipeNode producer : producers) {
                        long producerBatches = (quantity + producer.outputCount() - 1L)
                                / producer.outputCount();
                        if (producerBatches <= 0L || producerBatches > Integer.MAX_VALUE) continue;
                        long cost = 0L;
                        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                                producer.inputs())) {
                            if (input.role() == DemandRole.CATALYST) continue;
                            long scaled = scaledInputCount(input, producerBatches);
                            if (scaled <= 0L || scaled >= FAMILY_COST_UNKNOWN) {
                                cost = FAMILY_COST_UNKNOWN;
                                break;
                            }
                            long inputCost = costForIngredient(input, scaled, visiting);
                            cost = Math.min(FAMILY_COST_UNKNOWN, cost + inputCost);
                            if (cost >= FAMILY_COST_UNKNOWN) break;
                        }
                        best = Math.min(best, cost);
                        if (best == 0L) break;
                    }
                } finally {
                    visiting.remove(material);
                }
                if (best < FAMILY_COST_UNKNOWN) memo.put(key, best);
                return best;
            }
        }

        private void checkBudget() {
            PlanningThreadContext.throwIfCancelled();
            if (deadlineNanos != Long.MAX_VALUE
                    && nanoTime.getAsLong() - deadlineNanos >= 0L) {
                throw new TimeLimitException();
            }
        }

        private static int batchesFor(int needed, int outputCount) {
            if (outputCount <= 0) return -1;
            long batches = ((long) needed + outputCount - 1L) / outputCount;
            return batches > Integer.MAX_VALUE ? -1 : (int) Math.max(1L, batches);
        }

        private static int selfConsumedPerBatch(RecipeNode candidate, MaterialRef output) {
            long consumed = 0;
            for (IngredientRef input : candidate.inputs()) {
                if (input.role() != DemandRole.CATALYST
                        && input.alternatives().contains(output)) {
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

    /** Builds a deterministic partial recipe trace after feasibility search finds raw shortages. */
    private static final class PartialPlanBuilder {
        private static final long COST_UNKNOWN = Long.MAX_VALUE / 4L;

        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Integer> stock = new HashMap<>();
        private final int maxSteps;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final Map<List<MaterialRef>, PartialFamilyCost> familyCosts = new HashMap<>();
        private final Runnable budgetCheck;
        private final Map<MissingKey, IngredientRef> missing = new LinkedHashMap<>();

        private PartialPlanBuilder(ImmutableRecipeGraph graph,
                                   Map<MaterialRef, Integer> available,
                                   int maxSteps,
                                   Runnable budgetCheck) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (material != null && count != null && count > 0) stock.put(material, count);
            });
            this.maxSteps = Math.max(1, maxSteps);
            this.budgetCheck = java.util.Objects.requireNonNull(budgetCheck, "budgetCheck");
        }

        private PartialTrace build(List<IngredientRef> roots) {
            for (IngredientRef root : roots) {
                budgetCheck.run();
                expand(root);
            }
            return new PartialTrace(List.copyOf(steps), List.copyOf(missing.values()));
        }

        private PartialTrace snapshot() {
            return new PartialTrace(List.copyOf(steps), List.copyOf(missing.values()));
        }

        private boolean expand(IngredientRef ingredient) {
            budgetCheck.run();
            int remaining = consumeAvailable(ingredient);
            if (remaining == 0) return true;
            if (steps.size() >= maxSteps || visiting.size() >= MAX_SEARCH_CALL_DEPTH) {
                noteMissing(ingredient, remaining);
                return false;
            }

            List<PartialChoice> choices = choicesFor(ingredient);
            for (PartialChoice choice : choices) {
                if (visiting.contains(choice.output())) continue;
                RecipeNode recipe = choice.recipe();
                int batches = Search.batchesFor(remaining, recipe.outputCount());
                if (batches <= 0) continue;
                if (ingredient.alternatives().size() > 1
                        && isNonProductiveFamilyRecipe(
                        recipe, ingredient.alternatives(), batches)) continue;

                List<IngredientRef> scaledInputs = new ArrayList<>();
                boolean valid = true;
                for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(recipe.inputs())) {
                    long scaled = scaledInputCount(input, batches);
                    if (scaled <= 0L || scaled > Integer.MAX_VALUE) {
                        valid = false;
                        break;
                    }
                    scaledInputs.add(input.withCount((int) scaled));
                }
                if (!valid) continue;

                visiting.add(choice.output());
                boolean covered = true;
                try {
                    for (IngredientRef input : scaledInputs) {
                        if (!expand(input)) covered = false;
                    }
                } finally {
                    visiting.remove(choice.output());
                }
                steps.add(new PlannedStep(recipe.recipeId(), batches));
                long surplus = (long) recipe.outputCount() * batches
                        - (ingredient.role() == DemandRole.CATALYST ? 0L : remaining);
                if (surplus > 0L) {
                    stock.merge(choice.output(), (int) Math.min(Integer.MAX_VALUE, surplus),
                            (left, right) -> (int) Math.min(
                                    Integer.MAX_VALUE, (long) left + right));
                }
                return covered;
            }

            noteMissing(ingredient, remaining);
            return false;
        }

        private int consumeAvailable(IngredientRef ingredient) {
            int remaining = ingredient.count();
            List<MaterialRef> ordered = new ArrayList<>(ingredient.alternatives());
            ordered.sort(Comparator
                    .comparingInt((MaterialRef material) -> stock.getOrDefault(material, 0))
                    .reversed());
            for (MaterialRef material : ordered) {
                int have = stock.getOrDefault(material, 0);
                if (have <= 0) continue;
                int take = Math.min(have, remaining);
                if (ingredient.role() != DemandRole.CATALYST) {
                    if (take == have) stock.remove(material);
                    else stock.put(material, have - take);
                }
                remaining -= take;
                if (remaining == 0) break;
            }
            return remaining;
        }

        private List<PartialChoice> choicesFor(IngredientRef ingredient) {
            List<PartialChoice> choices = new ArrayList<>();
            for (MaterialRef alternative : ingredient.alternatives()) {
                budgetCheck.run();
                for (RecipeNode recipe : graph.recipesByOutput()
                        .getOrDefault(alternative, List.of())) {
                    budgetCheck.run();
                    choices.add(new PartialChoice(alternative, recipe));
                }
            }
            choices.sort(Comparator
                    .comparingDouble((PartialChoice choice) -> inputStockCoverage(choice.recipe()))
                    .reversed()
                    .thenComparing(choice -> choice.recipe().recipeId().toString()));
            return choices;
        }

        private double inputStockCoverage(RecipeNode recipe) {
            budgetCheck.run();
            long required = 0L;
            long available = 0L;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(recipe.inputs())) {
                required += input.count();
                long matching = 0L;
                for (MaterialRef alternative : input.alternatives()) {
                    matching += stock.getOrDefault(alternative, 0);
                }
                available += Math.min(input.count(), matching);
            }
            return required == 0L ? 1.0D : (double) available / (double) required;
        }

        private boolean isNonProductiveFamilyRecipe(RecipeNode recipe,
                                                     List<MaterialRef> family,
                                                     int batches) {
            if (!family.contains(recipe.output())) return false;
            long produced = (long) recipe.outputCount() * batches;
            PartialFamilyCost analysis = familyCosts.computeIfAbsent(
                    List.copyOf(family), PartialFamilyCost::new);
            long consumed = 0L;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(recipe.inputs())) {
                if (input.role() == DemandRole.CATALYST) continue;
                long scaled = scaledInputCount(input, batches);
                long cost = analysis.ingredientCost(input, scaled, new HashSet<>());
                if (cost >= COST_UNKNOWN) return false;
                consumed = Math.min(COST_UNKNOWN, consumed + cost);
                if (consumed >= produced) return true;
            }
            return consumed >= produced;
        }

        private void noteMissing(IngredientRef ingredient, int count) {
            MissingKey key = new MissingKey(Set.copyOf(ingredient.alternatives()),
                    ingredient.nbtMatchMode(), ingredient.role());
            IngredientRef previous = missing.get(key);
            if (previous != null) {
                long combined = ingredient.role() == DemandRole.CATALYST
                        ? Math.max(previous.count(), count)
                        : (long) previous.count() + count;
                missing.put(key, previous.withCount((int) Math.min(Integer.MAX_VALUE, combined)));
            } else if (missing.size() < MAX_REPORTED_MISSING) {
                missing.put(key, ingredient.withCount(count));
            }
        }

        private record MissingKey(Set<MaterialRef> alternatives,
                                  ImmutableRecipeGraph.NbtMatchMode nbtMatchMode,
                                  DemandRole role) {}

        private record PartialChoice(MaterialRef output, RecipeNode recipe) {}

        private final class PartialFamilyCost {
            private final Set<MaterialRef> family;
            private final Map<MaterialRef, Long> unitCosts = new HashMap<>();

            private PartialFamilyCost(List<MaterialRef> family) {
                this.family = Set.copyOf(family);
            }

            private long ingredientCost(IngredientRef ingredient, long count,
                                        Set<MaterialRef> path) {
                budgetCheck.run();
                long best = COST_UNKNOWN;
                for (MaterialRef alternative : ingredient.alternatives()) {
                    long unit = materialUnitCost(alternative, path);
                    if (unit >= COST_UNKNOWN || count > COST_UNKNOWN / Math.max(1L, unit)) continue;
                    best = Math.min(best, unit * count);
                }
                return best;
            }

            private long materialUnitCost(MaterialRef material, Set<MaterialRef> path) {
                budgetCheck.run();
                if (family.contains(material)) return 1L;
                Long cached = unitCosts.get(material);
                if (cached != null) return cached;
                if (!path.add(material)) return COST_UNKNOWN;
                long best = COST_UNKNOWN;
                try {
                    List<RecipeNode> producers = graph.recipesByOutput()
                            .getOrDefault(material, List.of());
                    if (producers.isEmpty()) return 0L;
                    for (RecipeNode producer : producers) {
                        budgetCheck.run();
                        long cost = 0L;
                        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                                producer.inputs())) {
                            if (input.role() == DemandRole.CATALYST) continue;
                            long inputCost = ingredientCost(input, input.count(), path);
                            if (inputCost >= COST_UNKNOWN) {
                                cost = COST_UNKNOWN;
                                break;
                            }
                            cost = Math.min(COST_UNKNOWN, cost + inputCost);
                        }
                        if (cost < COST_UNKNOWN) {
                            long perOutput = (cost + producer.outputCount() - 1L)
                                    / producer.outputCount();
                            best = Math.min(best, perOutput);
                        }
                    }
                } finally {
                    path.remove(material);
                }
                if (best < COST_UNKNOWN) unitCosts.put(material, best);
                return best;
            }
        }
    }

    private record PartialTrace(List<PlannedStep> steps, List<IngredientRef> missing) {}

    private static final class DiagnosticBudget implements Runnable {
        private int remaining;

        private DiagnosticBudget(int maximumOperations) {
            remaining = Math.max(1, maximumOperations);
        }

        @Override
        public void run() {
            PlanningThreadContext.throwIfCancelled();
            if (--remaining < 0) throw new DiagnosticLimitException();
        }
    }

    /**
     * Proves seeded reachability only when the search is about to visit a branch.
     * Eagerly walking the complete dependency closure is itself expensive for broad tags: a
     * 60-item logs tag can fan out into most of a large modpack's crafting graph before the
     * inventory-first search gets a chance to select one stocked variant.
     *
     * <p>Cycles are treated conservatively as provisionally reachable. The real search still
     * validates quantities and rejects conversion loops, so this can retain an extra candidate
     * but can never hide a valid recipe.</p>
     */
    private static final class SeededReachability {
        private static final int UNREACHABLE_DEPTH = Integer.MAX_VALUE;

        private final Set<MaterialRef> seeds;
        private final Map<MaterialRef, Integer> materialDepth;
        private final Map<RecipeNode, Integer> recipeDepth;

        private SeededReachability(ImmutableRecipeGraph graph,
                                   Map<MaterialRef, Integer> available,
                                   Runnable budgetCheck) {
            Set<MaterialRef> present = new HashSet<>();
            for (Map.Entry<MaterialRef, Integer> entry : available.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) present.add(entry.getKey());
            }
            this.seeds = Set.copyOf(present);
            Map<MaterialRef, Integer> materialResult = new HashMap<>();
            Map<RecipeNode, Integer> recipeResult = new IdentityHashMap<>();
            Map<MaterialRef, List<InputRef>> dependents = new HashMap<>();
            Map<RecipeNode, int[]> inputSatisfied = new IdentityHashMap<>();
            Map<RecipeNode, int[]> inputDepths = new IdentityHashMap<>();
            java.util.ArrayDeque<MaterialRef> queue = new java.util.ArrayDeque<>();
            for (MaterialRef seed : seeds) {
                materialResult.put(seed, 0);
                queue.add(seed);
            }
            for (List<RecipeNode> candidates : graph.recipesByOutput().values()) {
                for (RecipeNode recipe : candidates) {
                    int inputCount = recipe.inputs().size();
                    inputSatisfied.put(recipe, new int[inputCount]);
                    inputDepths.put(recipe, new int[inputCount]);
                    for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
                        for (MaterialRef alternative : recipe.inputs().get(inputIndex).alternatives()) {
                            dependents.computeIfAbsent(alternative, ignored -> new ArrayList<>())
                                    .add(new InputRef(recipe, inputIndex));
                        }
                    }
                    if (inputCount == 0) {
                        recipeResult.put(recipe, 1);
                        materialResult.merge(recipe.output(), 1, Math::min);
                        queue.add(recipe.output());
                    }
                }
            }
            while (!queue.isEmpty()) {
                // This pass is linear in graph edges. It deliberately does not use the search
                // deadline: reachability is a routing index, not backtracking work, and charging
                // it against the tiny search budget caused false TIME_LIMIT results at state 1.
                PlanningThreadContext.throwIfCancelled();
                MaterialRef material = queue.removeFirst();
                int depth = materialResult.getOrDefault(material, 0);
                for (InputRef ref : dependents.getOrDefault(material, List.of())) {
                    int[] satisfied = inputSatisfied.get(ref.recipe());
                    int[] depths = inputDepths.get(ref.recipe());
                    if (depths[ref.inputIndex()] == 0 || depth < depths[ref.inputIndex()]) {
                        depths[ref.inputIndex()] = depth;
                    }
                    if (satisfied[ref.inputIndex()] != 1) satisfied[ref.inputIndex()] = 1;
                    boolean complete = true;
                    int deepest = 0;
                    for (int index = 0; index < satisfied.length; index++) {
                        if (satisfied[index] == 0) {
                            complete = false;
                            break;
                        }
                        deepest = Math.max(deepest, depths[index]);
                    }
                    if (!complete || recipeResult.containsKey(ref.recipe())) continue;
                    int recipeDepthValue = deepest + 1;
                    recipeResult.put(ref.recipe(), recipeDepthValue);
                    Integer previous = materialResult.putIfAbsent(ref.recipe().output(), recipeDepthValue);
                    if (previous == null || recipeDepthValue < previous) queue.add(ref.recipe().output());
                }
            }
            this.materialDepth = Map.copyOf(materialResult);
            this.recipeDepth = new IdentityHashMap<>(recipeResult);
        }

        private boolean canReach(MaterialRef material) {
            return seeds.contains(material) || materialDepth.containsKey(material);
        }

        private boolean canReach(RecipeNode recipe) {
            return recipeDepth.containsKey(recipe);
        }

        private int depth(RecipeNode recipe) {
            return recipeDepth.getOrDefault(recipe, UNREACHABLE_DEPTH);
        }

        private record InputRef(RecipeNode recipe, int inputIndex) {}
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

    private static final class DiagnosticLimitException extends RuntimeException {
        private DiagnosticLimitException() {
            super(null, null, false, false);
        }
    }
}
