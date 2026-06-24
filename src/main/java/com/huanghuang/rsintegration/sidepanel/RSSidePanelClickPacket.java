package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
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
import java.util.UUID;
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

    // ── Flag constants matching RS IItemGridHandler ──────────────
    private static final int EXTRACT_HALF  = 1;
    private static final int EXTRACT_SHIFT = 4;

    private static void handleSingleClick(ServerPlayer player, ItemStack targetItem, byte action, boolean isShift) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null || targetItem.isEmpty()) return;

        // Security check — matches RS ItemGridHandler.onExtract
        if (network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
            RSIntegrationMod.LOGGER.debug("[RSI] Extract blocked by security manager for {}", player.getGameProfile().getName());
            return;
        }
        if (!network.canRun()) return;

        var cache = network.getItemStorageCache();
        if (cache == null) return;
        var list = cache.getList();
        if (list == null) return;
        var entry = list.getEntry(targetItem, 1);
        if (entry == null) return;
        UUID stackId = entry.getId();
        ItemStack stored = list.get(stackId);
        if (stored == null || stored.isEmpty()) return;

        int available = stored.getCount();
        int maxStack = stored.getMaxStackSize();

        int count;
        switch (action) {
            case ACTION_EXTRACT_ONE:
                count = 1;
                break;
            case ACTION_EXTRACT_STACK:
                if (available <= 1) { count = 1; }
                else {
                    count = available / 2;
                    if (maxStack > 1 && count > maxStack / 2) count = maxStack / 2;
                }
                break;
            case ACTION_EXTRACT_MAX:
                count = maxStack;
                break;
            default:
                return;
        }
        count = Math.min(count, available);
        if (count <= 0) return;

        // Cursor-merging check — matches RS ItemGridHandler.onExtract
        ItemStack cursor = player.containerMenu.getCarried();
        if (!isShift) {
            if (!cursor.isEmpty()) {
                if (!ItemHandlerHelper.canItemStacksStack(cursor, stored)) {
                    return; // cursor holds a different item — deny extraction
                }
                int room = cursor.getMaxStackSize() - cursor.getCount();
                if (room <= 0) return; // cursor is full
                count = Math.min(count, room);
            }
        }

        ItemStack extractTemplate = stored.copy();
        extractTemplate.setCount(1);
        int totalBefore = available;

        ItemStack extracted = network.extractItem(extractTemplate, count, Action.PERFORM);
        if (extracted.isEmpty()) {
            sendDeltaForItem(player, network, extractTemplate, stackId);
            return;
        }

        if (isShift) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    new PlayerMainInvWrapper(player.getInventory()), extracted, false);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        } else {
            if (cursor.isEmpty()) {
                player.containerMenu.setCarried(extracted);
            } else {
                cursor.grow(extracted.getCount());
            }
        }
        player.containerMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, player.containerMenu.getCarried()));

        // Authoritative delta with UUID
        int remainingCount = Math.max(0, totalBefore - extracted.getCount());
        ItemStack deltaStack = extractTemplate.copy();
        deltaStack.setCount(remainingCount);
        sendCraftableDelta(player, network, deltaStack, extractTemplate, stackId);
    }

    private static void handleInsert(ServerPlayer player, boolean isRightClick) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return;

        // Security check — matches RS ItemGridHandler.onInsert
        if (network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.INSERT, player)) {
            RSIntegrationMod.LOGGER.debug("[RSI] Insert blocked by security manager for {}", player.getGameProfile().getName());
            return;
        }
        if (!network.canRun()) return;

        ItemStack serverCarried = player.containerMenu.getCarried();
        if (serverCarried.isEmpty()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    -1, player.containerMenu.getStateId(), -1, ItemStack.EMPTY));
            return;
        }

        // Matching RS native ItemGridHandler.onInsert patterns.
        ItemStack template = serverCarried.copy();
        if (isRightClick) {
            // Right-click: insert single item. RS does direct PERFORM.
            template.setCount(1);
            ItemStack remainder = network.insertItem(template.copy(), 1, Action.PERFORM);
            if (remainder.isEmpty()) serverCarried.shrink(1);
        } else {
            // Left-click: insert entire stack with crafting-tracker notification.
            int count = serverCarried.getCount();
            ItemStack remainder = network.insertItemTracked(template.copy(), count);
            int inserted = count - remainder.getCount();
            if (inserted > 0) serverCarried.shrink(inserted);
        }
        if (serverCarried.isEmpty()) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }

        player.containerMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, player.containerMenu.getCarried()));

        // Drain energy for wireless-grid users — matches RS ItemGridHandler.onInsert
        try {
            var nim = network.getNetworkItemManager();
            if (nim != null) {
                var cfg = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid();
                nim.drainEnergy(player, cfg.getInsertUsage());
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Energy drain skipped", e); }

        sendDeltaForItem(player, network, template, null);
    }

    private static void handleDragDistribute(ServerPlayer player, List<ItemStack> dragItems) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null || dragItems.isEmpty()) return;

        if (network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
            RSIntegrationMod.LOGGER.debug("[RSI] Drag-distribute blocked by security manager for {}", player.getGameProfile().getName());
            return;
        }
        if (!network.canRun()) return;

        var cache = network.getItemStorageCache();
        var list = cache != null ? cache.getList() : null;

        for (ItemStack template : dragItems) {
            ItemStack req = template.copy();
            req.setCount(1);
            ItemStack extracted = network.extractItem(req, 1, Action.PERFORM);
            if (!extracted.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), extracted, false);
                if (!remainder.isEmpty()) player.drop(remainder, false);
                // Try to get UUID from storage cache for accurate delta
                UUID sid = null;
                if (list != null) {
                    var e = list.getEntry(template, 1);
                    if (e != null) sid = e.getId();
                }
                sendDeltaForItem(player, network, template, sid);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    /** Query current count of {@code template} from the RS network and send an
     *  authoritative delta with UUID to the client. */
    private static void sendDeltaForItem(ServerPlayer player, INetwork network,
                                         ItemStack template, UUID stackId) {
        ItemStack probe = template.copy();
        probe.setCount(1);
        ItemStack current = network.extractItem(probe, Integer.MAX_VALUE, Action.SIMULATE);
        int count = current.getCount();

        ItemStack deltaStack = probe.copy();
        deltaStack.setCount(count);

        // If no UUID provided, try to find it from the storage cache
        UUID sid = stackId;
        if (sid == null) {
            var cache = network.getItemStorageCache();
            if (cache != null) {
                var list = cache.getList();
                if (list != null) {
                    var entry = list.getEntry(probe, 1);
                    if (entry != null) sid = entry.getId();
                }
            }
        }
        if (sid == null) sid = UUID.randomUUID(); // last resort

        sendCraftableDelta(player, network, deltaStack, probe, sid);
    }

    private static void sendCraftableDelta(ServerPlayer player, INetwork network,
                                           ItemStack deltaStack, ItemStack probe, UUID stackId) {
        long ts = System.currentTimeMillis();
        var tracker = network.getItemStorageTracker();
        if (tracker != null) {
            var te = tracker.get(probe);
            if (te != null) ts = te.getTime();
        }

        boolean craftable = false;
        try {
            var cm = network.getCraftingManager();
            if (cm != null) {
                for (var pattern : cm.getPatterns()) {
                    for (ItemStack out : pattern.getOutputs()) {
                        if (!out.isEmpty() && ItemStack.isSameItem(out, probe)) {
                            craftable = true;
                            break;
                        }
                    }
                    if (craftable) break;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Craftable probe failed", e); }

        RSSidePanelDeltaPacket.send(player, stackId, deltaStack, ts, craftable);
    }
}