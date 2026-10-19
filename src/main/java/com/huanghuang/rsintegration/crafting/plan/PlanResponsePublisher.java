package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import net.minecraft.network.chat.Component;
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
            sendError(player, Component.translatable("rsi.plan.failure.graph_too_large",
                    graphNodes, maxGraphNodes), requestId);
            return;
        }
        byte[] payload;
        try {
            payload = PlanResponsePacket.encodePayload(plan, requestId);
        } catch (RuntimeException failure) {
            RSIntegrationMod.LOGGER.error("[RSI-plan] failed to encode plan response request={}",
                    requestId, failure);
            sendError(player, Component.translatable("rsi.generic.error.craft_failed"), requestId);
            return;
        }
        if (payload.length > PlanResponsePagePacket.MAX_TOTAL_BYTES) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-plan] plan response exceeds paging limit request={} bytes={} limit={}",
                    requestId, payload.length, PlanResponsePagePacket.MAX_TOTAL_BYTES);
            sendError(player, Component.translatable("rsi.generic.error.craft_failed"), requestId);
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

    public static void sendError(ServerPlayer player, Component message, long requestId) {
        RSIntegrationMod.LOGGER.warn("[RSI-plan] plan error: msg={} player={}",
                message.getString(), player.getGameProfile().getName());
        send(player, new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "", null, null, 0, 0, 0,
                List.of(message), 1), requestId);
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
