package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/** 执行响应最终状态重验、缓存提交和过期结果丢弃。 */
final class CraftPlanResponseFinalizer {
    private CraftPlanResponseFinalizer() {}

    static void submit(
            ServerPlayer player, ResourceLocation recipeId, long previewGeneration,
            PlanningSnapshot planningSnapshot, PlanResponseDraft responseDraft,
            ResourceKey<Level> planDimension, BlockPos planPosition,
            @Nullable StorageReference planStorageReference, PlanCache.Key cacheKey,
            @Nullable PureRecipePlanner.Result precomputedPlan,
            @Nullable CraftPlanGraph resolvedGraphForCache,
            int responseStepCount, int responseGraphNodes, boolean responseFeasible,
            long planStartNanos, CraftPlanResultDispatcher.Sink sink) {
        PlanningProgressServer.finalizing(player.getUUID(), previewGeneration);
        CraftPlanningRuntime.planRequests().submitResponse(
                planningSnapshot, responseDraft, player.getServer()::execute,
                current -> {
                    boolean accepted = PlanningStateValidator.revalidatePreview(
                            player, current, responseDraft, planDimension, planPosition,
                            CraftPlanningRuntime.planRequests(), planStorageReference);
                    if (!accepted) {
                        RSIntegrationMod.debug(
                                "[RSI-plan] Final state validation rejected preview recipe={} "
                                        + "generation={} requestCurrent={} revisionCurrent={}",
                                recipeId, current.requestGeneration(),
                                CraftPlanningRuntime.planRequests().isCurrent(
                                        player.getUUID(), current.requestGeneration()),
                                CraftPlanningRevision.isCurrent(current.recipeRevision()));
                    }
                    return accepted;
                },
                plan -> {
                    if (previewGeneration != 0L
                            && !CraftPlanningRuntime.planRequests().isCurrent(
                                    player.getUUID(), previewGeneration)) {
                        RSIntegrationMod.debug(
                                "[RSI-tryBuildPlan] Discarding stale finalized response: recipeId={}",
                                recipeId);
                        sink.cancelled(Component.translatable(
                                "rsi.plan.failure.planning_cancelled"));
                        return;
                    }
                    CraftPlanningRuntime.planCache().put(
                            cacheKey, plan, planningSnapshot,
                            CraftPlanDecisionRules.canUsePrecomputedPlan(precomputedPlan)
                                    ? precomputedPlan : null,
                            resolvedGraphForCache, System.nanoTime());
                    RSIntegrationMod.debug(
                            "[RSI-tryBuildPlan] SENDING PlanResponsePacket: "
                                    + "recipeId={} steps={} graphNodes={} feasible={} player={}",
                            recipeId, responseStepCount, responseGraphNodes,
                            responseFeasible, player.getGameProfile().getName());
                    sink.success(plan, planningSnapshot);
                    PerformanceMonitor.recordPlanBuild(
                            System.nanoTime() - planStartNanos,
                            plan.graph() != null
                                    ? plan.graph().nodes().size() : responseStepCount);
                    RSIntegrationMod.debug(
                            "[RSI-tryBuildPlan] PlanResponsePacket SENT: recipeId={}",
                            recipeId);
                },
                failure -> {
                    if (failure instanceof RejectedExecutionException) {
                        sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                IntegrationDiagnostic.error(
                                        IntegrationDiagnosticCode.PLANNER_BUSY));
                    } else if (failure instanceof CancellationException
                            || failure instanceof AsyncPlanningCoordinator
                            .StalePlanningResultException) {
                        sink.cancelled(Component.translatable(
                                "rsi.plan.failure.planning_cancelled"));
                    } else {
                        RSIntegrationMod.LOGGER.error(
                                "[RSI-plan] Response finalization failed for {}",
                                recipeId, failure);
                        sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                IntegrationDiagnostic.error(
                                        IntegrationDiagnosticCode.INTERNAL_ERROR));
                    }
                });
    }
}
