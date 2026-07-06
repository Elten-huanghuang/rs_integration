package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ContainerTransferNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private ContainerTransferNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), StoreAllPacket.class,
                StoreAllPacket::encode, StoreAllPacket::decode, StoreAllPacket::handle);
        registered = true;
    }
}
