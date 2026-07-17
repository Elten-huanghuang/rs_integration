package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.ImportStats;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService;
import com.huanghuang.rsintegration.mods.apotheosis.client.ApotheosisLibraryClientEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ApotheosisLibraryImportResultPacket(ResourceLocation dimension, BlockPos pos,
                                                   ImportStats stats,
                                                   ApotheosisLibraryScanResponsePacket scan,
                                                   String errorKey) {
    public static ApotheosisLibraryImportResultPacket from(ResourceLocation dimension, BlockPos pos,
                                                            ApotheosisLibraryService.ImportResult result) {
        return new ApotheosisLibraryImportResultPacket(dimension, pos, result.stats(),
                ApotheosisLibraryScanResponsePacket.from(dimension, pos, result.scan()),
                result.errorKey() == null ? "" : result.errorKey());
    }

    public void encode(FriendlyByteBuf buf) {
        if (stats == null || stats.imported() < 0 || stats.skipped() < 0
                || stats.refunded() < 0 || stats.dropped() < 0) {
            throw new IllegalArgumentException("Invalid Apotheosis import statistics");
        }
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeVarInt(stats.imported());
        buf.writeVarInt(stats.skipped());
        buf.writeVarInt(stats.refunded());
        buf.writeVarInt(stats.dropped());
        scan.encode(buf);
        buf.writeUtf(errorKey == null ? "" : errorKey, com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.MAX_ERROR_LENGTH);
    }

    public static ApotheosisLibraryImportResultPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        ImportStats stats = new ImportStats(buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
        return new ApotheosisLibraryImportResultPacket(dimension, pos, stats,
                ApotheosisLibraryScanResponsePacket.decode(buf),
                buf.readUtf(com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.MAX_ERROR_LENGTH));
    }

    public static void handle(ApotheosisLibraryImportResultPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ApotheosisLibraryClientEvents.acceptImportResult(packet);
        });
        context.setPacketHandled(true);
    }
}
