package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerModels;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerUpgradeService;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public record ApothSpawnerExecutePacket(ResourceLocation dimension, BlockPos pos,
                                        Map<ResourceLocation, Integer> selected, boolean preview) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension); buf.writeBlockPos(pos);
        buf.writeBoolean(preview);
        if (selected.size() > ApothSpawnerModels.MAX_ENTRIES) throw new IllegalArgumentException("Too many upgrades");
        buf.writeVarInt(selected.size());
        selected.forEach((id, count) -> { buf.writeResourceLocation(id); buf.writeVarInt(count); });
    }
    public static ApothSpawnerExecutePacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        boolean preview = buf.readBoolean();
        int size = buf.readVarInt();
        if (size < 0 || size > ApothSpawnerModels.MAX_ENTRIES) throw new io.netty.handler.codec.DecoderException("Invalid selection count");
        Map<ResourceLocation, Integer> selected = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ResourceLocation id = buf.readResourceLocation();
            int count = buf.readVarInt();
            if (count < 1 || count > 4096) throw new io.netty.handler.codec.DecoderException("Invalid upgrade amount");
            selected.put(id, count);
        }
        return new ApothSpawnerExecutePacket(dimension, pos, selected, preview);
    }
    public static void handle(ApothSpawnerExecutePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            if (packet.preview) {
                ApothSpawnerUpgradeService.preview(player, packet.dimension, packet.pos, packet.selected);
            } else {
                var snapshot = ApothSpawnerUpgradeService.executePreviewed(player, packet.dimension, packet.pos);
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ApothSpawnerStatePacket.from(snapshot));
            }
        });
        context.setPacketHandled(true);
    }
}
