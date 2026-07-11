package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BatchCraftNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private BatchCraftNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.GENERIC_CRAFT, GenericCraftPacket.class,
                GenericCraftPacket::encode, GenericCraftPacket::decode, GenericCraftPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.PLAN_RESPONSE, PlanResponsePacket.class,
                PlanResponsePacket::encode, PlanResponsePacket::decode, PlanResponsePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
