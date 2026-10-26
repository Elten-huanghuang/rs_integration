package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.MaxCraftableSearch;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;

/** 最大可合成次数搜索及其快照/预算处理。 */
final class CraftMaxCraftableSearchService {
    private CraftMaxCraftableSearchService() {}

    record Request(
            ResourceLocation recipeId,
            Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput,
            long requestId,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks) {}

    static void start(ServerPlayer player, Request request, long previewGeneration) {
        GenericCraftPacket.tryBuildPlan(
                player, request.recipeId(), request.forcedRecipes(),
                request.dimension(), request.position(), 1,
                request.baseItem(), request.targetOutput(), request.requestId(),
                previewGeneration, null, null, false, false,
                new CraftPlanResultDispatcher.Sink() {
                    @Override
                    public void success(PlanResponse prepared, PlanningSnapshot snapshot) {
                        if (!prepared.success()) {
                            networkSink(player, request, previewGeneration)
                                    .success(prepared, snapshot);
                            return;
                        }
                        boolean fullyProjected = snapshot.recipeGraph().recipesById()
                                .containsKey(request.recipeId())
                                && prepared.steps().stream().allMatch(step ->
                                snapshot.recipeGraph().recipesById()
                                        .containsKey(step.recipeId()))
                                && (prepared.graph() == null || prepared.graph().nodes().stream()
                                .allMatch(node -> snapshot.recipeGraph().recipesById()
                                        .containsKey(node.recipeId())));
                        if (snapshot.mainThreadOnly() || !fullyProjected
                                || !request.forcedRecipes().isEmpty()
                                || !request.materialLocks().isEmpty()) {
                            MaxCraftableSearch search = new MaxCraftableSearch(
                                    RSIntegrationConfig.REPEAT_COUNT_MAX.get());
                            continueSearch(player, request, previewGeneration,
                                    search, snapshot);
                            return;
                        }
                        CraftPlanningRuntime.planRequests().submitMaxCraftable(
                                snapshot, RSIntegrationConfig.REPEAT_COUNT_MAX.get(),
                                player.getServer()::execute,
                                RSIntegrationConfig.CRAFTING_MAX_STEPS.get(),
                                result -> {
                                    if (!result.determined()) {
                                        networkSink(player, request, previewGeneration).error(
                                                PlanOutcome.BUDGET_EXHAUSTED,
                                                IntegrationDiagnostic.error(
                                                        IntegrationDiagnosticCode.MAX_CRAFTABLE_UNKNOWN));
                                    } else if (result.maximum() <= 0) {
                                        MaxCraftableSearch continuation =
                                                new MaxCraftableSearch(
                                                        RSIntegrationConfig
                                                                .REPEAT_COUNT_MAX.get());
                                        continueSearch(player, request, previewGeneration,
                                                continuation, result.snapshot());
                                    } else {
                                        finishSearch(player, request, previewGeneration,
                                                result.maximum(), result.plan(),
                                                result.snapshot());
                                    }
                                },
                                failure -> networkSink(player, request, previewGeneration).error(
                                        PlanOutcome.RUNTIME_UNAVAILABLE,
                                        IntegrationDiagnostic.error(
                                                IntegrationDiagnosticCode.INTERNAL_ERROR)));
                    }

                    @Override
                    public void error(PlanOutcome outcome, IntegrationDiagnostic diagnostic) {
                        networkSink(player, request, previewGeneration)
                                .error(outcome, diagnostic);
                    }
                },
                request.storageReference(), request.materialLocks());
    }

    private static void finishSearch(
            ServerPlayer player, Request request, long previewGeneration,
            int maximum, @Nullable PureRecipePlanner.Result plan,
            PlanningSnapshot snapshot) {
        int displayCount = Math.max(1, maximum);
        GenericCraftPacket.tryBuildPlan(
                player, request.recipeId(), request.forcedRecipes(),
                request.dimension(), request.position(), displayCount,
                request.baseItem(), request.targetOutput(), request.requestId(),
                previewGeneration, maximum > 0 ? plan : null, snapshot, true,
                true, networkSink(player, request, previewGeneration),
                request.storageReference(), request.materialLocks());
    }

    private static void continueSearch(
            ServerPlayer player, Request request, long previewGeneration,
            MaxCraftableSearch search, @Nullable PlanningSnapshot snapshot) {
        if (snapshot != null
                && !GenericCraftPacket.isSnapshotActive(player, snapshot, previewGeneration)) {
            return;
        }
        var probe = search.nextProbe();
        if (probe.isEmpty()) {
            int maximum = search.result();
            if (maximum <= 0) {
                GenericCraftPacket.tryBuildPlan(
                        player, request.recipeId(), request.forcedRecipes(),
                        request.dimension(), request.position(), 1,
                        request.baseItem(), request.targetOutput(), request.requestId(),
                        previewGeneration, null, snapshot, false,
                        true, networkSink(player, request, previewGeneration),
                        request.storageReference(), request.materialLocks());
                return;
            }
            GenericCraftPacket.tryBuildPlan(
                    player, request.recipeId(), request.forcedRecipes(),
                    request.dimension(), request.position(), maximum,
                    request.baseItem(), request.targetOutput(), request.requestId(),
                    previewGeneration, null, snapshot, false,
                    true, networkSink(player, request, previewGeneration),
                    request.storageReference(), request.materialLocks());
            return;
        }

        int candidate = probe.getAsInt();
        CraftPlanResultDispatcher.Sink probeSink = new CraftPlanResultDispatcher.Sink() {
            @Override
            public void success(PlanResponse plan, PlanningSnapshot usedSnapshot) {
                search.accept(candidate, plan.success());
                continueSearch(player, request, previewGeneration, search,
                        snapshot == null ? usedSnapshot : snapshot);
            }

            @Override
            public void error(PlanOutcome outcome, IntegrationDiagnostic diagnostic) {
                networkSink(player, request, previewGeneration)
                        .error(outcome, diagnostic);
            }
        };
        GenericCraftPacket.tryBuildPlan(
                player, request.recipeId(), request.forcedRecipes(),
                request.dimension(), request.position(), candidate,
                request.baseItem(), request.targetOutput(), request.requestId(),
                previewGeneration, null, snapshot, false,
                true, probeSink, request.storageReference(), request.materialLocks());
    }

    private static CraftPlanResultDispatcher.Sink networkSink(
            ServerPlayer player, Request request, long previewGeneration) {
        return CraftPlanResultDispatcher.networkSink(
                player, request.requestId(), previewGeneration);
    }
}
