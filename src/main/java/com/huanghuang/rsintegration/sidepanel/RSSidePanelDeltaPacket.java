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

import java.util.function.Supplier;

/**
 * Server→client incremental update: a single stack changed by {@code change} amount.
 * Positive = added to storage, negative = removed.  If the resulting count reaches
 * zero the client removes the item from its display.
 *
 * <p>Custom item serialisation — vanilla {@code writeItem} treats count=0 stacks
 * as EMPTY and drops the item reference, which prevents the client from removing
 * the entry on a full extraction.  We write the item identity even when count=0.
 */
public final class RSSidePanelDeltaPacket {

    final ItemStack stack;   // the item with its current total count (absolute)
    final long timestamp;
    final boolean craftable;

    RSSidePanelDeltaPacket(ItemStack stack, long timestamp, boolean craftable) {
        // stack.copy() returns EMPTY for count=0 stacks because
        // ItemStack.isEmpty() ⇔ count ≤ 0.  Preserve item identity manually.
        if (stack.getCount() <= 0 && stack.getItem() != null) {
            this.stack = new ItemStack(stack.getItem(), 0);
            if (stack.getTag() != null) this.stack.setTag(stack.getTag().copy());
        } else {
            this.stack = stack.copy();
        }
        this.timestamp = timestamp;
        this.craftable = craftable;
    }

    void encode(FriendlyByteBuf buf) {
        Item item = stack.getItem();
        if (item != null) {
            buf.writeBoolean(true);
            buf.writeId(BuiltInRegistries.ITEM, item);
            buf.writeVarInt(Math.max(0, stack.getCount()));
            buf.writeNbt(stack.getTag() != null ? stack.getTag() : null);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeVarLong(timestamp);
        buf.writeBoolean(craftable);
    }

    static RSSidePanelDeltaPacket decode(FriendlyByteBuf buf) {
        ItemStack stack;
        if (buf.readBoolean()) {
            Item item = buf.readById(BuiltInRegistries.ITEM);
            if (item != null) {
                int count = buf.readVarInt();
                CompoundTag tag = buf.readNbt();
                stack = new ItemStack(item, count);
                if (tag != null) stack.setTag(tag);
            } else {
                buf.readVarInt();
                buf.readNbt();
                stack = ItemStack.EMPTY;
            }
        } else {
            stack = ItemStack.EMPTY;
        }
        return new RSSidePanelDeltaPacket(stack, buf.readVarLong(), buf.readBoolean());
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
        RSSidePanelClient.onDeltaReceived(packet.stack, packet.timestamp, packet.craftable);
    }

    /** Convenience sender. */
    public static void send(ServerPlayer player, ItemStack stack, long timestamp, boolean craftable) {
        RSSidePanelNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelDeltaPacket(stack, timestamp, craftable));
    }
}
