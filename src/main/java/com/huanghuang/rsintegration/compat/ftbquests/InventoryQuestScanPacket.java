package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests one server-authoritative scan of the player's inventory and Curios slots. */
public final class InventoryQuestScanPacket {

    public static void encode(InventoryQuestScanPacket packet, FriendlyByteBuf buffer) {
    }

    public static InventoryQuestScanPacket decode(FriendlyByteBuf buffer) {
        return new InventoryQuestScanPacket();
    }

    public static void handle(InventoryQuestScanPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                StorageQuestScanService.requestInventoryScan(context.getSender());
            }
        });
        context.setPacketHandled(true);
    }
}
