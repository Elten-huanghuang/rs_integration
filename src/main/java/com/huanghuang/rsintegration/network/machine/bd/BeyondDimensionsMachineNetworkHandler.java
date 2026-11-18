package com.huanghuang.rsintegration.network.machine.bd;

import com.huanghuang.rsintegration.network.binding.MachineBindingSnapshotService;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;

/** Registers the machine-center packet required by BD-only clients. */
public final class BeyondDimensionsMachineNetworkHandler {
    private static boolean registered;

    private BeyondDimensionsMachineNetworkHandler() {}

    public static void sendBindingSync(ServerPlayer player) {
        MachineBindingSnapshotService.INSTANCE.send(
                player, MachineBindingSnapshotService.Scope.BD_TERMINALS, true,
                chunk -> NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new BeyondDimensionsBindingSyncPacket(chunk.bindings(), chunk.generation(),
                                chunk.chunkIndex(), chunk.totalChunks())));
    }

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
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.BD_BINDING_SYNC_REQUEST,
                BeyondDimensionsBindingSyncRequestPacket.class,
                BeyondDimensionsBindingSyncRequestPacket::encode,
                BeyondDimensionsBindingSyncRequestPacket::decode,
                BeyondDimensionsBindingSyncRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
