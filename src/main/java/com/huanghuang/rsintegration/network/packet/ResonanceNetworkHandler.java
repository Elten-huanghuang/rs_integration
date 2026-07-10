package com.huanghuang.rsintegration.network.packet;

import net.minecraftforge.network.simple.SimpleChannel;

public final class ResonanceNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private ResonanceNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.RESONANCE_SYNC, ResonanceSyncPacket.class,
                ResonanceSyncPacket::encode, ResonanceSyncPacket::decode, ResonanceSyncPacket::handle);
        registered = true;
    }
}
