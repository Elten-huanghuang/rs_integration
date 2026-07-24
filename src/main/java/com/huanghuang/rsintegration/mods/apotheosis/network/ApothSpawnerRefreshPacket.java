package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerUpgradeService;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public record ApothSpawnerRefreshPacket(ResourceLocation dimension, BlockPos pos) {
    public void encode(FriendlyByteBuf buf) { buf.writeResourceLocation(dimension); buf.writeBlockPos(pos); }
    public static ApothSpawnerRefreshPacket decode(FriendlyByteBuf buf) {
        return new ApothSpawnerRefreshPacket(buf.readResourceLocation(), buf.readBlockPos());
    }
    public static void handle(ApothSpawnerRefreshPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var snapshot = ApothSpawnerUpgradeService.scan(player, packet.dimension, packet.pos, "");
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ApothSpawnerStatePacket.from(snapshot));
        });
        context.setPacketHandled(true);
    }
}
