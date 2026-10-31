package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

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
            PlanningProgressServer.running(snapshot.playerId(), snapshot.requestGeneration(),
                    PlanningProgressSnapshot.Phase.SPECIAL_RECIPE);
            serverExecutor.execute(() -> rollback.accept(
                    new PlanningThreadContext.MainThreadPlanningRequiredException("special recipe planning")));
            return;
        }
        PlanningKey key = new PlanningKey(snapshot.playerId(), snapshot.recipeRevision(),
                snapshot.recipeId(), snapshot.availableItems(),
                snapshot.forcedRecipes(), snapshot.preferences(), snapshot.recipeGraph(),
                snapshot.networkFingerprint(), snapshot.bindingFingerprint(),
                snapshot.bindingBlockedOutputIds(), snapshot.mainThreadOnly(), repeatCount,
                maxSteps, maxSearchStates, maxMemoizedFailures, timeoutMs);
        PlanningProgressServer.queued(snapshot.playerId(), snapshot.requestGeneration(),
                PlanningProgressSnapshot.Phase.SEARCH);
        coordinator.submitShared(key, snapshot, ignored -> compute(snapshot, repeatCount, maxSteps,
                        maxSearchStates, maxMemoizedFailures, timeoutMs), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(),
                (callbackSnapshot, result) -> commit.accept(new CompletedPlan(callbackSnapshot, result)), rollback);
    }

    private static PureRecipePlanner.Result compute(PlanningSnapshot snapshot, int repeatCount,
                                                     int maxSteps,
                                                     int maxSearchStates, int maxMemoizedFailures,
                                                     int timeoutMs) {
        PlanningSession session = new PlanningSession(snapshot.requestGeneration(), timeoutMs);
        return PlanningLookupCache.run(() -> computeInScope(snapshot, repeatCount, maxSteps,
                maxSearchStates, maxMemoizedFailures, session));
    }

    public void submitRouted(PlanningSnapshot snapshot, RouteInputs routing, int repeatCount,
                             Executor serverExecutor, int maxSteps, int maxSearchStates,
                             int maxMemoizedFailures, int timeoutMs,
                             Consumer<RoutedPlan> commit, Consumer<Throwable> rollback) {
        PlanningKey planning = new PlanningKey(snapshot.playerId(), snapshot.recipeRevision(),
                snapshot.recipeId(), snapshot.availableItems(),
                snapshot.forcedRecipes(), snapshot.preferences(), snapshot.recipeGraph(),
                snapshot.networkFingerprint(), snapshot.bindingFingerprint(),
                snapshot.bindingBlockedOutputIds(), snapshot.mainThreadOnly(), repeatCount,
                maxSteps, maxSearchStates, maxMemoizedFailures, timeoutMs);
        PlanningProgressServer.queued(snapshot.playerId(), snapshot.requestGeneration(),
                PlanningProgressSnapshot.Phase.DEPENDENCIES);
        coordinator.submitShared(new RoutedKey(planning, routing,
                        List.copyOf(routing.available().entrySet())), snapshot,
                ignored -> computeRouted(snapshot, routing, repeatCount, maxSteps,
                        maxSearchStates, maxMemoizedFailures, timeoutMs), serverExecutor,
                current -> PlanningStateValidator.sameState(snapshot, current),
                (current, result) -> commit.accept(new RoutedPlan(current, routing,
                        result.inspection(), result.plan(), result.forcedRecipesProjected())),
                rollback);
    }

    static RoutedPlan computeRouted(PlanningSnapshot snapshot, RouteInputs routing, int repeatCount,
                                    int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                                    int timeoutMs) {
        return PlanningLookupCache.run(() -> {
            PlanningThreadContext.throwIfCancelled();
            PlanningSession session = new PlanningSession(snapshot.requestGeneration(), timeoutMs);
            long started = session.startedNanos();
            setPhase(session, snapshot, PlanningSession.Phase.DEPENDENCY_PROJECTION);
            ImmutableRecipeGraph scopedGraph = ImmutableRecipeGraphProjector.restrictToDependencies(
                    snapshot.recipeGraph(), snapshot.recipeId());
            ImmutableRecipeGraph forcedGraph = ForcedRecipeGraphProjector.apply(
                    scopedGraph, snapshot.forcedRecipes());
            boolean forcedRecipesProjected =
                    snapshot.forcedRecipes().isEmpty() || forcedGraph != null;
            ImmutableRecipeGraph routingGraph = forcedGraph != null ? forcedGraph : scopedGraph;
            PureDemandTreeInspector.Result inspection;
            try {
                setPhase(session, snapshot, PlanningSession.Phase.DEMAND_TREE);
                inspection = PureDemandTreeInspector.inspectWithDeadline(routingGraph,
                        routing.available(), snapshot.recipeId(), repeatCount, routing.maxNodes(),
                        routing.catalystOutputs(), routing.catalystRecipes(), routing.incompatibleOutputs(),
                        session.deadlineNanos());
            } finally {
                PerformanceMonitor.recordDemandTreeInspection(
                        System.nanoTime() - started);
            }
            PlanningThreadContext.throwIfCancelled();
            PureRecipePlanner.Result plan = forcedRecipesProjected
                    && inspection.backgroundCompatible()
                    && !snapshot.mainThreadOnly()
                    ? computeInScope(snapshot, repeatCount, maxSteps, maxSearchStates,
                            maxMemoizedFailures, session, routingGraph) : null;
            return new RoutedPlan(snapshot, routing, inspection, plan, forcedRecipesProjected);
        });
    }

    private static PureRecipePlanner.Result computeInScope(PlanningSnapshot snapshot, int repeatCount,
                                                            int maxSteps, int maxSearchStates,
                                                            int maxMemoizedFailures,
                                                            PlanningSession session) {
        return computeInScope(snapshot, repeatCount, maxSteps, maxSearchStates,
                maxMemoizedFailures, session, null);
    }

    private static PureRecipePlanner.Result computeInScope(PlanningSnapshot snapshot, int repeatCount,
                                                            int maxSteps, int maxSearchStates,
                                                            int maxMemoizedFailures,
                                                            PlanningSession session,
                                                            ImmutableRecipeGraph projectedGraph) {
        PlanningThreadContext.throwIfCancelled();
        setPhase(session, snapshot, PlanningSession.Phase.PREPARATION);
        Map<ImmutableRecipeGraph.MaterialRef, Long> stock =
                ImmutableRecipeGraphProjector.projectAvailability(
                        snapshot.availableItems());
        ImmutableRecipeGraph planningGraph;
        if (projectedGraph != null) {
            // 路由阶段已经完成依赖裁剪和强制选择投影，直接复用该图。
            planningGraph = projectedGraph;
        } else {
            setPhase(session, snapshot, PlanningSession.Phase.DEPENDENCY_PROJECTION);
            ImmutableRecipeGraph scopedGraph =
                    ImmutableRecipeGraphProjector.restrictToDependencies(
                            snapshot.recipeGraph(), snapshot.recipeId());
            // Inventory matching is performed lazily against the request-local stock buckets.
            // Only smithing needs a derived graph because its output inherits the base NBT;
            // PureRecipePlanner creates that single specialization layer.
            planningGraph = ForcedRecipeGraphProjector.apply(
                    scopedGraph, snapshot.forcedRecipes());
        }
        if (planningGraph == null) {
            return new PureRecipePlanner.Result(false, List.of(), List.of(), Map.of());
        }
        RecipeNode target = planningGraph.recipesById().get(snapshot.recipeId());
        if (target == null) {
            return new PureRecipePlanner.Result(false, List.of(), List.of(), Map.of());
        }
        setPhase(session, snapshot, PlanningSession.Phase.RECURSIVE_SEARCH);
        long searchStarted = System.nanoTime();
        long deadlineNanos = session.deadlineNanos();
        PureRecipePlanner.Result result = resolveInScope(
                planningGraph, stock, target, repeatCount, maxSteps,
                maxSearchStates, maxMemoizedFailures, deadlineNanos,
                snapshot.forcedRecipes(), snapshot.preferences());
        long elapsedNanos = System.nanoTime() - searchStarted;
        PerformanceMonitor.recordPurePlanningSearch(
                result, elapsedNanos);
        if (result.status() == PureRecipePlanner.Status.TIME_LIMIT) {
            PlanningLookupCache.Stats lookupStats = PlanningLookupCache.currentStats();
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-plan] Pure planning timed out: recipe={} phase={} elapsedMs={} states={} backtracks={} memoHits={} stockTypes={} recipes={} nbtParses={} outputIndexBuilds={} outputScans={} reachabilityBuilds={} reachabilityMs={} reachabilityMaxMs={} seedCandidates={} seeds={} indexedEdges={} matchedEdges={} reachedRecipes={} stateAllocations={}",
                    snapshot.recipeId(), session.phase(), elapsedNanos / 1_000_000L, result.expandedStates(),
                    result.backtracks(), result.memoHits(), stock.size(),
                    planningGraph.recipesById().size(), lookupStats.nbtParses(),
                    lookupStats.outputIndexBuilds(), lookupStats.outputScans(),
                    lookupStats.reachabilityBuilds(), lookupStats.reachabilityNanos() / 1_000_000L,
                    lookupStats.reachabilityMaxNanos() / 1_000_000L,
                    lookupStats.reachabilitySeedCandidates(),
                    lookupStats.reachabilitySeeds(), lookupStats.reachabilityIndexedEdges(),
                    lookupStats.reachabilityMatchedEdges(), lookupStats.reachabilityReachedRecipes(),
                    lookupStats.reachabilityStateAllocations());
        }
        return result;
    }

    /**
     * 按目标配方输入规划；若目标配方是“没有库存支撑的反向换算”（例如 Prefab 的
     * 分解压缩萤石），按输入规划必然绕回自身产物，形成“9 个萤石压成 1 个压缩、
     * 再分解回 9 个萤石”的闭环，此时改为按目标产物需求量规划，让正向配方
     * （如 4 萤石粉 → 1 萤石）接手。
     */
    static PureRecipePlanner.Result resolveInScope(ImmutableRecipeGraph graph,
                                                   Map<ImmutableRecipeGraph.MaterialRef, Long> stock,
                                                   RecipeNode target, int repeatCount,
                                                   int maxSteps, int maxSearchStates,
                                                   int maxMemoizedFailures, long deadlineNanos,
                                                   Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                                   PlanningPreferences preferences) {
        boolean amplification = SelfAmplifyingRecipePolicy.isSelfAmplifying(target);
        long existingOutputCount = 0L;
        Map<ResourceLocation, ResourceLocation> selectedRecipes = forcedRecipes;
        if (amplification) {
            IngredientRef output = SelfAmplifyingRecipePolicy.planningRoots(target, repeatCount, 0L).get(0);
            for (var entry : stock.entrySet()) {
                if (ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), output)) {
                    existingOutputCount = Math.addExact(existingOutputCount, Math.max(0L, entry.getValue()));
                }
            }
            // 终端指定的是实际执行的复制配方，不能被同产物的其他生产者替换。
            selectedRecipes = new LinkedHashMap<>(forcedRecipes);
            selectedRecipes.put(ForcedRecipeGraphProjector.preferenceKey(target.output()), target.recipeId());
        }
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, stock, SelfAmplifyingRecipePolicy.planningRoots(target, repeatCount, existingOutputCount),
                maxSteps, maxSearchStates, maxMemoizedFailures, deadlineNanos,
                System::nanoTime, planningRootGuard(target), selectedRecipes, preferences);
        if (amplification) result = result.withAdditionalOutputTarget();
        if (result.feasible()
                || !SelfAmplifyingRecipePolicy.isUnseededReverseTarget(target, graph, stock)) {
            return result;
        }
        PureRecipePlanner.Result direct = PureRecipePlanner.resolve(
                graph, stock,
                SelfAmplifyingRecipePolicy.directProductionRoots(target, repeatCount),
                maxSteps, maxSearchStates, maxMemoizedFailures, deadlineNanos,
                System::nanoTime, Set.of(), forcedRecipes, preferences).withOutputTarget();
        return direct.feasible() || !direct.missing().isEmpty() ? direct : result;
    }

    static Set<ImmutableRecipeGraph.MaterialRef> planningRootGuard(
            RecipeNode target) {
        return SelfAmplifyingRecipePolicy.isSelfAmplifying(target)
                ? Set.of() : Set.of(target.output());
    }

    private static void setPhase(PlanningSession session, PlanningSnapshot snapshot,
                                 PlanningSession.Phase phase) {
        session.phase(phase);
        PlanningProgressSnapshot.Phase visible = switch (phase) {
            case PREPARATION -> PlanningProgressSnapshot.Phase.PREPARING;
            case DEPENDENCY_PROJECTION -> PlanningProgressSnapshot.Phase.DEPENDENCIES;
            case DEMAND_TREE -> PlanningProgressSnapshot.Phase.DEMAND_TREE;
            case INVENTORY_BINDING, SMITHING_STATE_BINDING -> PlanningProgressSnapshot.Phase.INVENTORY;
            case RECURSIVE_SEARCH -> PlanningProgressSnapshot.Phase.SEARCH;
            case RESULT_HANDOFF -> PlanningProgressSnapshot.Phase.FINALIZING;
            case QUEUED, UNKNOWN -> PlanningProgressSnapshot.Phase.PREPARING;
        };
        PlanningProgressServer.running(snapshot.playerId(), snapshot.requestGeneration(), visible);
    }

    static long deadlineAfterMillis(long startedNanos, int timeoutMs) {
        long budgetNanos = Math.max(1L, timeoutMs) * 1_000_000L;
        long deadline = startedNanos + budgetNanos;
        return deadline < startedNanos ? Long.MAX_VALUE : deadline;
    }

    /** Keeps a background result inseparable from the immutable state that produced it. */
    public record CompletedPlan(PlanningSnapshot snapshot, PureRecipePlanner.Result result) {}

    public record RouteInputs(Map<ImmutableRecipeGraph.MaterialRef, Long> available,
                              int maxNodes, Set<ResourceLocation> catalystOutputs,
                              Set<ResourceLocation> catalystRecipes,
                              Set<ResourceLocation> incompatibleOutputs) {
        public RouteInputs {
            available = Collections.unmodifiableMap(new LinkedHashMap<>(available));
            catalystOutputs = Set.copyOf(catalystOutputs);
            catalystRecipes = Set.copyOf(catalystRecipes);
            incompatibleOutputs = Set.copyOf(incompatibleOutputs);
        }

        public boolean matchesPolicy(int nodes, Set<ResourceLocation> outputs,
                                      Set<ResourceLocation> recipes,
                                      Set<ResourceLocation> incompatible) {
            return maxNodes == nodes && catalystOutputs.equals(outputs)
                    && catalystRecipes.equals(recipes) && incompatibleOutputs.equals(incompatible);
        }
    }

    public record RoutedPlan(PlanningSnapshot snapshot, RouteInputs routing,
                             PureDemandTreeInspector.Result inspection,
                             @Nullable PureRecipePlanner.Result plan,
                             boolean forcedRecipesProjected) {}

    private record RoutedKey(PlanningKey planning, RouteInputs routing,
                              List<Map.Entry<ImmutableRecipeGraph.MaterialRef, Long>> availabilityOrder) {}

    private record PlanningKey(UUID playerId, long recipeRevision,
                               ResourceLocation recipeId,
                               Map<StackKey, Long> availableItems,
                               Map<ResourceLocation, ResourceLocation> forcedRecipes,
                               PlanningPreferences preferences,
                               ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                               String bindingFingerprint,
                               Set<ResourceLocation> bindingBlockedOutputIds,
                               boolean mainThreadOnly, int repeatCount, int maxSteps,
                               int maxSearchStates, int maxMemoizedFailures, int timeoutMs) {}
}
