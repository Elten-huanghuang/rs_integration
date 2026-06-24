package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class RSSidePanelClickPacket {

    public static final byte ACTION_EXTRACT_ONE = 0;
    public static final byte ACTION_EXTRACT_STACK = 1;
    public static final byte ACTION_EXTRACT_MAX = 2;
    public static final byte ACTION_DRAG_DISTRIBUTE = 3;
    public static final byte ACTION_INSERT = 4;

    final byte action;
    final boolean isShift;
    final ItemStack targetItem;    // 替代原来的 slotIndex
    final List<ItemStack> dragItems; // 替代原来的 List<Integer>
    final ItemStack carriedItem;

    // 单次点击提取
    public RSSidePanelClickPacket(ItemStack targetItem, byte action, boolean isShift) {
        this.action = action;
        this.isShift = isShift;
        this.targetItem = targetItem.copy();
        this.dragItems = Collections.emptyList();
        this.carriedItem = ItemStack.EMPTY;
    }

    // 拖拽分配
    public RSSidePanelClickPacket(List<ItemStack> dragItems) {
        this.action = ACTION_DRAG_DISTRIBUTE;
        this.isShift = false;
        this.targetItem = ItemStack.EMPTY;
        this.dragItems = new ArrayList<>(dragItems);
        this.carriedItem = ItemStack.EMPTY;
    }

    // Insert (no longer trusts client packet; server reads authoritative carried)
    public RSSidePanelClickPacket(ItemStack carriedItem, boolean isRightClick) {
        this.action = ACTION_INSERT;
        this.isShift = isRightClick; // repurposed: true = right-click insert-single
        this.targetItem = ItemStack.EMPTY;
        this.dragItems = Collections.emptyList();
        this.carriedItem = carriedItem.copy();
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        if (action == ACTION_DRAG_DISTRIBUTE) {
            buf.writeVarInt(dragItems.size());
            for (ItemStack stack : dragItems) writeStack(buf, stack);
        } else if (action == ACTION_INSERT) {
            writeStack(buf, carriedItem);
            buf.writeBoolean(isShift); // isRightClick
        } else {
            writeStack(buf, targetItem);
            buf.writeBoolean(isShift);
        }
    }

    static RSSidePanelClickPacket decode(FriendlyByteBuf buf) {
        byte action = buf.readByte();
        if (action == ACTION_DRAG_DISTRIBUTE) {
            int count = buf.readVarInt();
            List<ItemStack> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) items.add(readStack(buf));
            return new RSSidePanelClickPacket(items);
        }
        if (action == ACTION_INSERT) {
            return new RSSidePanelClickPacket(readStack(buf), buf.readBoolean());
        }
        return new RSSidePanelClickPacket(readStack(buf), action, buf.readBoolean());
    }

    private static void writeStack(FriendlyByteBuf buf, ItemStack stack) {
        if (stack.isEmpty()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeId(BuiltInRegistries.ITEM, stack.getItem());
        buf.writeVarInt(stack.getCount());
        buf.writeNbt(stack.hasTag() ? stack.getTag() : null);
    }

    private static ItemStack readStack(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return ItemStack.EMPTY;
        Item item = buf.readById(BuiltInRegistries.ITEM);
        if (item == null) return ItemStack.EMPTY;
        int count = buf.readVarInt();
        CompoundTag tag = buf.readNbt();
        ItemStack stack = new ItemStack(item, count);
        if (tag != null) stack.setTag(tag);
        return stack;
    }

    static void handle(RSSidePanelClickPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            if (packet.action == ACTION_DRAG_DISTRIBUTE) {
                handleDragDistribute(player, packet.dragItems);
            } else if (packet.action == ACTION_INSERT) {
                handleInsert(player, packet.isShift); // isShift repurposed as isRightClick
            } else {
                handleSingleClick(player, packet.targetItem, packet.action, packet.isShift);
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleSingleClick(ServerPlayer player, ItemStack targetItem, byte action, boolean isShift) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null || targetItem.isEmpty()) return;

        int count;
        switch (action) {
            case ACTION_EXTRACT_ONE:
                count = 1;
                break;
            case ACTION_EXTRACT_STACK:
                count = Math.min(targetItem.getMaxStackSize(), Math.max(1, targetItem.getCount() / 2));
                break;
            case ACTION_EXTRACT_MAX:
                count = targetItem.getMaxStackSize();
                break;
            default:
                return;
        }

        ItemStack extractTemplate = targetItem.copy();
        extractTemplate.setCount(1);

        // Sample total available before extraction so we can compute the
        // remaining count afterwards for the authoritative client delta.
        ItemStack before = network.extractItem(extractTemplate.copy(), Integer.MAX_VALUE,
                com.refinedmods.refinedstorage.api.util.Action.SIMULATE);
        int totalBefore = before.getCount();

        ItemStack extracted = network.extractItem(extractTemplate, count, Action.PERFORM);
        if (extracted.isEmpty()) {
            // Extraction failed — client may have predicted a change that didn't
            // happen.  Send a corrective delta with the real current count.
            ItemStack current = network.extractItem(extractTemplate.copy(), Integer.MAX_VALUE,
                    com.refinedmods.refinedstorage.api.util.Action.SIMULATE);
            ItemStack correctionStack = extractTemplate.copy();
            correctionStack.setCount(current.getCount());
            long ts = System.currentTimeMillis();
            var tracker = network.getItemStorageTracker();
            if (tracker != null) {
                var te = tracker.get(extractTemplate);
                if (te != null) ts = te.getTime();
            }
            RSSidePanelDeltaPacket.send(player, correctionStack, ts, false);
            return;
        }

        if (isShift) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    new PlayerMainInvWrapper(player.getInventory()), extracted, false);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        } else {
            ItemStack currentCarried = player.containerMenu.getCarried();
            if (currentCarried.isEmpty()) {
                player.containerMenu.setCarried(extracted);
            } else if (ItemHandlerHelper.canItemStacksStack(currentCarried, extracted)) {
                int total = currentCarried.getCount() + extracted.getCount();
                int max = currentCarried.getMaxStackSize();
                if (total <= max) {
                    currentCarried.setCount(total);
                } else {
                    currentCarried.setCount(max);
                    ItemStack returnToNet = extracted.copy();
                    returnToNet.setCount(total - max);
                    network.insertItem(returnToNet, returnToNet.getCount(), Action.PERFORM);
                }
            } else {
                network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            }
        }
        player.containerMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, player.containerMenu.getCarried()));

        // ── Send authoritative delta ───────────────────────────────
        // The storage-cache listener skips empty stacks (full extraction),
        // so we send a manual delta to keep the client display in sync.
        int remainingCount = Math.max(0, totalBefore - extracted.getCount());
        ItemStack deltaStack = extractTemplate.copy();
        deltaStack.setCount(remainingCount);

        long ts = System.currentTimeMillis();
        var tracker = network.getItemStorageTracker();
        if (tracker != null) {
            var te = tracker.get(extractTemplate);
            if (te != null) ts = te.getTime();
        }

        boolean craftable = false;
        try {
            var cm = network.getCraftingManager();
            if (cm != null) {
                for (var pattern : cm.getPatterns()) {
                    for (ItemStack out : pattern.getOutputs()) {
                        if (!out.isEmpty() && ItemStack.isSameItem(out, extractTemplate)) {
                            craftable = true;
                            break;
                        }
                    }
                    if (craftable) break;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        com.huanghuang.rsintegration.sidepanel.RSSidePanelDeltaPacket.send(
                player, deltaStack, ts, craftable);
    }

    private static void handleInsert(ServerPlayer player, boolean isRightClick) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return;

        ItemStack serverCarried = player.containerMenu.getCarried();
        if (serverCarried.isEmpty()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    -1, player.containerMenu.getStateId(), -1, ItemStack.EMPTY));
            return;
        }

        int count = isRightClick ? 1 : serverCarried.getCount();
        ItemStack toInsert = serverCarried.copy();
        toInsert.setCount(count);
        ItemStack remainder = network.insertItem(toInsert, count, Action.PERFORM);

        int inserted = count - remainder.getCount();
        serverCarried.shrink(inserted);
        if (serverCarried.isEmpty()) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }

        player.containerMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, player.containerMenu.getCarried()));
    }

    private static void handleDragDistribute(ServerPlayer player, List<ItemStack> dragItems) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null || dragItems.isEmpty()) return;

        for (ItemStack template : dragItems) {
            ItemStack req = template.copy();
            req.setCount(1);
            ItemStack extracted = network.extractItem(req, 1, Action.PERFORM);
            if (!extracted.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), extracted, false);
                if (!remainder.isEmpty()) player.drop(remainder, false);
            }
        }
        player.containerMenu.broadcastChanges();
    }
}