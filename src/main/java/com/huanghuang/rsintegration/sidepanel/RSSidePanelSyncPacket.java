package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class RSSidePanelSyncPacket {

    // Chunking: for panels with >CHUNK_SIZE items, the server sends multiple
    // packets. The client accumulates chunks by checking totalChunks>1 and
    // only applies when all chunks are received. §13.5 N-1
    static final int CHUNK_SIZE = 120;

    final List<UUID> ids;
    final List<ItemStack> items;
    final List<Long> timestamps;
    final List<Boolean> craftableFlags;
    final int totalSlotCount;
    final boolean networkAvailable;
    final String networkName;
    final List<BindingInfo> bindings;
    final int chunkIndex;
    final int totalChunks;
    /** Monotonic server-side snapshot generation; prevents stale chunk sets overwriting newer state. */
    final long generation;

    RSSidePanelSyncPacket(List<UUID> ids, List<ItemStack> items, List<Long> timestamps,
                          List<Boolean> craftableFlags,
                          int totalSlotCount, boolean networkAvailable, String networkName,
                          List<BindingInfo> bindings) {
        this(ids, items, timestamps, craftableFlags, totalSlotCount, networkAvailable,
                networkName, bindings, 0, 1, 0L);
    }

    RSSidePanelSyncPacket(List<UUID> ids, List<ItemStack> items, List<Long> timestamps,
                          List<Boolean> craftableFlags,
                          int totalSlotCount, boolean networkAvailable, String networkName,
                          List<BindingInfo> bindings, int chunkIndex, int totalChunks) {
        this(ids, items, timestamps, craftableFlags, totalSlotCount, networkAvailable,
                networkName, bindings, chunkIndex, totalChunks, 0L);
    }

    RSSidePanelSyncPacket(List<UUID> ids, List<ItemStack> items, List<Long> timestamps,
                          List<Boolean> craftableFlags,
                          int totalSlotCount, boolean networkAvailable, String networkName,
                          List<BindingInfo> bindings, int chunkIndex, int totalChunks,
                          long generation) {
        this.ids = ids;
        this.items = items;
        this.timestamps = timestamps;
        this.craftableFlags = craftableFlags;
        this.totalSlotCount = totalSlotCount;
        this.networkAvailable = networkAvailable;
        this.networkName = networkName;
        this.bindings = bindings;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.generation = generation;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (int i = 0; i < items.size(); i++) {
            buf.writeUUID(ids != null && i < ids.size() ? ids.get(i) : UUID.randomUUID());
            ItemStack stack = items.get(i);
            int realCount = stack.getCount();
            ItemStack sent = stack.copy();
            sent.setCount(1);
            buf.writeItem(sent);
            buf.writeVarInt(realCount);
            buf.writeVarLong(timestamps != null && i < timestamps.size() ? timestamps.get(i) : 0L);
            buf.writeBoolean(craftableFlags != null && i < craftableFlags.size() && craftableFlags.get(i));
        }
        buf.writeVarInt(totalSlotCount);
        buf.writeBoolean(networkAvailable);
        buf.writeUtf(networkName, 256);
        buf.writeVarInt(bindings != null ? bindings.size() : 0);
        if (bindings != null) {
            for (var b : bindings) {
                BindingInfo.encode(buf, b);
            }
        }
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeVarLong(generation);
    }

    static RSSidePanelSyncPacket decode(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), 4096));
        List<UUID> ids = new ArrayList<>(count);
        List<ItemStack> items = new ArrayList<>(count);
        List<Long> timestamps = new ArrayList<>(count);
        List<Boolean> craftable = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readUUID());
            ItemStack stack = buf.readItem();
            int realCount = buf.readVarInt();
            stack.setCount(realCount);
            items.add(stack);
            timestamps.add(buf.readVarLong());
            craftable.add(buf.readBoolean());
        }
        int total = buf.readVarInt();
        boolean available = buf.readBoolean();
        String name = buf.readUtf(256);
        int bindingCount = Math.max(0, Math.min(buf.readVarInt(), 4096));
        List<BindingInfo> bindings = new ArrayList<>(bindingCount);
        for (int i = 0; i < bindingCount; i++) {
            bindings.add(BindingInfo.decode(buf));
        }
        int chunkIdx = 0;
        int totalChunks = 1;
        long generation = 0L;
        if (buf.readableBytes() >= 2) {
            chunkIdx = buf.readVarInt();
            totalChunks = buf.readVarInt();
            if (buf.readableBytes() > 0) generation = buf.readVarLong();
        }
        return new RSSidePanelSyncPacket(ids, items, timestamps, craftable, total, available,
                name, bindings, chunkIdx, totalChunks, generation);
    }

    boolean isChunked() { return totalChunks > 1; }

    @SuppressWarnings("resource")
    static void handle(RSSidePanelSyncPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyOnClient(packet));
        context.setPacketHandled(true);
    }

    public List<BindingInfo> getBindings() { return bindings; }

    @OnlyIn(Dist.CLIENT)
    private static void applyOnClient(RSSidePanelSyncPacket packet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        RSSidePanelClient.onSyncReceived(packet);
    }
}
