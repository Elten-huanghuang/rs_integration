package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialVariantPreferences;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanRequestService;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.crafting.PreviewRateLimiter;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.RSGridMatrixNormalizer;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 合成请求的准入、排队、取消与服务端生命周期。 */
public final class CraftRequestCoordinator {
    private CraftRequestCoordinator() {}

    record Request(ResourceLocation recipeId, boolean preview, Map<String, String> forcedRecipes,
                   @Nullable ResourceLocation dim, @Nullable BlockPos pos, int repeatCount,
                   boolean inferMode, @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
                   long requestId, long confirmedPreviewRequestId, OutputDestination outputDestination,
                   @Nullable StorageReference storageReference, boolean maximize,
                   MachineSelectionMode machineSelectionMode, Map<String, ItemStack> materialLocks,
                   PlanningPreferences planningPreferences) {}

    static long beginPreview(ServerPlayer player, long requestId, ResourceLocation recipeId) {
        long generation = CraftPlanningRuntime.planRequests().begin(player.getUUID());
        if (generation != 0L) {
            PlanningProgressServer.begin(player.getUUID(), requestId, generation, recipeId);
        }
        return generation;
    }

    static void accept(ServerPlayer player, Request request, long previewGeneration,
                       boolean preparationRequest) {
        Consumer<ServerPlayer> action = readyPlayer ->
                executeRequest(readyPlayer, request, previewGeneration, preparationRequest);
        if (warmUpReady(player.serverLevel())) {
            action.accept(player);
            return;
        }
        if (RecipeIndex.generationBuildFailed()) {
            Component message = Component.translatable("rsi.plan.failure.catalog_unavailable");
            player.sendSystemMessage(message);
            PlanningProgressServer.fail(player, previewGeneration, message);
            return;
        }
        boolean queued = CraftRequestQueues.offerWarmUp(
                new DeferredCraftRequestQueue.Entry<>(
                player.getUUID(), request.preview, previewGeneration, action));
        if (!queued) {
            Component message = Component.translatable("rsi.plan.failure.planner_busy");
            player.sendSystemMessage(message);
            PlanningProgressServer.fail(player, previewGeneration, message);
            return;
        }
        if (request.preview) {
            PlanningProgressServer.warmingUp(player.getUUID(), previewGeneration);
        } else {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.catalog_loading"));
        }
        RSIntegrationMod.debug(
                "[RSI-Generic] deferred until recipe warm-up: recipeId={} preview={} queued={}",
                request.recipeId, request.preview, CraftRequestQueues.warmUpSize());
    }

    private static boolean warmUpReady(ServerLevel level) {
        return RecipeIndex.isReady(level);
    }

    private static void executeRequest(ServerPlayer player, Request request,
                                       long previewGeneration, boolean preparationRequest) {
        try {
            if (request.preview) {
                if (!CraftPlanningRuntime.planRequests().isCurrent(player.getUUID(), previewGeneration)) return;
                PlanningProgressServer.running(player.getUUID(), previewGeneration,
                        PlanningProgressSnapshot.Phase.PREPARING);
                RSIntegrationMod.debug(
                        "[RSI-Generic] handle() -> tryBuildPlan: recipeId={}", request.recipeId);
                if (request.maximize) {
                    CraftMaxCraftableSearchService.start(
                            player, new CraftMaxCraftableSearchService.Request(
                                    request.recipeId, request.forcedRecipes, request.dim, request.pos,
                                    request.baseItem, request.targetOutput, request.requestId,
                                    request.storageReference, request.materialLocks, request.planningPreferences),
                            previewGeneration);
                } else {
                    CraftPlanningApplicationService.tryBuildPlan(player, request.recipeId, request.forcedRecipes,
                            request.dim, request.pos, request.repeatCount, request.baseItem,
                            request.targetOutput, request.requestId, previewGeneration,
                            request.storageReference, request.materialLocks,
                            request.planningPreferences);
                }
            } else {
                RSIntegrationMod.debug(
                        "[RSI-Generic] handle() -> queue execution: recipeId={} forced={} destination={}",
                        request.recipeId, request.forcedRecipes.size(), request.outputDestination);
                queueExecutionRequest(player, request, preparationRequest);
            }
        } catch (Throwable e) {
            RSIntegrationMod.LOGGER.error("[RSI-Generic] Failed for {}:", request.recipeId, e);
            try {
                Component message = CraftFailureMessages.build(e, request.recipeId);
                player.sendSystemMessage(message);
                if (request.preview) PlanningProgressServer.fail(player, previewGeneration, message);
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Failed to send error message to player", ex);
            }
        }
    }

    private static void queueExecutionRequest(ServerPlayer player, Request request,
                                              boolean preparationRequest) {
        queueExecutionRequest(player,
                readyPlayer -> executeQueuedRequest(
                        readyPlayer, request, preparationRequest), request.recipeId);
    }

    private static void queueExecutionRequest(ServerPlayer player,
                                               Consumer<ServerPlayer> action,
                                               ResourceLocation recipeId) {
        Consumer<ServerPlayer> guardedAction = readyPlayer -> {
            try {
                action.accept(readyPlayer);
            } catch (CraftingPlanningTimeoutException timeout) {
                PerformanceMonitor.recordResolveTimeout();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-exec] deferred server-thread resolver timeout recipe={}", recipeId);
                readyPlayer.sendSystemMessage(Component.translatable(
                        "rsi.plan.failure.dynamic_plan_unavailable"));
            } catch (Throwable failure) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Deferred execution failed for {}:", recipeId, failure);
                readyPlayer.sendSystemMessage(CraftFailureMessages.build(failure, recipeId));
            }
        };
        boolean queued = CraftRequestQueues.offerExecution(
                new DeferredCraftRequestQueue.Entry<>(
                player.getUUID(), false, 0L,
                guardedAction));
        if (!queued) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planner_busy"));
            return;
        }
        RSIntegrationMod.debug("[RSI-Generic] execution queued: recipeId={} queueSize={}",
                recipeId, CraftRequestQueues.executionSize());
    }

    private static void executeQueuedRequest(ServerPlayer player, Request request,
                                             boolean preparationRequest) {
        try {
            if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                    && !RSGridMatrixNormalizer.returnToSelectedNetwork(
                            player, request.storageReference)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.matrix_return_failed"));
                return;
            }
            RSIntegrationMod.debug(
                    "[RSI-Generic] queued execution -> tryResolve: recipeId={} forced={} destination={}",
                    request.recipeId, request.forcedRecipes.size(), request.outputDestination);
            CraftExecutionRequestService.tryResolve(player, request.recipeId, request.forcedRecipes, request.dim, request.pos,
                    request.repeatCount, request.inferMode, request.baseItem, request.targetOutput,
                    request.outputDestination, request.machineSelectionMode,
                    request.storageReference, request.materialLocks,
                    false, preparationRequest, request.planningPreferences, request.confirmedPreviewRequestId);
        } catch (CraftingPlanningTimeoutException timeout) {
            PerformanceMonitor.recordResolveTimeout();
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-exec] queued server-thread resolver timeout recipe={}", request.recipeId);
            player.sendSystemMessage(Component.translatable(
                    "rsi.plan.failure.dynamic_plan_unavailable"));
        } catch (Throwable e) {
            RSIntegrationMod.LOGGER.error("[RSI-Generic] Queued execution failed for {}:",
                    request.recipeId, e);
            try {
                player.sendSystemMessage(CraftFailureMessages.build(e, request.recipeId));
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Failed to send queued execution error", ex);
            }
        }
    }

    private static void tickExecutionRequests(MinecraftServer server) {
        DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                CraftRequestQueues.pollExecution();
        if (request == null) return;
        if (System.nanoTime() - request.queuedAtNanos()
                >= CraftRequestQueues.CATALOG_TIMEOUT_NANOS) {
            failExpiredCatalogRequest(server, request);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            request.payload().accept(player);
        }
    }

    /** Runs at most one valid deferred request after a complete generation is ready. */
    public static void tickWarmUpRequests(MinecraftServer server) {
        PlanningProgressServer.tick(server, CraftRequestCoordinator::cancelTimedOutPlanning);
        // Runtime recipe drift is handled from the server tick, never from the
        // request handler, so the first craft request cannot synchronously rebuild
        // the complete recipe catalog on its network task.
        RecipeIndex.refreshDynamicRuntimeIfNeeded(server.overworld());
        expireCatalogRequest(server, CraftRequestQueues.pollExpiredWarmUp(System.nanoTime()));
        expireCatalogRequest(server, CraftRequestQueues.pollExpiredExecution(System.nanoTime()));
        tickTypedPreviewRequests(server);
        if (warmUpReady(server.overworld())) {
            tickExecutionRequests(server);
        }
        if (RecipeIndex.generationBuildFailed()) {
            // Keep even failure notifications incremental. A full warm-up queue
            // can contain dozens of clicks and draining it here used to create a
            // server-thread burst exactly when the recipe catalog was unhealthy.
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                    CraftRequestQueues.pollWarmUp();
            if (request != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                if (player != null) {
                    Component message = Component.translatable("rsi.plan.failure.catalog_unavailable");
                    player.sendSystemMessage(message);
                    if (request.preview()) {
                        PlanningProgressServer.fail(player, request.generation(), message);
                    }
                }
            }
            return;
        }
        if (!warmUpReady(server.overworld())) return;
        int remaining = CraftRequestQueues.warmUpSize();
        while (remaining-- > 0) {
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                    CraftRequestQueues.pollWarmUp();
            if (request == null) return;
            if (System.nanoTime() - request.queuedAtNanos()
                    >= CraftRequestQueues.CATALOG_TIMEOUT_NANOS) {
                failExpiredCatalogRequest(server, request);
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
            if (player == null) continue;
            if (request.preview()
                    && !CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation())) continue;
            request.payload().accept(player);
            return;
        }
    }

    private static void expireCatalogRequest(MinecraftServer server,
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        if (request == null) return;
        failExpiredCatalogRequest(server, request);
    }

    private static void failExpiredCatalogRequest(MinecraftServer server,
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null || request.preview()
                && !CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation())) return;
        Component message = Component.translatable("rsi.plan.failure.catalog_wait_timeout");
        player.sendSystemMessage(message);
        if (request.preview()) {
            PlanningProgressServer.fail(player, request.generation(), message);
            cancelTimedOutPlanning(request.playerId(), request.generation());
        }
    }

    private static void cancelTimedOutPlanning(UUID playerId, long generation) {
        PlanRequestService requests = CraftPlanningRuntime.planRequests();
        requests.cancel(playerId, generation);
    }

    public static void cancelPlanning(ServerPlayer player, long requestId) {
        long generation = PlanningProgressServer.generationForRequest(player.getUUID(), requestId);
        if (generation == 0L || !CraftPlanningRuntime.planRequests().cancel(player.getUUID(), generation)) return;
        CraftPlanningRuntime.typedPreviewRequests().remove(player.getUUID(), generation);
        CraftRequestQueues.cancelPreview(player.getUUID(), generation);
        PlanningProgressServer.cancelled(player, generation);
    }

    public static void finishPlanning(UUID playerId, long generation) {
        CraftPlanningRuntime.planRequests().complete(playerId, generation);
    }

    private static void tickTypedPreviewRequests(MinecraftServer server) {
        int admissions;
        int timeoutMs;
        try {
            admissions = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK.get();
            timeoutMs = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS.get();
        } catch (Exception ignored) {
            admissions = RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK;
            timeoutMs = RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS;
        }
        TypedPreviewAdmissionQueue queue = CraftPlanningRuntime.typedPreviewRequests();
        int admitted = queue.run(Math.max(1, admissions), System.nanoTime(),
                Math.max(1, timeoutMs) * 1_000_000L, request -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                    return player != null && !player.hasDisconnected()
                            && CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation());
                });
        PerformanceMonitor.recordTypedPreviewAdmitted(admitted, queue.size());
    }

    public static void onPlayerLogout(UUID playerId) {
        CraftPlanResultDispatcher.clear(playerId);
        CraftRequestQueues.removePlayer(playerId);
        CraftPlanningRuntime.removePlayer(playerId);
        PlanningProgressServer.remove(playerId);
    }

    /** Cancels preview workers during server shutdown before world objects are torn down. */
    public static synchronized void cancelAllPlanning() {
        CraftPlanResultDispatcher.clear(null);
        CraftPlanningRuntime.shutdown();
        CraftRequestQueues.clear();
        PlanningProgressServer.clear();
        ImmutableRecipeGraphProjector.clearCache();
    }

    /** Applies server-configured planner resource limits by replacing the bounded executor. */
    public static synchronized void reloadPlanningConfig() {
        CraftPlanResultDispatcher.clear(null);
        MaterialVariantPreferences.refresh();
        CraftPlanningRuntime.reload();
        PlanningProgressServer.clear();
    }

}
