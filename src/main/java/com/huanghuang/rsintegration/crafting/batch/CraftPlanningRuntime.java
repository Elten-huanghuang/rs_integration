package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanRequestService;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;

import java.util.UUID;

/** 规划线程池、typed preview 准入队列和计划缓存的统一生命周期。 */
final class CraftPlanningRuntime {
    private static volatile PlanRequestService planRequests = newPlanRequestService(
            CraftingPlanningConfig.defaults());
    private static volatile TypedPreviewAdmissionQueue typedPreviewRequests =
            new TypedPreviewAdmissionQueue(
                    RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY);
    private static final PlanCache PLAN_CACHE = new PlanCache();

    private CraftPlanningRuntime() {}

    static PlanRequestService planRequests() {
        return planRequests;
    }

    static TypedPreviewAdmissionQueue typedPreviewRequests() {
        return typedPreviewRequests;
    }

    static PlanCache planCache() {
        return PLAN_CACHE;
    }

    static void removePlayer(UUID playerId) {
        typedPreviewRequests.remove(playerId);
        planRequests.forget(playerId);
        PLAN_CACHE.removePlayer(playerId);
    }

    static synchronized void shutdown() {
        PlanRequestService stopped = planRequests;
        planRequests = newPlanRequestService(CraftingPlanningConfig.defaults());
        stopped.close();
        typedPreviewRequests.clear();
    }

    static synchronized void reload() {
        PLAN_CACHE.clear();
        PlanRequestService previous = planRequests;
        planRequests = newPlanRequestService(CraftingPlanningConfig.load());
        previous.close();
        TypedPreviewAdmissionQueue previousTyped = typedPreviewRequests;
        typedPreviewRequests = new TypedPreviewAdmissionQueue(
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY.get());
        previousTyped.clear();
    }

    private static PlanRequestService newPlanRequestService(CraftingPlanningConfig config) {
        return new PlanRequestService(
                config.workers(), config.queueCapacity(),
                config.maxSearchStates(), config.maxMemoizedFailures(),
                config.pureTimeoutMs());
    }
}
