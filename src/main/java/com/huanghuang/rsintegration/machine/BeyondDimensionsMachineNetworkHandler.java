package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;
import java.util.Optional;

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
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_MACHINE_COLLECT,
                BeyondDimensionsMachineCollectPacket.class,
                BeyondDimensionsMachineCollectPacket::encode,
                BeyondDimensionsMachineCollectPacket::decode,
                BeyondDimensionsMachineCollectPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_MACHINE_INSERT,
                BeyondDimensionsMachineInsertPacket.class,
                BeyondDimensionsMachineInsertPacket::encode,
                BeyondDimensionsMachineInsertPacket::decode,
                BeyondDimensionsMachineInsertPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_UNBIND_MACHINE,
                BeyondDimensionsUnbindMachinePacket.class,
                BeyondDimensionsUnbindMachinePacket::encode,
                BeyondDimensionsUnbindMachinePacket::decode,
                BeyondDimensionsUnbindMachinePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_BINDING_SYNC,
                BeyondDimensionsBindingSyncPacket.class,
                BeyondDimensionsBindingSyncPacket::encode,
                BeyondDimensionsBindingSyncPacket::decode,
                BeyondDimensionsBindingSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
