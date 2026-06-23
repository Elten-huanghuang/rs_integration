package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class RSSidePanelSyncPacket {

    final List<ItemStack> items;
    final List<Long> timestamps;
    final List<Boolean> craftableFlags;
    final int totalSlotCount;
    final boolean networkAvailable;
    final String networkName;

    RSSidePanelSyncPacket(List<ItemStack> items, List<Long> timestamps,
                          List<Boolean> craftableFlags,
                          int totalSlotCount, boolean networkAvailable, String networkName) {
        this.items = items;
        this.timestamps = timestamps;
        this.craftableFlags = craftableFlags;
        this.totalSlotCount = totalSlotCount;
        this.networkAvailable = networkAvailable;
        this.networkName = networkName;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (int i = 0; i < items.size(); i++) {
            buf.writeItem(items.get(i));
            buf.writeVarLong(timestamps != null && i < timestamps.size() ? timestamps.get(i) : 0L);
            buf.writeBoolean(craftableFlags != null && i < craftableFlags.size() && craftableFlags.get(i));
        }
        buf.writeVarInt(totalSlotCount);
        buf.writeBoolean(networkAvailable);
        buf.writeUtf(networkName, 256);
    }

    static RSSidePanelSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>(count);
        List<Long> timestamps = new ArrayList<>(count);
        List<Boolean> craftable = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(buf.readItem());
            timestamps.add(buf.readVarLong());
            craftable.add(buf.readBoolean());
        }
        int total = buf.readVarInt();
        boolean available = buf.readBoolean();
        String name = buf.readUtf();
        return new RSSidePanelSyncPacket(items, timestamps, craftable, total, available, name);
    }

    @SuppressWarnings("resource")
    static void handle(RSSidePanelSyncPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyOnClient(packet));
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyOnClient(RSSidePanelSyncPacket packet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        RSSidePanelClient.onSyncReceived(packet);
    }
}
