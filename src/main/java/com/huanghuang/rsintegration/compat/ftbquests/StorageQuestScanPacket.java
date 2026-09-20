package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests one server-authoritative scan of storage, inventory, equipment and Curios. */
public final class StorageQuestScanPacket {

    public static void encode(StorageQuestScanPacket packet, FriendlyByteBuf buffer) {
    }

    public static StorageQuestScanPacket decode(FriendlyByteBuf buffer) {
        return new StorageQuestScanPacket();
    }

    public static void handle(StorageQuestScanPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                StorageQuestScanService.requestScan(context.getSender());
            }
        });
        context.setPacketHandled(true);
    }
}
