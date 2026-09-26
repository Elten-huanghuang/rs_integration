package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import net.minecraftforge.network.NetworkDirection;

public final class ContainerTransferNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private ContainerTransferNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.STORE_ALL, StoreAllPacket.class,
                StoreAllPacket::encode, StoreAllPacket::decode, StoreAllPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
