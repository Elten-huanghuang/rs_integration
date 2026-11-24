package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePublisher;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 将一次规划请求限制为唯一终态，并负责把结果发送给对应客户端。 */
final class CraftPlanResultDispatcher {
    private static final ConcurrentMap<ResultKey, PlanResultGate> RESULT_GATES =
            new ConcurrentHashMap<>();
    private static final long RESULT_GATE_TTL_NANOS = 60_000_000_000L;

    private CraftPlanResultDispatcher() {}

    interface Sink {
        void success(PlanResponse plan, PlanningSnapshot snapshot);
        void error(PlanOutcome outcome, IntegrationDiagnostic diagnostic);
        default void incomplete(PlanResponse plan) {
            error(plan.outcome(), plan.diagnostics().get(0));
        }
        default void cancelled(Component message) {}
        default void notice(Component message) {}
    }

    static Sink networkSink(ServerPlayer player, long requestId, long previewGeneration) {
        long now = System.nanoTime();
        RESULT_GATES.entrySet().removeIf(entry ->
                entry.getValue().expired(now, RESULT_GATE_TTL_NANOS));
        ResultKey key = new ResultKey(player.getUUID(), requestId, previewGeneration);
        PlanResultGate gate = RESULT_GATES.compute(key, (ignored, existing) ->
                existing == null || existing.expired(now, RESULT_GATE_TTL_NANOS)
                        ? new PlanResultGate(previewGeneration, now) : existing);
        return new Sink() {
            @Override
            public void success(PlanResponse plan, PlanningSnapshot snapshot) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                RESULT_GATES.remove(key, gate);
                logIncomplete(plan, requestId, previewGeneration);
                PlanningProgressServer.succeed(player, previewGeneration);
                CraftPlanningRuntime.planCache().retainForRequest(player.getUUID(), requestId, snapshot, previewGeneration);
                PlanResponsePublisher.send(player, plan, requestId, previewGeneration, snapshot.preferences().materialSources());
            }

            @Override
            public void error(PlanOutcome outcome, IntegrationDiagnostic diagnostic) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                RESULT_GATES.remove(key, gate);
                Component message = diagnostic.clientMessage();
                PlanningProgressServer.fail(player, previewGeneration, message);
                PlanResponsePublisher.sendError(player, outcome, diagnostic, requestId);
            }

            @Override
            public void incomplete(PlanResponse plan) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                RESULT_GATES.remove(key, gate);
                logIncomplete(plan, requestId, previewGeneration);
                // 进度表示将交付预览。超时原因由草稿携带，避免客户端先收到失败进度而关闭等待。
                PlanningProgressServer.succeed(player, previewGeneration);
                PlanResponsePublisher.send(player, plan, requestId);
            }

            @Override
            public void cancelled(Component message) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                RESULT_GATES.remove(key, gate);
                PlanningProgressServer.fail(player, previewGeneration, message);
                PlanResponsePublisher.sendError(player, PlanOutcome.CANCELLED,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLAN_CANCELLED),
                        requestId);
            }

            @Override
            public void notice(Component message) {
                if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
                    player.sendSystemMessage(message);
                }
            }
        };
    }

    private static void logIncomplete(PlanResponse plan, long requestId, long previewGeneration) {
        if (plan.outcome() != PlanOutcome.MISSING_INPUTS
                && !plan.outcome().incomplete()) return;
        var graph = plan.graph();
        long missingRoots = graph == null ? 0 : graph.roots().stream()
                .filter(root -> root.unresolvedQuantity() > 0).count();
        var diagnostic = plan.diagnostics().isEmpty() ? null : plan.diagnostics().get(0).code();
        RSIntegrationMod.LOGGER.info(
                "[RSI-plan] recipe={} request={} generation={} outcome={} diagnostic={} nodes={} allocatedRoots={} missingRoots={} missingInputs={}",
                plan.recipeId(), requestId, previewGeneration, plan.outcome(), diagnostic,
                graph == null ? 0 : graph.nodes().size(),
                graph == null ? 0 : graph.roots().size() - missingRoots,
                missingRoots, graph == null ? 0 : graph.unresolved().size());
    }

    static void clear(@Nullable UUID playerId) {
        if (playerId == null) {
            RESULT_GATES.forEach((key, gate) -> gate.cancel());
            RESULT_GATES.clear();
            return;
        }
        RESULT_GATES.entrySet().removeIf(entry -> {
            if (!playerId.equals(entry.getKey().playerId())) return false;
            entry.getValue().cancel();
            return true;
        });
    }

    private record ResultKey(UUID playerId, long requestId, long previewGeneration) {}
}
