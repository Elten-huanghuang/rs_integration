package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class RSSidePanelClickPacket {

    static final byte ACTION_EXTRACT_ONE = 0;
    static final byte ACTION_EXTRACT_STACK = 1;
    static final byte ACTION_EXTRACT_MAX = 2;
    static final byte ACTION_DRAG_DISTRIBUTE = 3;
    static final byte ACTION_INSERT = 4;

    final int slotIndex;
    final byte action;
    final boolean isShift;
    final List<Integer> dragSlots;
    final ItemStack carriedItem;

    // Single-slot constructor (actions 0-2)
    RSSidePanelClickPacket(int slotIndex, byte action, boolean isShift) {
        this.slotIndex = slotIndex;
        this.action = action;
        this.isShift = isShift;
        this.dragSlots = Collections.emptyList();
        this.carriedItem = ItemStack.EMPTY;
    }

    // Drag distribute constructor
    RSSidePanelClickPacket(List<Integer> dragSlots) {
        this.slotIndex = -1;
        this.action = ACTION_DRAG_DISTRIBUTE;
        this.isShift = false;
        this.dragSlots = new ArrayList<>(dragSlots);
        this.carriedItem = ItemStack.EMPTY;
    }

    // Insert constructor
    RSSidePanelClickPacket(int slotIndex, ItemStack carriedItem) {
        this.slotIndex = slotIndex;
        this.action = ACTION_INSERT;
        this.isShift = false;
        this.dragSlots = Collections.emptyList();
        this.carriedItem = carriedItem.copy();
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        if (action == ACTION_DRAG_DISTRIBUTE) {
            buf.writeVarInt(dragSlots.size());
            for (int slot : dragSlots) buf.writeVarInt(slot);
        } else if (action == ACTION_INSERT) {
            buf.writeVarInt(slotIndex);
            buf.writeItem(carriedItem);
        } else {
            buf.writeVarInt(slotIndex);
            buf.writeBoolean(isShift);
        }
    }

    static RSSidePanelClickPacket decode(FriendlyByteBuf buf) {
        byte action = buf.readByte();
        if (action == ACTION_DRAG_DISTRIBUTE) {
            int count = buf.readVarInt();
            List<Integer> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) slots.add(buf.readVarInt());
            return new RSSidePanelClickPacket(slots);
        }
        if (action == ACTION_INSERT) {
            return new RSSidePanelClickPacket(buf.readVarInt(), buf.readItem());
        }
        return new RSSidePanelClickPacket(buf.readVarInt(), action, buf.readBoolean());
    }

    static void handle(RSSidePanelClickPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            if (packet.action == ACTION_DRAG_DISTRIBUTE) {
                handleDragDistribute(player, packet.dragSlots);
            } else if (packet.action == ACTION_INSERT) {
                handleInsert(player, packet.slotIndex, packet.carriedItem);
            } else {
                handleSingleClick(player, packet.slotIndex, packet.action, packet.isShift);
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleSingleClick(ServerPlayer player, int slotIndex,
                                          byte action, boolean isShift) {
        List<ItemStack> cached = RSSidePanelNetworkHandler.getCachedItems(player.getUUID());
        if (cached == null) return;
        if (slotIndex < 0 || slotIndex >= cached.size()) return;

        ItemStack template = cached.get(slotIndex).copy();
        if (template.isEmpty()) return;
        int storedCount = template.getCount();
        template.setCount(1);

        int count;
        switch (action) {
            case ACTION_EXTRACT_ONE:
                count = isShift ? template.getMaxStackSize() : 1;
                break;
            case ACTION_EXTRACT_STACK:
                count = Math.max(1, (storedCount + 1) / 2);
                break;
            case ACTION_EXTRACT_MAX:
                count = template.getMaxStackSize();
                break;
            default:
                return;
        }

        doExtract(player, template, count);
    }

    private static void handleDragDistribute(ServerPlayer player, List<Integer> slots) {
        List<ItemStack> cached = RSSidePanelNetworkHandler.getCachedItems(player.getUUID());
        if (cached == null || cached.isEmpty()) return;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return;

        // Count total stored quantity across all dragged slots for their respective items
        int totalStored = 0;
        List<ItemStack> templates = new ArrayList<>();
        for (int idx : slots) {
            if (idx < 0 || idx >= cached.size()) continue;
            ItemStack stack = cached.get(idx);
            if (stack.isEmpty()) continue;
            totalStored += stack.getCount();
            templates.add(stack);
        }
        if (templates.isEmpty() || totalStored <= 0) return;

        // Extract items slot by slot — distribute 1 item from each, round-robin
        for (ItemStack template : templates) {
            ItemStack req = template.copy();
            req.setCount(1);
            int allocated = totalStored / templates.size();
            if (allocated <= 0) allocated = 1;
            try {
                ItemStack extracted = network.extractItem(req,
                        Math.min(allocated, template.getCount()), Action.PERFORM);
                if (!extracted.isEmpty()) {
                    if (!player.getInventory().add(extracted)) {
                        player.drop(extracted, false);
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.info("[RSI] SidePanel dragDistribute error: {}", e.toString());
            }
        }
        player.containerMenu.broadcastChanges();
        RSSidePanelNetworkHandler.invalidateCache(player.getUUID());
    }

    private static void handleInsert(ServerPlayer player, int slotIndex, ItemStack carried) {
        if (carried.isEmpty()) return;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return;

        List<ItemStack> cached = RSSidePanelNetworkHandler.getCachedItems(player.getUUID());
        ItemStack target = (cached != null && slotIndex >= 0 && slotIndex < cached.size())
                ? cached.get(slotIndex) : ItemStack.EMPTY;

        try {
            // Insert carried item into network. If clicking a matching slot,
            // the RS network will stack it automatically.
            ItemStack remainder = network.insertItem(carried.copy(), carried.getCount(), Action.PERFORM);
            if (!remainder.isEmpty()) {
                if (!player.getInventory().add(remainder)) {
                    player.drop(remainder, false);
                }
            }
            player.containerMenu.broadcastChanges();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.info("[RSI] SidePanel insert error: {}", e.toString());
        }

        RSSidePanelNetworkHandler.invalidateCache(player.getUUID());
    }

    private static void doExtract(ServerPlayer player, ItemStack template, int count) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return;

        try {
            ItemStack extracted = network.extractItem(template, count, Action.PERFORM);
            if (!extracted.isEmpty()) {
                if (!player.getInventory().add(extracted)) {
                    player.drop(extracted, false);
                }
                player.containerMenu.broadcastChanges();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.info("[RSI] SidePanel click error: {}", e.toString());
        }

        RSSidePanelNetworkHandler.invalidateCache(player.getUUID());
    }
}
