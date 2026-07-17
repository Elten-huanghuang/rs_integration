package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService;
import com.huanghuang.rsintegration.mods.apotheosis.client.ApotheosisLibraryClientEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ApotheosisLibraryScanResponsePacket(ResourceLocation dimension, BlockPos pos,
                                                   long snapshotId, List<Entry> entries,
                                                   String errorKey) {
    public static ApotheosisLibraryScanResponsePacket from(ResourceLocation dimension, BlockPos pos,
                                                            ApotheosisLibraryService.ScanResult result) {
        return new ApotheosisLibraryScanResponsePacket(dimension, pos, result.snapshotId(),
                result.entries(), result.errorKey() == null ? "" : result.errorKey());
    }

    public void encode(FriendlyByteBuf buf) {
        if (entries == null || entries.size() > ApotheosisLibraryModels.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many Apotheosis library entries");
        }
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeLong(snapshotId);
        buf.writeVarInt(entries.size());
        entries.forEach(entry -> entry.encode(buf));
        buf.writeUtf(errorKey == null ? "" : errorKey, ApotheosisLibraryModels.MAX_ERROR_LENGTH);
    }

    public static ApotheosisLibraryScanResponsePacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        long snapshotId = buf.readLong();
        int size = buf.readVarInt();
        if (size < 0 || size > ApotheosisLibraryModels.MAX_ENTRIES) {
            throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis entry count");
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(Entry.decode(buf));
        return new ApotheosisLibraryScanResponsePacket(dimension, pos, snapshotId, entries,
                buf.readUtf(ApotheosisLibraryModels.MAX_ERROR_LENGTH));
    }

    public static void handle(ApotheosisLibraryScanResponsePacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ApotheosisLibraryClientEvents.acceptScan(packet);
        });
        context.setPacketHandled(true);
    }
}
