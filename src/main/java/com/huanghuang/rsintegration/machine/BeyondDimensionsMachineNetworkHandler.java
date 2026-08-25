package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;

/** Registers the machine-center packet required by BD-only clients. */
public final class BeyondDimensionsMachineNetworkHandler {
    private static boolean registered;

    private BeyondDimensionsMachineNetworkHandler() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_OPEN_BOUND_MACHINE_GUI,
                BeyondDimensionsOpenBoundMachineGuiPacket.class,
                BeyondDimensionsOpenBoundMachineGuiPacket::encode,
                BeyondDimensionsOpenBoundMachineGuiPacket::decode,
                BeyondDimensionsOpenBoundMachineGuiPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_MACHINE_COLLECT,
                BeyondDimensionsMachineCollectPacket.class,
                BeyondDimensionsMachineCollectPacket::encode,
                BeyondDimensionsMachineCollectPacket::decode,
                BeyondDimensionsMachineCollectPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_MACHINE_INSERT,
                BeyondDimensionsMachineInsertPacket.class,
                BeyondDimensionsMachineInsertPacket::encode,
                BeyondDimensionsMachineInsertPacket::decode,
                BeyondDimensionsMachineInsertPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_UNBIND_MACHINE,
                BeyondDimensionsUnbindMachinePacket.class,
                BeyondDimensionsUnbindMachinePacket::encode,
                BeyondDimensionsUnbindMachinePacket::decode,
                BeyondDimensionsUnbindMachinePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_BINDING_SYNC,
                BeyondDimensionsBindingSyncPacket.class,
                BeyondDimensionsBindingSyncPacket::encode,
                BeyondDimensionsBindingSyncPacket::decode,
                BeyondDimensionsBindingSyncPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
