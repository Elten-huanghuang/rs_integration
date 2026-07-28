package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;

/** Publishes correlated planning responses without exposing transport details to request handling. */
public final class PlanResponsePublisher {
    private PlanResponsePublisher() {}

    public static void send(ServerPlayer player, PlanResponse plan, long requestId) {
        if (player == null || player.hasDisconnected() || player.isRemoved()) return;
        BatchCraftNetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PlanResponsePacket(plan, requestId));
    }

    public static void sendError(ServerPlayer player, Component message, long requestId) {
        RSIntegrationMod.LOGGER.warn("[RSI-plan] plan error: msg={} player={}",
                message.getString(), player.getGameProfile().getName());
        send(player, new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "", null, null, 0, 0, 0,
                List.of(message), 1), requestId);
    }
}
