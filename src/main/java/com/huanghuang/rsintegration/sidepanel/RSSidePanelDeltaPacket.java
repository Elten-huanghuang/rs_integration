package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import io.netty.handler.codec.DecoderException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server→client incremental batch update.  Multiple delta entries are sent
 * in a single packet (matching RS native {@code GridItemDeltaMessage}).
 * Each entry carries the RS {@code StackListEntry} UUID for stable identity.
 */
public final class RSSidePanelDeltaPacket {

    /** Keep incremental updates bounded like full snapshots. */
    static final int MAX_ENTRIES = 120;

    /** A single delta entry within a batch. */
    public static final class Entry {
        final UUID stackId;
        final ItemStack stack;
        final long timestamp;
        final boolean craftable;
        final long operationId;

        public Entry(UUID stackId, ItemStack stack, long timestamp, boolean craftable) {
            this(stackId, stack, timestamp, craftable, 0L);
        }

        public Entry(UUID stackId, ItemStack stack, long timestamp, boolean craftable, long operationId) {
            this.stackId = stackId;
            // Preserve item identity for count=0 stacks (full extraction)
            if (stack.getCount() <= 0 && stack.getItem() != null) {
                this.stack = new ItemStack(stack.getItem(), 0);
                if (stack.getTag() != null) this.stack.setTag(stack.getTag().copy());
            } else {
                this.stack = stack.copy();
            }
            this.timestamp = timestamp;
            this.craftable = craftable;
            this.operationId = operationId;
        }
    }

    final List<Entry> entries;

    RSSidePanelDeltaPacket(List<Entry> entries) {
        this.entries = entries;
    }

    /** Convenience: single-entry packet for manual delta sends. */
    public static void send(ServerPlayer player, UUID stackId, ItemStack stack,
                            long timestamp, boolean craftable) {
        RSSidePanelNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelDeltaPacket(List.of(new Entry(stackId, stack, timestamp, craftable))));
    }

    /** Send a batch of deltas collected over a tick. */
    public static void sendBatch(ServerPlayer player, List<Entry> entries) {
        if (entries.isEmpty()) return;
        for (int from = 0; from < entries.size(); from += MAX_ENTRIES) {
            int to = Math.min(from + MAX_ENTRIES, entries.size());
            RSSidePanelNetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new RSSidePanelDeltaPacket(new ArrayList<>(entries.subList(from, to))));
        }
    }

    void encode(FriendlyByteBuf buf) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many side-panel delta entries: " + entries.size());
        }
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.stackId);
            int realCount = e.stack.getCount();
            ItemStack sent = e.stack.copy();
            sent.setCount(1);
            buf.writeItem(sent);
            buf.writeVarInt(realCount);
            buf.writeVarLong(e.timestamp);
            buf.writeBoolean(e.craftable);
            buf.writeVarLong(e.operationId);
        }
    }

    static RSSidePanelDeltaPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("side-panel delta entry count out of bounds: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            ItemStack stack = buf.readItem();
            int realCount = buf.readVarInt();
            stack.setCount(realCount);
            long timestamp = buf.readVarLong();
            boolean craftable = buf.readBoolean();
            long operationId = buf.isReadable() ? buf.readVarLong() : 0L;
            entries.add(new Entry(id, stack, timestamp, craftable, operationId));
        }
        return new RSSidePanelDeltaPacket(entries);
    }

    @SuppressWarnings("resource")
    static void handle(RSSidePanelDeltaPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> RSSidePanelClientPacketHandler.onDelta(packet)));
        context.setPacketHandled(true);
    }
}
