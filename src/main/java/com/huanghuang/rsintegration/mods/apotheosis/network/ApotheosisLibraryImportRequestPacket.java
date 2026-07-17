package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public record ApotheosisLibraryImportRequestPacket(ResourceLocation dimension, BlockPos pos,
                                                    long snapshotId, Set<Integer> entryIds) {
    public void encode(FriendlyByteBuf buf) {
        if (entryIds == null || entryIds.isEmpty() || entryIds.size() > ApotheosisLibraryModels.MAX_IMPORT_IDS
                || entryIds.stream().anyMatch(id -> id == null || id < 0 || id >= ApotheosisLibraryModels.MAX_ENTRIES)) {
            throw new IllegalArgumentException("Invalid Apotheosis import ids");
        }
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeLong(snapshotId);
        buf.writeVarInt(entryIds.size());
        for (int id : entryIds) buf.writeVarInt(id);
    }

    public static ApotheosisLibraryImportRequestPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        long snapshotId = buf.readLong();
        int encodedSize = buf.readVarInt();
        if (encodedSize <= 0 || encodedSize > ApotheosisLibraryModels.MAX_IMPORT_IDS) {
            throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis import id count");
        }
        int size = encodedSize;
        Set<Integer> ids = new HashSet<>(size);
        for (int i = 0; i < encodedSize; i++) {
            int id = buf.readVarInt();
            if (id < 0 || id >= ApotheosisLibraryModels.MAX_ENTRIES) {
                throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis entry id");
            }
            ids.add(id);
        }
        if (ids.size() != encodedSize) {
            throw new io.netty.handler.codec.DecoderException("Duplicate Apotheosis entry id");
        }
        return new ApotheosisLibraryImportRequestPacket(dimension, pos, snapshotId, ids);
    }

    public static void handle(ApotheosisLibraryImportRequestPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var result = ApotheosisLibraryService.importEntries(player, packet.dimension,
                    packet.pos, packet.snapshotId, packet.entryIds);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    ApotheosisLibraryImportResultPacket.from(packet.dimension, packet.pos, result));
        });
        context.setPacketHandled(true);
    }
}
