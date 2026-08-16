package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/** Sends the target block entity needed by Placebo's client-side menu constructor. */
public record PlaceboRemoteMenuSnapshotPacket(BlockPos pos, int blockStateId,
                                               @Nullable CompoundTag blockEntityTag) {
    public static void encode(PlaceboRemoteMenuSnapshotPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeVarInt(packet.blockStateId);
        buf.writeNbt(packet.blockEntityTag);
    }

    public static PlaceboRemoteMenuSnapshotPacket decode(FriendlyByteBuf buf) {
        return new PlaceboRemoteMenuSnapshotPacket(buf.readBlockPos(), buf.readVarInt(), buf.readNbt());
    }

    public static void handle(PlaceboRemoteMenuSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> com.huanghuang.rsintegration.network.gui.RemotePlaceboMenuSnapshot
                .accept(packet.pos, packet.blockStateId, packet.blockEntityTag));
        context.setPacketHandled(true);
    }

    public static void send(ServerPlayer player, BlockPos pos, int blockStateId,
                            CompoundTag blockEntityTag) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PlaceboRemoteMenuSnapshotPacket(pos, blockStateId, blockEntityTag));
    }

    public static void clear(ServerPlayer player) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PlaceboRemoteMenuSnapshotPacket(BlockPos.ZERO, 0, null));
    }
}
