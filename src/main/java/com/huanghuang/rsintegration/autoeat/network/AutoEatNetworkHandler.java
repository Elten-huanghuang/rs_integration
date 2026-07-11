package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;

public final class AutoEatNetworkHandler {

    private static boolean registered;

    private AutoEatNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_REQUEST, AutoEatPacket.class,
                AutoEatPacket::encode, AutoEatPacket::decode, AutoEatPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_STOP, AutoEatStopPacket.class,
                AutoEatStopPacket::encode, AutoEatStopPacket::decode, AutoEatStopPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_SYNC, AutoEatSyncPacket.class,
                AutoEatSyncPacket::encode, AutoEatSyncPacket::decode, AutoEatSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_BLACKLIST_UPDATE, UpdateBlacklistPacket.class,
                UpdateBlacklistPacket::encode, UpdateBlacklistPacket::decode, UpdateBlacklistPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_BLACKLIST_REQUEST, RequestBlacklistPacket.class,
                RequestBlacklistPacket::encode, RequestBlacklistPacket::decode, RequestBlacklistPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.AUTO_EAT_BLACKLIST_SYNC, BlacklistSyncPacket.class,
                BlacklistSyncPacket::encode, BlacklistSyncPacket::decode, BlacklistSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
