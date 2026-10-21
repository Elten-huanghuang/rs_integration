package com.huanghuang.rsintegration.crafting.plan;



import com.huanghuang.rsintegration.network.packet.NetworkHandler;import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes correlated planning responses without exposing transport details to request handling. */
public final class PlanResponsePublisher {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

    private PlanResponsePublisher() {}

    public static void send(ServerPlayer player, PlanResponse plan, long requestId) {
        if (player == null || player.hasDisconnected() || player.isRemoved()) return;
        int graphNodes = plan.graph() == null ? 0 : plan.graph().nodes().size();
        int maxGraphNodes = configuredMaxGraphNodes();
        if (graphNodes > maxGraphNodes) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-plan] plan graph exceeds configured node limit request={} nodes={} limit={}",
                    requestId, graphNodes, maxGraphNodes);
            sendError(player, PlanOutcome.BUDGET_EXHAUSTED,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.GRAPH_TOO_LARGE,
                            String.valueOf(graphNodes), String.valueOf(maxGraphNodes)),
                    requestId);
            return;
        }
        byte[] payload;
        try {
            payload = PlanResponsePacket.encodePayload(plan, requestId);
        } catch (RuntimeException failure) {
            RSIntegrationMod.LOGGER.error("[RSI-plan] failed to encode plan response request={}",
                    requestId, failure);
            sendError(player, PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.INTERNAL_ERROR),
                    requestId);
            return;
        }
        if (payload.length > PlanResponsePagePacket.MAX_TOTAL_BYTES) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-plan] plan response exceeds paging limit request={} bytes={} limit={}",
                    requestId, payload.length, PlanResponsePagePacket.MAX_TOTAL_BYTES);
            sendError(player, PlanOutcome.BUDGET_EXHAUSTED,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.RESPONSE_TOO_LARGE,
                            String.valueOf(payload.length),
                            String.valueOf(PlanResponsePagePacket.MAX_TOTAL_BYTES)),
                    requestId);
            return;
        }
        long sessionId = nextSessionId();
        var pages = PlanResponsePagePacket.split(sessionId, requestId, payload);
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-plan] sending paged response session={} request={} bytes={} pages={}",
                    sessionId, requestId, payload.length, pages.size());
        }
        for (PlanResponsePagePacket page : pages) {
            BatchCraftNetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), page);
        }
    }

    public static void sendError(ServerPlayer player, PlanOutcome outcome,
                                 IntegrationDiagnostic diagnostic, long requestId) {
        RSIntegrationMod.LOGGER.warn("[RSI-plan] plan error: msg={} player={}",
                diagnostic.code(), player.getGameProfile().getName());
        send(player, new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "", List.of(), 1,
                outcome, List.of(diagnostic)), requestId);
    }

    private static long nextSessionId() {
        return NEXT_SESSION_ID.updateAndGet(current ->
                current <= 0L || current == Long.MAX_VALUE ? 1L : current + 1L);
    }

    static int configuredMaxGraphNodes() {
        try {
            return RSIntegrationConfig.CRAFTING_MAX_GRAPH_NODES.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_MAX_GRAPH_NODES;
        }
    }
}
