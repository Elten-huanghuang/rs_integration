package com.huanghuang.rsintegration.reforging;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;

public final class ReforgingRestockNetworkHandler {
    private static boolean registered;

    private ReforgingRestockNetworkHandler() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.REFORGING_RESTOCK_REQUEST,
                ReforgingRestockRequestPacket.class, ReforgingRestockRequestPacket::encode,
                ReforgingRestockRequestPacket::decode, ReforgingRestockRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.REFORGING_RESTOCK_RESULT,
                ReforgingRestockResultPacket.class, ReforgingRestockResultPacket::encode,
                ReforgingRestockResultPacket::decode, ReforgingRestockResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
