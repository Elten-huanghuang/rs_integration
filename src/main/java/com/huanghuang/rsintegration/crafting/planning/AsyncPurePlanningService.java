package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
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
                    new PlanningThreadContext.MainThreadPlanningFallbackException("special recipe planning")));
            return;
        }
        PlanningKey key = new PlanningKey(snapshot.playerId(), snapshot.recipeRevision(),
                snapshot.recipeId(), snapshot.availableItems(), snapshot.forcedRecipes(),
                snapshot.recipeGraph(), snapshot.networkFingerprint(), snapshot.bindingFingerprint(),
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
                snapshot.recipeId(), snapshot.availableItems(), snapshot.forcedRecipes(),
                snapshot.recipeGraph(), snapshot.networkFingerprint(), snapshot.bindingFingerprint(),
                snapshot.bindingBlockedOutputIds(), snapshot.mainThreadOnly(), repeatCount,
                maxSteps, maxSearchStates, maxMemoizedFailures, timeoutMs);
        PlanningProgressServer.queued(snapshot.playerId(), snapshot.requestGeneration(),
                PlanningProgressSnapshot.Phase.DEPENDENCIES);
        coordinator.submitShared(new RoutedKey(planning, routing,
                        List.copyOf(routing.available().entrySet())), snapshot,
                ignored -> computeRouted(snapshot, routing, repeatCount, maxSteps,
                        maxSearchStates, maxMemoizedFailures, timeoutMs), serverExecutor,
                current -> current.recipeRevision() == snapshot.recipeRevision(),
                (current, result) -> commit.accept(new RoutedPlan(current, routing,
                        result.inspection(), result.plan())), rollback);
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
            PureDemandTreeInspector.Result inspection;
            try {
                setPhase(session, snapshot, PlanningSession.Phase.DEMAND_TREE);
                inspection = PureDemandTreeInspector.inspectWithDeadline(scopedGraph,
                        routing.available(), snapshot.recipeId(), repeatCount, routing.maxNodes(),
                        routing.catalystOutputs(), routing.catalystRecipes(), routing.incompatibleOutputs(),
                        session.deadlineNanos());
            } finally {
                PerformanceMonitor.recordDemandTreeInspection(
                        System.nanoTime() - started);
            }
            PlanningThreadContext.throwIfCancelled();
            PureRecipePlanner.Result plan = inspection.backgroundCompatible()
                    && !snapshot.mainThreadOnly() && snapshot.forcedRecipes().isEmpty()
                    ? computeInScope(snapshot, repeatCount, maxSteps, maxSearchStates,
                            maxMemoizedFailures, session, scopedGraph) : null;
            return new RoutedPlan(snapshot, routing, inspection, plan);
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
                                                            ImmutableRecipeGraph routedGraph) {
        PlanningThreadContext.throwIfCancelled();
        setPhase(session, snapshot, PlanningSession.Phase.PREPARATION);
        Map<ImmutableRecipeGraph.MaterialRef, Integer> stock =
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems());
        setPhase(session, snapshot, PlanningSession.Phase.DEPENDENCY_PROJECTION);
        ImmutableRecipeGraph scopedGraph = routedGraph != null ? routedGraph
                : ImmutableRecipeGraphProjector.restrictToDependencies(
                        snapshot.recipeGraph(), snapshot.recipeId());
        // Inventory matching is performed lazily against the request-local stock buckets.
        // Only smithing needs a derived graph because its output inherits the base NBT;
        // PureRecipePlanner creates that single specialization layer.
        ImmutableRecipeGraph planningGraph = scopedGraph;
        RecipeNode target = planningGraph.recipesById().get(snapshot.recipeId());
        if (target == null) {
            return new PureRecipePlanner.Result(false, List.of(), List.of(), Map.of());
        }
        List<IngredientRef> roots = SelfAmplifyingRecipePolicy.scaleTargetInputs(
                target, repeatCount);
        setPhase(session, snapshot, PlanningSession.Phase.RECURSIVE_SEARCH);
        long searchStarted = System.nanoTime();
        long deadlineNanos = session.deadlineNanos();
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                planningGraph, stock, roots, maxSteps,
                maxSearchStates, maxMemoizedFailures, deadlineNanos);
        long elapsedNanos = System.nanoTime() - searchStarted;
        PerformanceMonitor.recordPurePlanningSearch(
                result, elapsedNanos);
        if (result.status() == PureRecipePlanner.Status.TIME_LIMIT) {
            PlanningLookupCache.Stats lookupStats = PlanningLookupCache.currentStats();
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-plan] Pure planning timed out: recipe={} phase={} elapsedMs={} states={} backtracks={} memoHits={} stockTypes={} recipes={} nbtParses={} outputIndexBuilds={} outputScans={}",
                    snapshot.recipeId(), session.phase(), elapsedNanos / 1_000_000L, result.expandedStates(),
                    result.backtracks(), result.memoHits(), stock.size(),
                    planningGraph.recipesById().size(), lookupStats.nbtParses(),
                    lookupStats.outputIndexBuilds(), lookupStats.outputScans());
        }
        return result;
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

    public record RouteInputs(Map<ImmutableRecipeGraph.MaterialRef, Integer> available,
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
                             @Nullable PureRecipePlanner.Result plan) {}

    private record RoutedKey(PlanningKey planning, RouteInputs routing,
                              List<Map.Entry<ImmutableRecipeGraph.MaterialRef, Integer>> availabilityOrder) {}

    private record PlanningKey(UUID playerId, long recipeRevision,
                               ResourceLocation recipeId,
                               Map<StackKey, Integer> availableItems,
                               Map<ResourceLocation, ResourceLocation> forcedRecipes,
                               ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                               String bindingFingerprint,
                               Set<ResourceLocation> bindingBlockedOutputIds,
                               boolean mainThreadOnly, int repeatCount, int maxSteps,
                               int maxSearchStates, int maxMemoizedFailures, int timeoutMs) {}
}
