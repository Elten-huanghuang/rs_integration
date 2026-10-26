package com.huanghuang.rsintegration.crafting.planning;

import java.lang.reflect.Method;

import com.huanghuang.rsintegration.crafting.MaterialVariantPreferences;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final int REACHABILITY_PRECHECK_MIN_CANDIDATES = 5;
    private static final int MAX_LOCAL_SEED_PROBE_NODES = 64;
    private static final int MAX_MATCHING_ITEM_INDEX_UNITS = 8_192;

    private PureRecipePlanner() {}

    public static Result resolve(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 List<IngredientRef> roots, int maxSteps) {
        int searchBudget = (int) Math.max(MIN_SEARCH_STATES, Math.min(MAX_SEARCH_STATES,
                (long) Math.max(1, maxSteps) * 32L));
        return resolve(graph, available, roots, maxSteps, searchBudget,
                DEFAULT_MAX_MEMOIZED_FAILURES);
    }

    /**
     * 使用调用方提供的完整偏好和有界预算执行搜索。
     *
     * <p>Consumer 检查会话需要固定 search state、截止时间以及请求级 forced/excluded
     * 语义，不能依赖每玩家“最新请求”槽位。</p>
     */
    public static Result resolveConfigured(
            ImmutableRecipeGraph graph,
            Map<MaterialRef, ? extends Number> available,
            List<IngredientRef> roots,
            int maxSteps,
            int maxSearchStates,
            int maxMemoizedFailures,
            long deadlineNanos,
            Map<ResourceLocation, ResourceLocation> forcedRecipes,
            PlanningPreferences preferences
    ) {
        return resolve(
                graph,
                available,
                roots,
                maxSteps,
                maxSearchStates,
                maxMemoizedFailures,
                deadlineNanos,
                System::nanoTime,
                Set.of(),
                forcedRecipes,
                preferences
        );
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                DEFAULT_MAX_MEMOIZED_FAILURES);
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                           List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                           int maxMemoizedFailures) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, Long.MAX_VALUE);
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, System::nanoTime);
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos,
                          LongSupplier nanoTime) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, nanoTime, Set.of());
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos,
                          LongSupplier nanoTime, Set<MaterialRef> activeRootOutputs) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, nanoTime, activeRootOutputs, Map.of());
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos,
                          LongSupplier nanoTime, Set<MaterialRef> activeRootOutputs,
                          Map<ResourceLocation, ResourceLocation> forcedRecipes) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, nanoTime, activeRootOutputs,
                forcedRecipes, PlanningPreferences.empty());
    }

    static Result resolve(ImmutableRecipeGraph graph,
                          Map<MaterialRef, ? extends Number> available,
                          List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                          int maxMemoizedFailures, long deadlineNanos,
                          LongSupplier nanoTime, Set<MaterialRef> activeRootOutputs,
                          Map<ResourceLocation, ResourceLocation> forcedRecipes,
                          PlanningPreferences preferences) {
        return PlanningLookupCache.run(() -> resolveInScope(graph, available, roots,
                maxSteps, maxSearchStates, maxMemoizedFailures, deadlineNanos, nanoTime,
                activeRootOutputs, forcedRecipes, preferences));
    }

    private static Result resolveInScope(ImmutableRecipeGraph graph,
                                        Map<MaterialRef, ? extends Number> available,
                                        List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                                        int maxMemoizedFailures, long deadlineNanos,
                                        LongSupplier nanoTime, Set<MaterialRef> activeRootOutputs,
                                        Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                        PlanningPreferences preferences) {
        List<IngredientRef> normalizedRoots = PureDemandNormalizer.mergeEquivalent(roots);
        Map<MaterialRef, Long> preciseAvailable = longAvailability(available);
        graph = ImmutableRecipeGraphProjector.bindSmithingStates(
                graph, preciseAvailable, normalizedRoots);
        Search search = new Search(graph, preciseAvailable, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, nanoTime, activeRootOutputs, forcedRecipes,
                preferences);
        PersistentTaskQueue<Task> pending = PersistentTaskQueue.from(
                normalizedRoots.stream().map(DemandTask::new).map(Task.class::cast).toList());
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
                    graph, preciseAvailable, maxSteps, activeRootOutputs,
                    status == Status.UNRESOLVABLE
                            ? search::checkBudget
                            : new DiagnosticBudget(MAX_DIAGNOSTIC_OPERATIONS),
                    forcedRecipes, preferences);
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
        Map<MaterialRef, Long> resultStock = status == Status.SUCCESS
                ? search.stock : search.initialStock;
        FailureReason failureReason = status == Status.UNRESOLVABLE
                ? search.sawMissingInputFailure
                        ? FailureReason.MISSING_INPUTS
                        : search.deepestFailureReason
                : FailureReason.NONE;
        return new Result(Feasibility.from(status), resultSteps, missing, resultStock,
                aggregateMagicCosts(graph, resultSteps), status,
                search.expandedStates, search.backtracks, search.memoHits,
                failureReason);
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

    /** 无法形成计划时区分普通缺料与不可增长的材料环。 */
    public enum FailureReason {
        NONE,
        MISSING_INPUTS,
        CYCLE
    }

    public record PlannedStep(ResourceLocation recipeId, long batches, MaterialRef demandedOutput) {
        public PlannedStep(ResourceLocation recipeId, long batches) {
            this(recipeId, batches, null);
        }
    }

    public record Result(Feasibility feasibility, List<PlannedStep> steps,
                         List<IngredientRef> missing, Map<MaterialRef, Long> remaining,
                         List<MagicResourceCost> magicCosts,
                         Status status, int expandedStates, int backtracks, int memoHits,
                         FailureReason failureReason) {
        public Result(boolean feasible, List<PlannedStep> steps,
                      List<IngredientRef> missing, Map<MaterialRef, Long> remaining) {
            this(feasible ? Feasibility.FEASIBLE : Feasibility.INFEASIBLE,
                    steps, missing, remaining, List.of(),
                    feasible ? Status.SUCCESS : Status.UNRESOLVABLE, 0, 0, 0,
                    feasible ? FailureReason.NONE : FailureReason.MISSING_INPUTS);
        }

        public Result(Feasibility feasibility, List<PlannedStep> steps,
                      List<IngredientRef> missing, Map<MaterialRef, Long> remaining,
                      Status status, int expandedStates, int backtracks, int memoHits) {
            this(feasibility, steps, missing, remaining, List.of(), status,
                    expandedStates, backtracks, memoHits,
                    feasibility == Feasibility.INFEASIBLE
                            ? FailureReason.MISSING_INPUTS : FailureReason.NONE);
        }

        public boolean feasible() {
            return feasibility == Feasibility.FEASIBLE;
        }

        public Result {
            steps = List.copyOf(steps);
            missing = List.copyOf(missing);
            remaining = Map.copyOf(remaining);
            magicCosts = MagicResourceCost.merge(magicCosts);
            if (status == null || feasibility == null || feasibility != Feasibility.from(status)) {
                throw new IllegalArgumentException("inconsistent pure-plan status");
            }
            if (failureReason == null) failureReason = FailureReason.NONE;
        }
    }

    private static List<MagicResourceCost> aggregateMagicCosts(
            ImmutableRecipeGraph graph, List<PlannedStep> steps) {
        if (steps.isEmpty()) return List.of();
        List<MagicResourceCost> costs = new ArrayList<>();
        for (PlannedStep step : steps) {
            RecipeNode node = graph.recipesById().get(step.recipeId());
            if (node == null) continue;
            for (MagicResourceCost cost : node.magicCosts()) {
                long total = cost.totalFor(Math.max(1L, step.batches()));
                if (total <= 0L) continue;
                costs.add(new MagicResourceCost(cost.kind(), total));
            }
        }
        return MagicResourceCost.merge(costs);
    }

    private static long scaledInputCount(IngredientRef input, long batches) {
        if (input.role() == DemandRole.CATALYST) return input.count();
        if (batches <= 0L || input.count() <= 0L) return 0L;
        return input.count() > Long.MAX_VALUE / batches
                ? Long.MAX_VALUE : input.count() * batches;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static Map<MaterialRef, Long> longAvailability(
            Map<MaterialRef, ? extends Number> available) {
        Map<MaterialRef, Long> precise = new LinkedHashMap<>(available.size());
        available.forEach((material, count) -> {
            long value = count == null ? 0L : Math.max(0L, count.longValue());
            precise.put(material, value);
        });
        return precise;
    }

    private static CandidateCycleRelation cycleRelation(
            RecipeNode candidate, Set<MaterialRef> ancestors) {
        boolean consumesOnlyAncestor = false;
        List<AncestorInput> touched = null;
        for (IngredientRef input : candidate.inputs()) {
            if (input.role() == DemandRole.CATALYST || input.alternatives().isEmpty()) continue;
            boolean hasOutsideAlternative = false;
            for (MaterialRef alternative : input.alternatives()) {
                if (!ancestors.contains(alternative)) {
                    hasOutsideAlternative = true;
                } else {
                    if (touched == null) touched = new ArrayList<>();
                    touched.add(new AncestorInput(alternative, input.count()));
                }
            }
            if (!hasOutsideAlternative) consumesOnlyAncestor = true;
        }
        return new CandidateCycleRelation(
                consumesOnlyAncestor, touched == null ? List.of() : touched);
    }

    private sealed interface Task permits DemandTask, CompleteRecipeTask {}
    private record DemandTask(IngredientRef ingredient) implements Task {}
    private record CompleteRecipeTask(MaterialRef output, long outputCount, long consumeCount,
                                      ResourceLocation recipeId, long batches,
                                      MaterialRef resolvingMaterial) implements Task {}
    private record CandidateLookup(MaterialRef material, NbtMatchMode mode) {}
    private record AncestorInput(MaterialRef material, long count) {}
    private record CandidateCycleRelation(boolean consumesOnlyAncestor,
                                          List<AncestorInput> ancestorInputs) {
        private CandidateCycleRelation {
            ancestorInputs = List.copyOf(ancestorInputs);
        }
    }
    private record FailureKey(PersistentTaskQueue<Task> pending, Map<MaterialRef, Long> stockChanges,
                              Set<MaterialRef> resolving, int stepCount) {}

    private static final class Search {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Long> initialStock;
        private final Map<MaterialRef, Long> stock = new HashMap<>();
        // 初始库存对一次搜索固定；失败键只需记录变化，零表示已耗尽的初始材料。
        private final Map<MaterialRef, Long> stockChanges = new HashMap<>();
        private final int maxSteps;
        private final int maxSearchStates;
        private final int maxMemoizedFailures;
        private final long deadlineNanos;
        private final LongSupplier nanoTime;
        private final Map<ResourceLocation, ResourceLocation> forcedRecipes;
        private final PlanningPreferences preferences;
        private final MaterialVariantPreferences.Snapshot variantPreferences;
        private final Map<ResourceLocation, List<MaterialRef>> stockByItem = new HashMap<>();
        // 只缓存静态物品分类；库存数量和新产生的 NBT 变体仍实时读取。
        private final Map<List<MaterialRef>, List<ResourceLocation>> matchingItemIndexes =
                new IdentityHashMap<>();
        private int matchingItemIndexUnits;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> resolving = new HashSet<>();
        private final Set<FailureKey> failedStates = new HashSet<>();
        private SeededReachability reachability;
        private final Map<List<MaterialRef>, BroadFamilyAnalysis> broadFamilyAnalyses =
                new HashMap<>();
        /**
         * Cached lower-bound dependency depth used only to make equivalent producers
         * deterministic.  A direct decomposition (one input -> many particles)
         * should beat a longer tool/processing chain, while feasibility remains
         * decided by the normal recursive search below.
         */
        private final Map<MaterialRef, Integer> producerDepthCache = new HashMap<>();
        private final Set<MaterialRef> producerDepthVisiting = new HashSet<>();
        private final Map<CandidateLookup, List<RecipeNode>> candidateCache = new HashMap<>();
        private final Map<RecipeNode, List<IngredientRef>> normalizedInputCache =
                new IdentityHashMap<>();
        private static final long FAMILY_COST_UNKNOWN = Long.MAX_VALUE / 4L;
        private int expandedStates;
        private int backtracks;
        private int memoHits;
        private IngredientRef deepestFailure;
        private int bestFailureDepth = -1;
        private int bestFailureStepCount = -1;
        private List<PlannedStep> bestFailureSteps = List.of();
        private FailureReason deepestFailureReason = FailureReason.MISSING_INPUTS;
        private boolean sawMissingInputFailure;
        private boolean stepLimitReached;
        private int callDepth;

        private Search(ImmutableRecipeGraph graph, Map<MaterialRef, Long> available,
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                       long deadlineNanos, LongSupplier nanoTime,
                       Set<MaterialRef> activeRootOutputs,
                       Map<ResourceLocation, ResourceLocation> forcedRecipes,
                       PlanningPreferences preferences) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (count != null && count > 0) {
                    stock.put(material, count);
                    stockByItem.computeIfAbsent(material.itemId(), ignored -> new ArrayList<>())
                            .add(material);
                }
            });
            initialStock = Map.copyOf(stock);
            resolving.addAll(activeRootOutputs);
            this.maxSteps = Math.max(1, maxSteps);
            this.maxSearchStates = Math.max(1, maxSearchStates);
            this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
            this.deadlineNanos = deadlineNanos;
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            this.forcedRecipes = Map.copyOf(forcedRecipes);
            this.preferences = preferences == null
                    ? PlanningPreferences.empty() : preferences;
            this.variantPreferences = MaterialVariantPreferences.snapshot();

        }

        private boolean solve(PersistentTaskQueue<Task> pending) {
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

        private boolean solveWithinDepth(PersistentTaskQueue<Task> pending) {
            if (++expandedStates > maxSearchStates) throw new SearchLimitException();
            if (pending.isEmpty()) return true;

            FailureKey key = maxMemoizedFailures == 0 ? null
                    : new FailureKey(pending, Map.copyOf(stockChanges),
                            Set.copyOf(resolving), steps.size());
            if (key != null && failedStates.contains(key)) {
                memoHits++;
                return false;
            }

            Task current = pending.head();
            PersistentTaskQueue<Task> rest = pending.tail();
            boolean solved = current instanceof DemandTask demand
                    ? solveDemand(demand.ingredient(), rest, pending)
                    : completeRecipe((CompleteRecipeTask) current, rest);
            if (!solved && key != null && failedStates.size() < maxMemoizedFailures) {
                failedStates.add(key);
            }
            return solved;
        }

        private boolean solveDemand(IngredientRef ingredient,
                                    PersistentTaskQueue<Task> rest,
                                    PersistentTaskQueue<Task> pending) {
            boolean catalyst = ingredient.role() == DemandRole.CATALYST;
            boolean cycleBlocked = false;
            boolean noRecipeBlocked = false;
            List<MaterialRef> stockAlternatives = inventoryFirst(ingredient.alternatives());
            if (catalyst) {
                if (stockAcross(ingredient) >= ingredient.count()) {
                    if (solve(rest)) return true;
                    backtracks++;
                }
            } else {
                if (ingredient.nbtMatchMode() != NbtMatchMode.EXACT
                        || ingredient.alternatives().stream().anyMatch(material -> !material.nbt().isEmpty())) {
                    Map<MaterialRef, Long> consumedVariants =
                            consumeMatchingVariants(ingredient, rest);
                    if (consumedVariants != null) {
                        if (solve(rest)) return true;
                        consumedVariants.forEach(this::setStock);
                        backtracks++;
                    }
                }
                for (MaterialRef alternative : stockAlternatives) {
                    long have = stock.getOrDefault(alternative, 0L);
                    if (have < ingredient.count()) continue;
                    setStock(alternative, have - ingredient.count());
                    if (solve(rest)) return true;
                    setStock(alternative, have);
                    backtracks++;
                }
            }

            Map<MaterialRef, Long> combinedConsumption = catalyst ? null
                    : consumeAcrossAlternatives(ingredient, rest);
            if (combinedConsumption != null) {
                if (solve(rest)) return true;
                combinedConsumption.forEach(this::setStock);
                backtracks++;
            }

            if (!ingredient.recursive()) {
                return false;
            }

            for (MaterialRef wanted : productionFirst(ingredient.alternatives())) {
                checkBudget();
                if (preferences.isNoRecipe(wanted)) {
                    noRecipeBlocked = true;
                    continue;
                }
                if (resolving.contains(wanted)) {
                    cycleBlocked = true;
                    continue;
                }
                long have = stock.getOrDefault(wanted, 0L);
                boolean broadFamily = ingredient.alternatives().size() > 1;
                long present = catalyst ? stockAcross(ingredient)
                        : broadFamily || ingredient.nbtMatchMode() != NbtMatchMode.EXACT
                        ? unreservedStockAcross(ingredient, rest)
                        : unreservedStock(rest, wanted);
                long needed = Math.max(0L, ingredient.count() - present);
                if (needed <= 0L) continue;
                List<RecipeNode> rawCandidates = inventoryFirstCandidates(
                        wanted, !broadFamily, ingredient.nbtMatchMode());
                List<RecipeNode> candidates = preferNonCyclicCandidates(rawCandidates);
                if (candidates.size() < rawCandidates.size()) cycleBlocked = true;
                for (RecipeNode candidate : candidates) {
                    if (steps.size() + scheduledRecipes(rest) >= maxSteps) {
                        stepLimitReached = true;
                        continue;
                    }
                    long selfConsumed = selfConsumedPerBatch(candidate, candidate.output());
                    long netGain = candidate.outputCount() - selfConsumed;
                    long batches = batchesFor(needed,
                            selfConsumed > 0 ? netGain : candidate.outputCount());
                    if (batches <= 0L) continue;
                    if (isNonProductiveBroadFamilyConversion(
                            candidate, ingredient.alternatives(), batches)) continue;
                    // Broad tags are pruned by family gain before reachability. In large
                    // modpacks, probing all log/chest variants first can traverse most of the
                    // recipe graph even though their recipes are only neutral conversion rings.
                    if (broadFamily && !reachability().canReach(candidate)) continue;
                    if (selfConsumed == 0
                            && isUnseededReverseConversion(candidate, wanted, batches)) {
                        cycleBlocked = true;
                        continue;
                    }
                    long scheduledBatches = batches;
                    long consumeCount = catalyst ? 0L : ingredient.count();
                    PersistentTaskQueue<Task> continuation = rest;
                    if (selfConsumed > 0) {
                        if (netGain <= 0) continue;
                        scheduledBatches = Math.min(batches, have / selfConsumed);
                        if (scheduledBatches <= 0) continue;
                        consumeCount = 0;
                        continuation = rest.prepend(new DemandTask(ingredient));
                    }
                    // Production may have a different NBT identity than the demand.
                    // Consume the combined existing and new variants through the matcher.
                    if (selfConsumed == 0 && !catalyst
                            && (broadFamily || !candidate.output().equals(wanted))) {
                        consumeCount = 0;
                        continuation = rest.prepend(new DemandTask(ingredient));
                    }
                    PersistentTaskQueue<Task> branch = recipeBranch(candidate, scheduledBatches,
                            consumeCount, continuation, wanted);
                    resolving.add(wanted);
                    if (solve(branch)) return true;
                    resolving.remove(wanted);
                    backtracks++;
                }
            }
            recordFailure(ingredient, pending,
                    cycleBlocked && !noRecipeBlocked && !sawMissingInputFailure
                            ? FailureReason.CYCLE : FailureReason.MISSING_INPUTS);
            return false;
        }

        private void recordFailure(IngredientRef ingredient,
                                   PersistentTaskQueue<Task> pending,
                                   FailureReason reason) {
            if (reason == FailureReason.MISSING_INPUTS) {
                sawMissingInputFailure = true;
            }
            int depth = resolving.size();
            List<PlannedStep> partial = new ArrayList<>(steps);
            for (Task task : pending) {
                if (task instanceof CompleteRecipeTask completion) {
                    partial.add(new PlannedStep(completion.recipeId(), completion.batches(),
                            completion.output().constrainedNbt() ? completion.output() : null));
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
            deepestFailureReason = reason;
        }

        /**
         * A tag ingredient is satisfied by the aggregate stock of all matching variants.
         * Keep singleton demands in the remaining task list reserved where possible so a
         * broad tag (for example wool or fuels) does not consume a later exact material.
         */
        private Map<MaterialRef, Long> consumeAcrossAlternatives(
                IngredientRef ingredient, PersistentTaskQueue<Task> rest) {
            List<MaterialRef> stocked = ingredient.alternatives().stream()
                    .filter(material -> stock.getOrDefault(material, 0L) > 0L)
                    .sorted(Comparator
                            .comparingInt((MaterialRef material) ->
                                    variantPreferences.rank(material.itemId()))
                            .thenComparing(Comparator.comparingLong(
                                    (MaterialRef material) -> unreservedStock(rest, material))
                                    .reversed())
                            .thenComparing(Comparator.comparingLong(
                                    (MaterialRef material) ->
                                            stock.getOrDefault(material, 0L)).reversed()))
                    .toList();
            if (stocked.size() < 2) return null;

            long total = 0L;
            for (MaterialRef material : stocked) {
                total = saturatedAdd(total, stock.getOrDefault(material, 0L));
                if (total >= ingredient.count()) break;
            }
            if (total < ingredient.count()) return null;

            Map<MaterialRef, Long> previous = consumeStocked(
                    stocked, ingredient.count(), rest);
            int contributors = previous.size();
            if (contributors > 1) return previous;
            previous.forEach(this::setStock);
            return null;
        }

        private Map<MaterialRef, Long> consumeMatchingVariants(
                IngredientRef ingredient, PersistentTaskQueue<Task> rest) {
            List<MaterialRef> stocked = matchingStock(ingredient)
                    .sorted(Comparator
                            .comparingInt((MaterialRef material) ->
                                    variantPreferences.rank(material.itemId()))
                            .thenComparing(Comparator.comparingLong(
                                    (MaterialRef material) -> unreservedStock(rest, material))
                                    .reversed())
                            .thenComparing(Comparator.comparingLong(
                                    (MaterialRef material) ->
                                            stock.getOrDefault(material, 0L)).reversed()))
                    .toList();
            long total = 0L;
            for (MaterialRef material : stocked) {
                total = saturatedAdd(total, stock.getOrDefault(material, 0L));
                if (total >= ingredient.count()) break;
            }
            if (total < ingredient.count()) return null;

            return consumeStocked(stocked, ingredient.count(), rest);
        }

        private Map<MaterialRef, Long> consumeStocked(
                List<MaterialRef> stocked, long count,
                PersistentTaskQueue<Task> rest) {
            long remaining = count;
            Map<MaterialRef, Long> previous = new HashMap<>();
            // Consume only stock above later exact demands first. This makes the soft
            // preference subordinate to plan feasibility instead of stealing a required item.
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (MaterialRef material : stocked) {
                    long have = stock.getOrDefault(material, 0L);
                    long capacity = pass == 0
                            ? unreservedStock(rest, material)
                            : have;
                    long take = Math.min(capacity, remaining);
                    if (take <= 0) continue;
                    previous.putIfAbsent(material, have);
                    setStock(material, have - take);
                    remaining -= take;
                    if (remaining == 0) break;
                }
            }
            return previous;
        }

        private long unreservedStock(
                PersistentTaskQueue<Task> rest, MaterialRef material) {
            return Math.max(0L, stock.getOrDefault(material, 0L)
                    - singletonDemand(rest, material));
        }

        private long unreservedStockAcross(
                IngredientRef ingredient, PersistentTaskQueue<Task> rest) {
            long total = 0L;
            var matching = matchingStock(ingredient).iterator();
            while (matching.hasNext()) {
                MaterialRef material = matching.next();
                total = saturatedAdd(total, unreservedStock(rest, material));
            }
            return total;
        }

        private static long singletonDemand(
                PersistentTaskQueue<Task> tasks, MaterialRef material) {
            long demand = 0L;
            for (Task task : tasks) {
                if (!(task instanceof DemandTask needed)
                        || needed.ingredient().alternatives().size() != 1
                        || !needed.ingredient().alternatives().get(0).equals(material)) continue;
                demand = saturatedAdd(demand, needed.ingredient().count());
            }
            return demand;
        }

        private boolean completeRecipe(
                CompleteRecipeTask completed, PersistentTaskQueue<Task> rest) {
            long before = stock.getOrDefault(completed.output(), 0L);
            long afterProduction = before
                    + (long) completed.outputCount() * completed.batches();
            if (afterProduction < completed.consumeCount()
                    || afterProduction < before) {
                return false;
            }
            resolving.remove(completed.resolvingMaterial());
            setStock(completed.output(), afterProduction - completed.consumeCount());
            RecipeNode source = graph.recipesById().get(completed.recipeId());
            MaterialRef demanded = source != null && ("smithing".equals(source.modTypeId())
                    || completed.output().constrainedNbt())
                    && !completed.output().nbt().isEmpty() ? completed.output() : null;
            steps.add(new PlannedStep(completed.recipeId(), completed.batches(), demanded));
            if (solve(rest)) return true;
            steps.remove(steps.size() - 1);
            setStock(completed.output(), before);
            resolving.add(completed.resolvingMaterial());
            backtracks++;
            return false;
        }

        private PersistentTaskQueue<Task> recipeBranch(
                RecipeNode candidate, long batches, long consumeCount,
                PersistentTaskQueue<Task> rest, MaterialRef resolvingMaterial) {
            List<IngredientRef> inputs = normalizedInputs(candidate);
            PersistentTaskQueue<Task> branch = rest;
            branch = branch.prepend(new CompleteRecipeTask(candidate.output(),
                    candidate.outputCount(), consumeCount, candidate.recipeId(),
                    batches, resolvingMaterial));
            for (int index = inputs.size() - 1; index >= 0; index--) {
                IngredientRef input = inputs.get(index);
                long scaled = scaledInputCount(input, batches);
                branch = branch.prepend(new DemandTask(input.withCount(scaled)));
            }
            return branch;
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
            ordered.sort(Comparator.comparingInt(this::stockAlternativeRank)
                    .thenComparingInt(material -> variantPreferences.rank(material.itemId())));
            return ordered;
        }

        /**
         * 玩家显式选择的是硬约束，不是排序提示。失败后回退到其他配方会让
         * UI 显示 B、执行却偷偷变成 A，并掩盖真正不可执行的循环。
         */
        private List<RecipeNode> preferForcedCandidate(MaterialRef wanted,
                                                       List<RecipeNode> candidates) {
            if (forcedRecipes.isEmpty()) return preferSavedCandidate(wanted, candidates);
            ResourceLocation forced = ForcedRecipeGraphProjector
                    .forcedRecipeFor(wanted, forcedRecipes);
            if (forced == null) return preferSavedCandidate(wanted, candidates);
            for (RecipeNode candidate : candidates) {
                if (candidate.recipeId().equals(forced)) return List.of(candidate);
            }
            return List.of();
        }

        private List<RecipeNode> preferSavedCandidate(MaterialRef wanted,
                                                      List<RecipeNode> candidates) {
            ResourceLocation preferred = preferences.preferredRecipe(wanted);
            if (preferred == null || candidates.size() < 2) return candidates;
            for (int i = 0; i < candidates.size(); i++) {
                if (!candidates.get(i).recipeId().equals(preferred)) continue;
                if (i == 0) return candidates;
                List<RecipeNode> reordered = new ArrayList<>(candidates);
                RecipeNode selected = reordered.remove(i);
                reordered.add(0, selected);
                return reordered;
            }
            return candidates;
        }

        private boolean isForcedCandidate(MaterialRef wanted, RecipeNode candidate) {
            if (forcedRecipes.isEmpty()) return false;
            ResourceLocation forced = ForcedRecipeGraphProjector
                    .forcedRecipeFor(wanted, forcedRecipes);
            return forced != null && forced.equals(candidate.recipeId());
        }

        private int stockAlternativeRank(MaterialRef material) {
            if (stock.getOrDefault(material, 0L) > 0L) return 0;
            return 1;
        }

        private List<MaterialRef> productionFirst(List<MaterialRef> alternatives) {
            if (alternatives.size() < 2) return alternatives;
            List<MaterialRef> ordered = new ArrayList<>(alternatives);
            ordered.sort(Comparator
                    .comparingInt((MaterialRef material) ->
                            variantPreferences.rank(material.itemId()))
                    .thenComparingInt(this::stockAlternativeRank));
            return ordered;
        }

        private List<RecipeNode> inventoryFirstCandidates(MaterialRef wanted,
                                                           boolean pruneUnseeded,
                                                           NbtMatchMode mode) {
            List<RecipeNode> candidates = candidates(wanted, mode).stream()
                    .filter(candidate -> !preferences.excludes(candidate.recipeId()))
                    .toList();
            // Tiny producer sets are cheaper to explore directly. Constructing
            // seeded reachability scans the complete scoped graph before the
            // first branch, which dominates equipment and upgrade chains.
            boolean useReachability = pruneUnseeded
                    && candidates.size() >= REACHABILITY_PRECHECK_MIN_CANDIDATES;
            // Reachability is only a heuristic: it is based on the initial stock
            // snapshot and cannot see dynamic/NBT-sensitive outputs produced while
            // solving this plan. Keep the fast filter in the common case, but fall
            // back to all producers when it would remove every candidate. This
            // prevents false "missing material" results without making every broad
            // recipe search traverse the entire graph.
            List<RecipeNode> ordered = candidates.stream()
                    .filter(candidate -> isForcedCandidate(wanted, candidate)
                            || !pruneUnseeded || hasAllInputsInStock(candidate)
                            || (useReachability
                            ? reachability().canReach(candidate)
                            : hasLocalSeedPath(candidate, new HashSet<>(),
                            new int[] { MAX_LOCAL_SEED_PROBE_NODES })))
                    .collect(Collectors.toCollection(ArrayList::new));
            // Seeded reachability is only an ordering hint. A partially stocked direct
            // recipe must still be attempted so its concrete missing inputs can be
            // reported instead of letting a sibling reverse conversion mark the whole
            // search as cyclic.
            candidates.stream()
                    .filter(this::hasAnyInputInStock)
                    .filter(candidate -> !ordered.contains(candidate))
                    .forEach(ordered::add);
            if (ordered.isEmpty() && !candidates.isEmpty()) {
                // A conservative escape hatch for stale reachability results:
                // require at least one concrete stock seed before reopening a
                // candidate.  A recipe whose inputs are all absent would only
                // create an unproductive empty-material search.
                candidates.stream()
                        .filter(this::hasAnyInputInStock)
                        .forEach(ordered::add);
            }
            if (ordered.size() < 2) return ordered;
            List<CandidateScore> scores = new ArrayList<>(ordered.size());
            for (RecipeNode candidate : ordered) scores.add(new CandidateScore(candidate));
            Comparator<CandidateScore> coverage = Comparator
                    .comparingDouble((CandidateScore score) -> score.coverage).reversed();
            Comparator<CandidateScore> dependencyCost = Comparator
                    .comparingInt(CandidateScore::dependencyCost)
                    .thenComparing(Comparator.comparingLong(
                            (CandidateScore score) -> score.candidate.outputCount()).reversed());
            Comparator<CandidateScore> structuralCost = Comparator
                    .comparingInt((CandidateScore score) -> distinctInputGroups(score.candidate))
                    .thenComparingInt(CandidateScore::alternativeCount);
            // 评分仅在此次排序内复用；昂贵的依赖评分仍按需执行，不提前扩大遍历范围。
            scores.sort(useReachability
                    ? coverage.thenComparingInt(score -> reachability().depth(score.candidate))
                            .thenComparing(dependencyCost)
                            .thenComparing(structuralCost)
                            .thenComparing(CandidateScore::recipeId)
                    : coverage.thenComparing(dependencyCost)
                            .thenComparing(structuralCost)
                            .thenComparing(CandidateScore::recipeId));
            for (int i = 0; i < scores.size(); i++) ordered.set(i, scores.get(i).candidate);
            return preferForcedCandidate(wanted, ordered);
        }

        private final class CandidateScore {
            private final RecipeNode candidate;
            private final double coverage;
            private int dependencyCost = -1;
            private int alternativeCount = -1;
            private String recipeId;

            private CandidateScore(RecipeNode candidate) {
                this.candidate = candidate;
                coverage = inputStockCoverage(candidate);
            }

            private int dependencyCost() {
                if (dependencyCost < 0) dependencyCost = dependencyDepth(candidate, new HashSet<>());
                return dependencyCost;
            }

            private int alternativeCount() {
                if (alternativeCount < 0) alternativeCount = inputAlternativeCount(candidate);
                return alternativeCount;
            }

            private String recipeId() {
                if (recipeId == null) recipeId = candidate.recipeId().toString();
                return recipeId;
            }
        }

        /**
         * Returns a conservative lower bound for the number of recipe layers needed
         * to seed this candidate from the current inventory.  Cycles, runtime-NBT
         * outputs and unknown branches are treated as a large finite cost so this
         * method can never reject a recipe or change feasibility.
         */
        private int dependencyDepth(RecipeNode candidate, Set<MaterialRef> visiting) {
            checkBudget();
            int best = Integer.MAX_VALUE / 4;
            for (IngredientRef input : normalizedInputs(candidate)) {
                if (input.role() == DemandRole.CATALYST) continue;
                int inputDepth = Integer.MAX_VALUE / 4;
                for (MaterialRef alternative : input.alternatives()) {
                    inputDepth = Math.min(inputDepth, materialProducerDepth(alternative, visiting));
                }
                if (inputDepth >= Integer.MAX_VALUE / 4) return inputDepth;
                best = Math.max(best == Integer.MAX_VALUE / 4 ? 0 : best, inputDepth);
            }
            return best == Integer.MAX_VALUE / 4 ? 0 : best + 1;
        }

        private int materialProducerDepth(MaterialRef material, Set<MaterialRef> visiting) {
            Integer cached = producerDepthCache.get(material);
            if (cached != null) return cached;
            if (stockAcross(new IngredientRef(List.of(material), 1, NbtMatchMode.ANY,
                    DemandRole.CONSUMED)) > 0) return 0;
            if (material.runtimeNbt() || visiting.size() >= MAX_SEARCH_CALL_DEPTH
                    || !visiting.add(material)
                    || !producerDepthVisiting.add(material)) {
                return Integer.MAX_VALUE / 4;
            }
            int best = Integer.MAX_VALUE / 4;
            try {
                for (RecipeNode producer : candidates(material, NbtMatchMode.ANY)) {
                    best = Math.min(best, dependencyDepth(producer, visiting));
                }
            } finally {
                visiting.remove(material);
                producerDepthVisiting.remove(material);
            }
            if (best < Integer.MAX_VALUE / 4) best++;
            producerDepthCache.put(material, best);
            return best;
        }

        private int distinctInputGroups(RecipeNode candidate) {
            return normalizedInputs(candidate).size();
        }

        private int inputAlternativeCount(RecipeNode candidate) {
            return normalizedInputs(candidate).stream()
                    .mapToInt(input -> input.alternatives().size())
                    .sum();
        }

        private boolean hasAllInputsInStock(RecipeNode candidate) {
            return candidate.inputs().stream().allMatch(input -> stockAcross(input) >= input.count());
        }

        /**
         * 先尝试不依赖当前祖先产物的正向配方，再尝试反向转换。
         * 不直接禁用反向转换，因为它也可能是有净增益的合法放大链，
         * 例如 1 shard -> 1 cluster -> 2 shard。
         */
        private List<RecipeNode> preferNonCyclicCandidates(List<RecipeNode> candidates) {
            if (candidates.isEmpty()) return candidates;
            List<RecipeNode> forward = new ArrayList<>(candidates.size());
            List<RecipeNode> reverse = new ArrayList<>(0);
            for (RecipeNode candidate : candidates) {
                CandidateCycleRelation relation = cycleRelation(candidate, resolving);
                if (isZeroGainResolvingCycle(candidate, relation.ancestorInputs())) continue;
                (relation.consumesOnlyAncestor() ? reverse : forward).add(candidate);
            }
            forward.addAll(reverse);
            return forward;
        }

        /**
         * 拒绝一步闭合且没有净增益的反向环，例如
         * 1 个 9x -> 9 个 8x -> 1 个 9x。
         * 若反向转换会产生额外材料，则仍允许，以支持合法种子放大。
         */
        private boolean isZeroGainResolvingCycle(RecipeNode candidate,
                                                 List<AncestorInput> ancestorInputs) {
            if (ancestorInputs.isEmpty()) return false;
            for (AncestorInput ancestorInput : ancestorInputs) {
                for (RecipeNode back : candidates(ancestorInput.material(), NbtMatchMode.ANY)) {
                    for (IngredientRef backInput : back.inputs()) {
                        if (backInput.role() == DemandRole.CATALYST
                                || backInput.count() <= 0
                                || !backInput.alternatives().contains(candidate.output())) continue;
                        if (candidate.outputCount() < backInput.count()
                                || candidate.outputCount() % backInput.count() != 0L) {
                            continue;
                        }
                        long returnedBatches = candidate.outputCount() / backInput.count();
                        long returnedAncestor = returnedBatches * back.outputCount();
                        if (returnedAncestor <= ancestorInput.count()) return true;
                    }
                }
            }
            return false;
        }

        private List<RecipeNode> candidates(MaterialRef wanted, NbtMatchMode mode) {
            return candidateCache.computeIfAbsent(new CandidateLookup(wanted, mode),
                    lookup -> ImmutableRecipeGraphProjector.candidates(
                            graph, lookup.material(), lookup.mode()));
        }

        private List<IngredientRef> normalizedInputs(RecipeNode recipe) {
            return normalizedInputCache.computeIfAbsent(recipe,
                    node -> PureDemandNormalizer.mergeEquivalent(node.inputs()));
        }

        private boolean hasAnyInputInStock(RecipeNode candidate) {
            return candidate.inputs().stream().anyMatch(input ->
                    input.alternatives().stream().anyMatch(material ->
                            stock.getOrDefault(material, 0L) > 0L));
        }

        /**
         * Conservative reachability check for a tiny producer set. Unlike
         * {@link SeededReachability}, this follows only the candidate's local
         * dependency closure and never indexes the complete scoped graph.
         */
        private boolean hasLocalSeedPath(RecipeNode candidate, Set<MaterialRef> visiting,
                                         int[] remainingNodes) {
            for (IngredientRef input : normalizedInputs(candidate)) {
                boolean satisfied = input.alternatives().stream().anyMatch(material ->
                        hasLocalSeed(material, input.nbtMatchMode(), visiting, remainingNodes));
                if (!satisfied) return false;
            }
            return true;
        }

        private boolean hasLocalSeed(MaterialRef material, NbtMatchMode mode,
                                     Set<MaterialRef> visiting, int[] remainingNodes) {
            if (stockAcross(new IngredientRef(List.of(material), 1, mode,
                    DemandRole.CONSUMED)) > 0) return true;
            // Cycles and runtime-NBT outputs are left for the main
            // planner; this helper must never turn an uncertain route into a
            // false shortage.
            if (material.runtimeNbt() || remainingNodes[0]-- <= 0
                    || !visiting.add(material)) return true;
            try {
                List<RecipeNode> producers = ImmutableRecipeGraphProjector.candidates(
                        graph, material, mode);
                if (producers.isEmpty()) return false;
                return producers.stream().anyMatch(producer ->
                        hasLocalSeedPath(producer, visiting, remainingNodes));
            } finally {
                visiting.remove(material);
            }
        }

        private SeededReachability reachability() {
            if (reachability == null) {
                reachability = new SeededReachability(graph, initialStock, this::checkBudget);
            }
            return reachability;
        }

        private double inputStockCoverage(RecipeNode candidate) {
            long required = 0L;
            long covered = 0L;
            for (IngredientRef input : normalizedInputs(candidate)) {
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
                                                    long batches) {
            if (stock.getOrDefault(wanted, 0L) > 0L) return false;
            for (IngredientRef input : normalizedInputs(candidate)) {
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
            if (ingredient.alternatives().size() == 1) {
                return stockAcrossItem(ingredient, ingredient.alternatives().get(0).itemId());
            }
            long total = 0L;
            for (ResourceLocation itemId : matchingItemIds(ingredient)) {
                total = saturatedAdd(total, stockAcrossItem(ingredient, itemId));
            }
            return total;
        }

        private long stockAcrossItem(IngredientRef ingredient, ResourceLocation itemId) {
            long total = 0L;
            for (MaterialRef material : stockByItem.getOrDefault(itemId, List.of())) {
                long count = stock.getOrDefault(material, 0L);
                if (count <= 0L) continue;
                checkBudget();
                if (matchesIngredient(material, ingredient)) total = saturatedAdd(total, count);
            }
            return total;
        }

        private List<ResourceLocation> matchingItemIds(IngredientRef ingredient) {
            List<MaterialRef> alternatives = ingredient.alternatives();
            if (alternatives.size() == 1) return List.of(alternatives.get(0).itemId());
            List<ResourceLocation> cached = matchingItemIndexes.get(alternatives);
            if (cached != null) return cached;
            Set<ResourceLocation> unique = new LinkedHashSet<>();
            for (MaterialRef alternative : alternatives) {
                checkBudget();
                unique.add(alternative.itemId());
            }
            List<ResourceLocation> itemIds = List.copyOf(unique);
            long units = 1L + alternatives.size() + itemIds.size();
            if (units <= MAX_MATCHING_ITEM_INDEX_UNITS - matchingItemIndexUnits) {
                matchingItemIndexes.put(alternatives, itemIds);
                matchingItemIndexUnits += (int) units;
            }
            return itemIds;
        }

        /** Restricts NBT matching to inventory variants with a compatible item id. */
        private Stream<MaterialRef> matchingStock(IngredientRef ingredient) {
            return matchingItemIds(ingredient).stream()
                    .flatMap(itemId -> stockByItem.getOrDefault(itemId, List.of()).stream())
                    .filter(material -> stock.getOrDefault(material, 0L) > 0L)
                    .filter(material -> {
                        checkBudget();
                        return matchesIngredient(material, ingredient);
                    });
        }

        private static boolean matchesIngredient(MaterialRef stocked, IngredientRef ingredient) {
            return ImmutableRecipeGraphProjector.matchesIngredient(stocked, ingredient);
        }

        /**
         * Rejects neutral compression/conversion rings while resolving a broad tag. For example,
         * log -> board -> another log has no net gain in the logs family and should not be tried
         * once the aggregate log stock is short. A genuinely amplifying recipe (one family item
         * producing two) remains eligible. Missing non-family leaves are treated as zero-cost so
         * the normal planner can still report them as missing instead of this guard hiding them.
         */
        private boolean isNonProductiveBroadFamilyConversion(
                RecipeNode candidate, List<MaterialRef> familyAlternatives, long batches) {
            if (familyAlternatives.size() < 2
                    || !familyAlternatives.contains(candidate.output())) return false;
            long produced = (long) candidate.outputCount() * batches;
            if (produced <= 0L) return false;

            List<MaterialRef> familyKey = List.copyOf(familyAlternatives);
            BroadFamilyAnalysis analysis = broadFamilyAnalyses.computeIfAbsent(
                    familyKey, BroadFamilyAnalysis::new);
            long consumed = 0L;
            for (IngredientRef input : normalizedInputs(candidate)) {
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
                        for (IngredientRef input : normalizedInputs(producer)) {
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

        private static long batchesFor(long needed, long outputCount) {
            if (outputCount <= 0 || needed <= 0L) return -1L;
            return Math.max(1L, saturatedAdd(needed, outputCount - 1L)
                    / outputCount);
        }

        private static long selfConsumedPerBatch(RecipeNode candidate, MaterialRef output) {
            long consumed = 0;
            for (IngredientRef input : candidate.inputs()) {
                if (SelfAmplifyingRecipePolicy.isSelfInput(input, output)) {
                    consumed += input.count();
                }
            }
            return consumed;
        }

        private static int scheduledRecipes(PersistentTaskQueue<Task> pending) {
            int count = 0;
            for (Task task : pending) {
                if (task instanceof CompleteRecipeTask) count++;
            }
            return count;
        }

        private void setStock(MaterialRef material, long count) {
            long normalized = Math.max(0L, count);
            if (maxMemoizedFailures > 0) {
                if (normalized == initialStock.getOrDefault(material, 0L)) {
                    stockChanges.remove(material);
                } else {
                    stockChanges.put(material, normalized);
                }
            }
            if (count <= 0L) {
                stock.remove(material);
            } else {
                stock.put(material, count);
                List<MaterialRef> variants = stockByItem.computeIfAbsent(
                        material.itemId(), ignored -> new ArrayList<>());
                if (!variants.contains(material)) variants.add(material);
            }
        }
    }

    /** Builds a deterministic partial recipe trace after feasibility search finds raw shortages. */
    private static final class PartialPlanBuilder {
        private static final long COST_UNKNOWN = Long.MAX_VALUE / 4L;

        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Long> stock = new HashMap<>();
        private final Map<ResourceLocation, Set<MaterialRef>> stockByItem = new HashMap<>();
        private final int maxSteps;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final Map<List<MaterialRef>, PartialFamilyCost> familyCosts = new HashMap<>();
        private final Map<CandidateLookup, List<RecipeNode>> candidateCache = new HashMap<>();
        private final Map<RecipeNode, List<IngredientRef>> normalizedInputCache =
                new IdentityHashMap<>();
        private final Runnable budgetCheck;
        private final Map<ResourceLocation, ResourceLocation> forcedRecipes;
        private final PlanningPreferences preferences;
        private final Map<MissingKey, IngredientRef> missing = new LinkedHashMap<>();

        private PartialPlanBuilder(ImmutableRecipeGraph graph,
                                   Map<MaterialRef, Long> available,
                                   int maxSteps, Set<MaterialRef> activeRootOutputs,
                                   Runnable budgetCheck,
                                   Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                   PlanningPreferences preferences) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (material != null && count != null && count > 0) {
                    addStock(material, count);
                }
            });
            this.maxSteps = Math.max(1, maxSteps);
            visiting.addAll(activeRootOutputs);
            this.budgetCheck = Objects.requireNonNull(budgetCheck, "budgetCheck");
            this.forcedRecipes = Map.copyOf(forcedRecipes);
            this.preferences = preferences == null
                    ? PlanningPreferences.empty() : preferences;
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
            long remaining = consumeAvailable(ingredient);
            if (remaining == 0L) return true;
            if (steps.size() >= maxSteps || visiting.size() >= MAX_SEARCH_CALL_DEPTH) {
                noteMissing(ingredient, remaining);
                return false;
            }
            if (!ingredient.recursive()) {
                noteMissing(ingredient, remaining);
                return false;
            }

            List<PartialChoice> choices = choicesFor(ingredient);
            for (PartialChoice choice : choices) {
                if (visiting.contains(choice.output())) continue;
                RecipeNode recipe = choice.recipe();
                long batches = Search.batchesFor(remaining, recipe.outputCount());
                if (batches <= 0L) continue;
                if (ingredient.alternatives().size() > 1
                        && isNonProductiveFamilyRecipe(
                        recipe, ingredient.alternatives(), batches)) continue;

                List<IngredientRef> scaledInputs = new ArrayList<>();
                boolean valid = true;
                for (IngredientRef input : normalizedInputs(recipe)) {
                    long scaled = scaledInputCount(input, batches);
                    if (scaled <= 0L) {
                        valid = false;
                        break;
                    }
                    scaledInputs.add(input.withCount(scaled));
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
                steps.add(new PlannedStep(recipe.recipeId(), batches,
                        ("smithing".equals(recipe.modTypeId()) || recipe.output().constrainedNbt())
                                && !recipe.output().nbt().isEmpty()
                                ? recipe.output() : null));
                long surplus = recipe.outputCount() * batches
                        - (ingredient.role() == DemandRole.CATALYST ? 0L : remaining);
                if (surplus > 0L) {
                    addStock(recipe.output(), surplus);
                }
                return covered;
            }

            noteMissing(ingredient, remaining);
            return false;
        }

        private long consumeAvailable(IngredientRef ingredient) {
            long remaining = ingredient.count();
            Set<ResourceLocation> itemIds = new LinkedHashSet<>();
            for (MaterialRef alternative : ingredient.alternatives()) {
                itemIds.add(alternative.itemId());
            }
            Set<MaterialRef> candidates = new LinkedHashSet<>();
            for (ResourceLocation itemId : itemIds) {
                for (MaterialRef material : stockByItem.getOrDefault(itemId, Set.of())) {
                    if (stock.getOrDefault(material, 0L) <= 0L) continue;
                    budgetCheck.run();
                    if (ImmutableRecipeGraphProjector.matchesIngredient(material, ingredient)) {
                        candidates.add(material);
                    }
                }
            }
            List<MaterialRef> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator
                    .comparingLong((MaterialRef material) -> stock.getOrDefault(material, 0L))
                    .reversed());
            for (MaterialRef material : ordered) {
                long have = stock.getOrDefault(material, 0L);
                if (have <= 0L) continue;
                long take = Math.min(have, remaining);
                if (ingredient.role() != DemandRole.CATALYST) {
                    if (take == have) stock.remove(material);
                    else stock.put(material, have - take);
                }
                remaining -= take;
                if (remaining == 0L) break;
            }
            return remaining;
        }

        private void addStock(MaterialRef material, long count) {
            if (count <= 0L) return;
            stock.merge(material, count, PureRecipePlanner::saturatedAdd);
            stockByItem.computeIfAbsent(material.itemId(), ignored -> new LinkedHashSet<>())
                    .add(material);
        }

        private List<PartialChoice> choicesFor(IngredientRef ingredient) {
            List<PartialChoice> choices = new ArrayList<>();
            Map<RecipeNode, Boolean> zeroGainCycles = new HashMap<>();
            for (MaterialRef alternative : ingredient.alternatives()) {
                budgetCheck.run();
                List<RecipeNode> candidates = partialCandidates(alternative,
                        ingredient.nbtMatchMode()).stream()
                        .filter(recipe -> !preferences.excludes(recipe.recipeId()))
                        .toList();
                ResourceLocation forced = ForcedRecipeGraphProjector
                        .forcedRecipeFor(alternative, forcedRecipes);
                if (forced != null) {
                    candidates = candidates.stream()
                            .filter(recipe -> recipe.recipeId().equals(forced))
                            .toList();
                } else if (preferences.isNoRecipe(alternative)) {
                    candidates = List.of();
                }
                for (RecipeNode recipe : candidates) {
                    budgetCheck.run();
                    if (zeroGainCycles.computeIfAbsent(recipe,
                            this::isZeroGainVisitingCycle)) continue;
                    choices.add(new PartialChoice(alternative, recipe));
                }
            }
            choices.sort(Comparator
                    .comparingInt((PartialChoice choice) -> isForced(choice) ? 0 : 1)
                    .thenComparingInt(choice -> isSavedPreference(choice) ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble(
                            (PartialChoice choice) -> inputStockCoverage(choice.recipe()))
                    .reversed()
                    .thenComparing(choice -> choice.recipe().recipeId().toString())));
            return choices;
        }

        private boolean isForced(PartialChoice choice) {
            ResourceLocation forced = ForcedRecipeGraphProjector
                    .forcedRecipeFor(choice.output(), forcedRecipes);
            return forced != null && forced.equals(choice.recipe().recipeId());
        }

        private boolean isSavedPreference(PartialChoice choice) {
            ResourceLocation preferred = preferences.preferredRecipe(choice.output());
            return preferred != null && preferred.equals(choice.recipe().recipeId());
        }

        private boolean isZeroGainVisitingCycle(RecipeNode candidate) {
            CandidateCycleRelation relation = cycleRelation(candidate, visiting);
            if (relation.ancestorInputs().isEmpty()) return false;
            for (AncestorInput ancestorInput : relation.ancestorInputs()) {
                for (RecipeNode back : partialCandidates(
                        ancestorInput.material(), NbtMatchMode.ANY)) {
                    for (IngredientRef backInput : back.inputs()) {
                        if (backInput.role() == DemandRole.CATALYST
                                || backInput.count() <= 0
                                || !backInput.alternatives().contains(candidate.output())) continue;
                        if (candidate.outputCount() < backInput.count()
                                || candidate.outputCount() % backInput.count() != 0L) {
                            continue;
                        }
                        long returnedBatches = candidate.outputCount() / backInput.count();
                        long returnedAncestor = returnedBatches * back.outputCount();
                        if (returnedAncestor <= ancestorInput.count()) return true;
                    }
                }
            }
            return false;
        }

        private List<RecipeNode> partialCandidates(MaterialRef wanted, NbtMatchMode matchMode) {
            return candidateCache.computeIfAbsent(new CandidateLookup(wanted, matchMode),
                    lookup -> ImmutableRecipeGraphProjector.candidates(
                            graph, lookup.material(), lookup.mode()));
        }

        private List<IngredientRef> normalizedInputs(RecipeNode recipe) {
            return normalizedInputCache.computeIfAbsent(recipe,
                    node -> PureDemandNormalizer.mergeEquivalent(node.inputs()));
        }

        private double inputStockCoverage(RecipeNode recipe) {
            budgetCheck.run();
            long required = 0L;
            long available = 0L;
            for (IngredientRef input : normalizedInputs(recipe)) {
                required += input.count();
                long matching = 0L;
                for (MaterialRef alternative : input.alternatives()) {
                    matching = saturatedAdd(matching,
                            stock.getOrDefault(alternative, 0L));
                }
                available += Math.min(input.count(), matching);
            }
            return required == 0L ? 1.0D : (double) available / (double) required;
        }

        private boolean isNonProductiveFamilyRecipe(RecipeNode recipe,
                                                     List<MaterialRef> family,
                                                     long batches) {
            if (!family.contains(recipe.output())) return false;
            long produced = (long) recipe.outputCount() * batches;
            PartialFamilyCost analysis = familyCosts.computeIfAbsent(
                    List.copyOf(family), PartialFamilyCost::new);
            long consumed = 0L;
            for (IngredientRef input : normalizedInputs(recipe)) {
                if (input.role() == DemandRole.CATALYST) continue;
                long scaled = scaledInputCount(input, batches);
                long cost = analysis.ingredientCost(input, scaled, new HashSet<>());
                if (cost >= COST_UNKNOWN) return false;
                consumed = Math.min(COST_UNKNOWN, consumed + cost);
                if (consumed >= produced) return true;
            }
            return consumed >= produced;
        }

        private void noteMissing(IngredientRef ingredient, long count) {
            MissingKey key = new MissingKey(Set.copyOf(ingredient.alternatives()),
                    ingredient.nbtMatchMode(), ingredient.role());
            IngredientRef previous = missing.get(key);
            if (previous != null) {
                long combined = ingredient.role() == DemandRole.CATALYST
                        ? Math.max(previous.count(), count)
                        : saturatedAdd(previous.count(), count);
                missing.put(key, previous.withCount(combined));
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
                        for (IngredientRef input : normalizedInputs(producer)) {
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

        private final ImmutableRecipeGraph graph;
        private final Set<MaterialRef> seeds;
        private final Map<MaterialRef, Integer> materialDepth;
        private final Map<RecipeNode, Integer> recipeDepth;

        private SeededReachability(ImmutableRecipeGraph graph,
                                   Map<MaterialRef, Long> available,
                                   Runnable budgetCheck) {
            this.graph = graph;
            Set<MaterialRef> present = new HashSet<>();
            for (Map.Entry<MaterialRef, Long> entry : available.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0L) continue;
                present.add(entry.getKey());
            }
            this.seeds = Set.copyOf(present);
            Map<MaterialRef, Integer> materialResult = new HashMap<>();
            Map<RecipeNode, Integer> recipeResult = new IdentityHashMap<>();
            Map<ResourceLocation, List<InputRef>> dependents = new HashMap<>();
            Set<ResourceLocation> inheritedOutputs = new HashSet<>();
            Map<RecipeNode, int[]> inputSatisfied = new IdentityHashMap<>();
            Map<RecipeNode, int[]> inputDepths = new IdentityHashMap<>();
            ArrayDeque<MaterialRef> queue = new ArrayDeque<>();
            for (MaterialRef seed : seeds) {
                materialResult.put(seed, 0);
                queue.add(seed);
            }
            for (List<RecipeNode> candidates : graph.recipesByOutput().values()) {
                for (RecipeNode recipe : candidates) {
                    budgetCheck.run();
                    if ("smithing".equals(recipe.modTypeId()) && recipe.inputs().size() == 3) {
                        inheritedOutputs.add(recipe.output().itemId());
                    }
                    int inputCount = recipe.inputs().size();
                    inputSatisfied.put(recipe, new int[inputCount]);
                    inputDepths.put(recipe, new int[inputCount]);
                    for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
                        Set<ResourceLocation> indexedItems = new HashSet<>();
                        for (MaterialRef alternative : recipe.inputs().get(inputIndex).alternatives()) {
                            budgetCheck.run();
                            if (!indexedItems.add(alternative.itemId())) continue;
                            dependents.computeIfAbsent(alternative.itemId(), ignored -> new ArrayList<>())
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
                // Reachability is part of this request's planning work. It must obey the same
                // deadline as recursive search, otherwise a broad NBT graph can overrun the
                // configured timeout before the first search state is expanded.
                budgetCheck.run();
                MaterialRef material = queue.removeFirst();
                int depth = materialResult.getOrDefault(material, 0);
                for (InputRef ref : dependents.getOrDefault(material.itemId(), List.of())) {
                    budgetCheck.run();
                    if (!ImmutableRecipeGraphProjector.matchesIngredient(material,
                            ref.recipe().inputs().get(ref.inputIndex()))
                            && !(material.nbt().isEmpty()
                            && inheritedOutputs.contains(material.itemId()))) continue;
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

        private boolean canReach(RecipeNode recipe) {
            return recipeDepth.containsKey(recipe)
                    || recipeDepth.containsKey(graph.recipesById().get(recipe.recipeId()));
        }

        private int depth(RecipeNode recipe) {
            return recipeDepth.getOrDefault(recipe, recipeDepth.getOrDefault(
                    graph.recipesById().get(recipe.recipeId()), UNREACHABLE_DEPTH));
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
