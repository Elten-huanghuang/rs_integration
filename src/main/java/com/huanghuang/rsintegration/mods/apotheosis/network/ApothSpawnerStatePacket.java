package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerModels;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerUpgradeService;
import com.huanghuang.rsintegration.mods.apotheosis.client.ApothSpawnerUpgradeScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record ApothSpawnerStatePacket(ResourceLocation dimension, BlockPos pos,
                                      List<Entry> entries, String message) {
    public static ApothSpawnerStatePacket from(ApothSpawnerUpgradeService.Snapshot snapshot) {
        return new ApothSpawnerStatePacket(snapshot.dimension(), snapshot.pos(), snapshot.entries(), snapshot.message());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        ApothSpawnerModels.writeEntries(buf, entries);
        buf.writeUtf(message == null ? "" : message, ApothSpawnerModels.MAX_MESSAGE);
    }

    public static ApothSpawnerStatePacket decode(FriendlyByteBuf buf) {
        return new ApothSpawnerStatePacket(buf.readResourceLocation(), buf.readBlockPos(),
                ApothSpawnerModels.readEntries(buf), buf.readUtf(ApothSpawnerModels.MAX_MESSAGE));
    }

    public static void handle(ApothSpawnerStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ApothSpawnerUpgradeScreen.accept(packet));
        context.setPacketHandled(true);
    }
}
