package com.huanghuang.rsintegration.voidupgrade.network;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;

public final class VoidUpgradeNetworkHandler {
    private static boolean registered;

    private VoidUpgradeNetworkHandler() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.VOID_UPGRADE_CONFIG,
                SaveVoidUpgradeConfigPacket.class, SaveVoidUpgradeConfigPacket::encode,
                SaveVoidUpgradeConfigPacket::decode, SaveVoidUpgradeConfigPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }

    public static void clearServerState() {
        SaveVoidUpgradeConfigPacket.clearServerState();
    }
}
