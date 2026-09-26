package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.resonance.backpack.OpenResonanceBackpackPacket;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import net.minecraftforge.network.NetworkDirection;

public final class ResonanceNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private ResonanceNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.RESONANCE_SYNC, ResonanceSyncPacket.class,
                ResonanceSyncPacket::encode, ResonanceSyncPacket::decode, ResonanceSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.OPEN_RESONANCE_BACKPACK, OpenResonanceBackpackPacket.class,
                OpenResonanceBackpackPacket::encode, OpenResonanceBackpackPacket::decode,
                OpenResonanceBackpackPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
