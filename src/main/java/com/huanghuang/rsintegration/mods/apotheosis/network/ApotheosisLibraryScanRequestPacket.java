package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;

import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public record ApotheosisLibraryScanRequestPacket(ResourceLocation dimension, BlockPos pos) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
    }

    public static ApotheosisLibraryScanRequestPacket decode(FriendlyByteBuf buf) {
        return new ApotheosisLibraryScanRequestPacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(ApotheosisLibraryScanRequestPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var result = ApotheosisLibraryService.scan(player, packet.dimension, packet.pos);
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    ApotheosisLibraryScanResponsePacket.from(packet.dimension, packet.pos, result));
        });
        context.setPacketHandled(true);
    }
}
