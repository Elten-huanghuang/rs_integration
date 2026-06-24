package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

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

    /** A single delta entry within a batch. */
    public static final class Entry {
        final UUID stackId;
        final ItemStack stack;
        final long timestamp;
        final boolean craftable;

        public Entry(UUID stackId, ItemStack stack, long timestamp, boolean craftable) {
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
        RSSidePanelNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelDeltaPacket(entries));
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.stackId);
            Item item = e.stack.getItem();
            if (item != null) {
                buf.writeBoolean(true);
                buf.writeId(BuiltInRegistries.ITEM, item);
                buf.writeVarInt(Math.max(0, e.stack.getCount()));
                buf.writeNbt(e.stack.getTag() != null ? e.stack.getTag() : null);
            } else {
                buf.writeBoolean(false);
            }
            buf.writeVarLong(e.timestamp);
            buf.writeBoolean(e.craftable);
        }
    }

    static RSSidePanelDeltaPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            ItemStack stack;
            if (buf.readBoolean()) {
                Item item = buf.readById(BuiltInRegistries.ITEM);
                if (item != null) {
                    int c = buf.readVarInt();
                    CompoundTag tag = buf.readNbt();
                    stack = new ItemStack(item, c);
                    if (tag != null) stack.setTag(tag);
                } else {
                    buf.readVarInt();
                    buf.readNbt();
                    stack = ItemStack.EMPTY;
                }
            } else {
                stack = ItemStack.EMPTY;
            }
            entries.add(new Entry(id, stack, buf.readVarLong(), buf.readBoolean()));
        }
        return new RSSidePanelDeltaPacket(entries);
    }

    @SuppressWarnings("resource")
    static void handle(RSSidePanelDeltaPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyOnClient(packet));
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyOnClient(RSSidePanelDeltaPacket packet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (Entry e : packet.entries) {
            RSSidePanelClient.onDeltaReceived(e.stackId, e.stack, e.timestamp, e.craftable);
        }
    }
}
