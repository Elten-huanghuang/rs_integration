package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
    final ItemStack targetItem;
    final List<ItemStack> dragItems;
    final ItemStack carriedItem;
    final UUID panelId; // matches RS onExtract(player, UUID id, ...)
    final long operationId;

    public RSSidePanelClickPacket(ItemStack targetItem, byte action, boolean isShift,
                                  UUID panelId, long operationId) {
        if (operationId < 0) throw new IllegalArgumentException("operationId must be non-negative");
        this.action = action;
        this.isShift = isShift;
        this.targetItem = targetItem.copy();
        this.dragItems = Collections.emptyList();
        this.carriedItem = ItemStack.EMPTY;
        this.panelId = panelId;
        this.operationId = operationId;
    }

    public RSSidePanelClickPacket(ItemStack targetItem, byte action, boolean isShift, UUID panelId) {
        this(targetItem, action, isShift, panelId, 0L);
    }

    public RSSidePanelClickPacket(List<ItemStack> dragItems, long operationId) {
        if (operationId < 0) throw new IllegalArgumentException("operationId must be non-negative");
        this.action = ACTION_DRAG_DISTRIBUTE;
        this.isShift = false;
        this.targetItem = ItemStack.EMPTY;
        this.dragItems = new ArrayList<>(dragItems);
        this.carriedItem = ItemStack.EMPTY;
        this.panelId = null;
        this.operationId = operationId;
    }

    public RSSidePanelClickPacket(List<ItemStack> dragItems) { this(dragItems, 0L); }

    public RSSidePanelClickPacket(ItemStack carriedItem, boolean isRightClick, long operationId) {
        if (operationId < 0) throw new IllegalArgumentException("operationId must be non-negative");
        this.action = ACTION_INSERT;
        this.isShift = isRightClick;
        this.targetItem = ItemStack.EMPTY;
        this.dragItems = Collections.emptyList();
        this.carriedItem = carriedItem.copy();
        this.panelId = null;
        this.operationId = operationId;
    }

    public RSSidePanelClickPacket(ItemStack carriedItem, boolean isRightClick) {
        this(carriedItem, isRightClick, 0L);
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeVarLong(operationId);
        if (action == ACTION_DRAG_DISTRIBUTE) {
            buf.writeVarInt(dragItems.size());
            for (ItemStack stack : dragItems) writeStack(buf, stack);
        } else if (action == ACTION_INSERT) {
            writeStack(buf, carriedItem);
            buf.writeBoolean(isShift);
        } else {
            writeStack(buf, targetItem);
            buf.writeBoolean(isShift);
            buf.writeBoolean(panelId != null);
            if (panelId != null) buf.writeUUID(panelId);
        }
    }

    static RSSidePanelClickPacket decode(FriendlyByteBuf buf) {
        byte action = buf.readByte();
        long operationId = buf.readVarLong();
        if (operationId < 0) throw new IllegalArgumentException("negative operationId");
        if (action == ACTION_DRAG_DISTRIBUTE) {
            int count = buf.readVarInt();
            if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid drag item count");
            List<ItemStack> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) items.add(readStack(buf));
            if (buf.readableBytes() != 0) throw new IllegalArgumentException("trailing side-panel drag bytes");
            return new RSSidePanelClickPacket(items, operationId);
        }
        if (action == ACTION_INSERT) {
            RSSidePanelClickPacket packet = new RSSidePanelClickPacket(readStack(buf), buf.readBoolean(), operationId);
            if (buf.readableBytes() != 0) throw new IllegalArgumentException("trailing side-panel insert bytes");
            return packet;
        }
        if (action < ACTION_EXTRACT_ONE || action > ACTION_EXTRACT_MAX)
            throw new IllegalArgumentException("invalid side-panel action: " + action);
        ItemStack item = readStack(buf);
        boolean shift = buf.readBoolean();
        UUID id = buf.readBoolean() ? buf.readUUID() : null;
        if (buf.readableBytes() != 0) throw new IllegalArgumentException("trailing side-panel click bytes");
        return new RSSidePanelClickPacket(item, action, shift, id, operationId);
    }

    private static void writeStack(FriendlyByteBuf buf, ItemStack stack) {
        buf.writeItem(stack);
    }

    private static ItemStack readStack(FriendlyByteBuf buf) {
        return buf.readItem();
    }

    static void handle(RSSidePanelClickPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof net.minecraftforge.common.util.FakePlayer) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            OperationResult result;
            try {
                if (packet.action == ACTION_DRAG_DISTRIBUTE) {
                    result = executeDragDistribute(player, packet.dragItems);
                } else if (packet.action == ACTION_INSERT) {
                    result = executeInsert(player, packet.isShift, packet.carriedItem);
                } else {
                    result = executeSingleClick(player, packet.targetItem, packet.action,
                            packet.isShift, packet.panelId);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] Side-panel operation failed for {}",
                        player.getGameProfile().getName(), e);
                result = OperationResult.failure(packet.panelId,
                        RSSidePanelOperationResultPacket.ErrorCode.INTERNAL_ERROR);
            }
            RSSidePanelNetworkHandler.sendOperationResult(player, packet.operationId, result);
        });
        context.setPacketHandled(true);
    }

    // ── Flag constants matching RS IItemGridHandler ──────────────
    private static final int EXTRACT_HALF  = 1;
    private static final int EXTRACT_SHIFT = 4;

    private static OperationResult executeSingleClick(ServerPlayer player, ItemStack targetItem,
                                                       byte action, boolean isShift, UUID panelId) {
        int before = storedCount(player, panelId, targetItem);
        handleSingleClick(player, targetItem, action, isShift, panelId);
        int after = storedCount(player, panelId, targetItem);
        int actual = Math.max(0, before - after);
        return actual > 0
                ? OperationResult.success(panelId, actual)
                : OperationResult.failure(panelId, RSSidePanelOperationResultPacket.ErrorCode.NOTHING_TRANSFERRED);
    }

    private static OperationResult executeInsert(ServerPlayer player, boolean rightClick, ItemStack carried) {
        int before = player.containerMenu.getCarried().getCount();
        handleInsert(player, rightClick, carried);
        int actual = Math.max(0, before - player.containerMenu.getCarried().getCount());
        return actual > 0
                ? OperationResult.success(null, actual)
                : OperationResult.failure(null, RSSidePanelOperationResultPacket.ErrorCode.NOTHING_TRANSFERRED);
    }

    private static OperationResult executeDragDistribute(ServerPlayer player, List<ItemStack> items) {
        int before = 0;
        for (ItemStack item : items) before += storedCount(player, null, item);
        handleDragDistribute(player, items);
        int after = 0;
        for (ItemStack item : items) after += storedCount(player, null, item);
        int actual = Math.max(0, before - after);
        if (actual == items.size()) return OperationResult.success(null, actual);
        if (actual > 0) return new OperationResult(false, null, actual,
                RSSidePanelOperationResultPacket.ErrorCode.PARTIAL_TRANSFER);
        return OperationResult.failure(null, RSSidePanelOperationResultPacket.ErrorCode.NOTHING_TRANSFERRED);
    }

    private static int storedCount(ServerPlayer player, UUID stackId, ItemStack template) {
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null || network.getItemStorageCache() == null) return 0;
        var list = network.getItemStorageCache().getList();
        if (list == null) return 0;
        ItemStack stored = stackId != null ? list.get(stackId) : null;
        if ((stored == null || stored.isEmpty()) && template != null && !template.isEmpty()) {
            var entry = list.getEntry(template, 1);
            stored = entry != null ? entry.getStack() : null;
        }
        return stored == null || stored.isEmpty() ? 0 : stored.getCount();
    }

    static record OperationResult(boolean success, UUID stackId, int actualCount,
                                   RSSidePanelOperationResultPacket.ErrorCode errorCode) {
        static OperationResult success(UUID stackId, int count) {
            return new OperationResult(true, stackId, count,
                    RSSidePanelOperationResultPacket.ErrorCode.NONE);
        }

        static OperationResult failure(UUID stackId,
                                       RSSidePanelOperationResultPacket.ErrorCode code) {
            return new OperationResult(false, stackId, 0, code);
        }
    }

    private static void handleSingleClick(ServerPlayer player, ItemStack targetItem,
                                           byte action, boolean isShift, UUID panelId) {
        try {
            handleSingleClickImpl(player, targetItem, action, isShift, panelId);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] handleSingleClick failed for {}", player.getGameProfile().getName(), e);
        } finally {
            syncCursorSlot(player);
        }
    }

    private static void handleSingleClickImpl(ServerPlayer player, ItemStack targetItem,
                                               byte action, boolean isShift, UUID panelId) {
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null || targetItem.isEmpty()) return;

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

        // Use the client-supplied UUID first (matches RS onExtract(player, UUID, ...))
        // falling back to ItemStack lookup for older/unknown clients.
        UUID stackId = panelId;
        ItemStack stored;
        if (stackId != null) {
            stored = list.get(stackId);
            if (stored == null || stored.isEmpty()) stackId = null;
        } else {
            stored = null;
        }
        if (stackId == null) {
            var entry = list.getEntry(targetItem, 1);
            if (entry == null) {
                forceSyncZero(player, targetItem, panelId);
                return;
            }
            stackId = entry.getId();
            stored = list.get(stackId);
        }
        if (stored == null || stored.isEmpty()) {
            forceSyncZero(player, targetItem, stackId);
            return;
        }

        int available = stored.getCount();
        int maxStack = stored.getMaxStackSize();

        int count;
        switch (action) {
            case ACTION_EXTRACT_ONE:
                count = 1;
                break;
            case ACTION_EXTRACT_STACK:
                count = available / 2;
                if (maxStack > 1 && count > maxStack / 2) count = maxStack / 2;
                if (count < 1) count = 1;
                break;
            case ACTION_EXTRACT_MAX:
                count = maxStack;
                break;
            default:
                return;
        }
        count = Math.min(count, available);
        if (count <= 0) return;

        // Cursor-merging check — matches RS ItemGridHandler.onExtract.
        // Creative mode: Mojang's ClientPacketListener.handleContainerSetSlot drops
        // ClientboundContainerSetSlotPacket (containerId=-1) when the screen is a
        // CreativeModeInventoryScreen, so the server cursor can never be synced to
        // the client.  Therefore creative-mode extractions always route items to the
        // player inventory directly (the isShift path).
        ItemStack cursor = player.containerMenu.getCarried();
        if (!isShift && !player.isCreative()) {
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

        // SIMULATE first — matches RS ItemGridHandler.onExtract
        ItemStack simulated = network.extractItem(extractTemplate, count, Action.SIMULATE);
        if (simulated.isEmpty()) {
            forceSyncZero(player, targetItem, stackId);
            return;
        }

        // Record modification time — matches RS tracker.changed() before extract
        var tracker = network.getItemStorageTracker();
        if (tracker != null) {
            tracker.changed(player, stored.copy());
        }

        // PERFORM extract
        ItemStack extracted = network.extractItem(extractTemplate, count, Action.PERFORM);
        if (extracted.isEmpty()) return;

        if (isShift || (player.isCreative() && action == ACTION_EXTRACT_MAX)) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    playerFullInv(player), extracted, false);
            if (!remainder.isEmpty()) player.drop(remainder, false);
            if (player.isCreative()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        } else {
            if (cursor.isEmpty()) {
                player.containerMenu.setCarried(extracted);
            } else {
                cursor.grow(extracted.getCount());
            }
        }
        syncCursorSlot(player);

        // Energy drain — matches RS onExtract for wireless grid
        try {
            var nim = network.getNetworkItemManager();
            if (nim != null) {
                int extractCost = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid().getExtractUsage();
                nim.drainEnergy(player, extractCost);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Energy drain failed after extraction", e);
        }

        // Delta is handled by the storage-cache listener registered in
        // RSSidePanelNetworkHandler — matches RS native pattern where
        // GridItemDeltaMessage is sent by the listener, not the handler.
    }

    private static void handleInsert(ServerPlayer player, boolean isRightClick, ItemStack clientCarried) {
        try {
            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
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
                // Cursor desync: server has nothing, client claims to carry something.
                // Do NOT trust the client — sync server state back and reject.
                syncCursorSlot(player, ItemStack.EMPTY);
                return;
            }

            ItemStack template = serverCarried.copy();

            // Record modification time — matches RS tracker.changed() before insert
            var tracker = network.getItemStorageTracker();
            if (tracker != null) {
                tracker.changed(player, template.copy());
            }

            if (isRightClick) {
                // Right-click: insert single item — matches RS onInsert single path
                template.setCount(1);
                ItemStack remainder = network.insertItem(template.copy(), 1, Action.PERFORM);
                if (remainder.isEmpty()) serverCarried.shrink(1);
            } else {
                // Left-click: insert entire stack — matches RS onInsert full-stack path
                int count = serverCarried.getCount();
                ItemStack remainder = network.insertItem(template.copy(), count, Action.PERFORM);
                int inserted = count - remainder.getCount();
                if (inserted > 0) serverCarried.shrink(inserted);
            }
            if (serverCarried.isEmpty()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }

            // Drain energy for wireless-grid users — matches RS ItemGridHandler.onInsert
            try {
                var nim = network.getNetworkItemManager();
                if (nim != null) {
                    int insertCost = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage();
                    nim.drainEnergy(player, insertCost);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] Energy drain failed after insertion", e);
            }

            // Delta is handled by the storage-cache listener registered in
            // RSSidePanelNetworkHandler — matches RS native pattern.
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] handleInsert failed for {}", player.getGameProfile().getName(), e);
        } finally {
            // Always sync cursor — the client clears it optimistically before
            // sending the packet. Every server path (success, early return,
            // or exception) must restore it or confirm empty.
            syncCursorSlot(player);
        }
    }

    private static void handleDragDistribute(ServerPlayer player, List<ItemStack> dragItems) {
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null || dragItems.isEmpty()) return;

        if (network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
            RSIntegrationMod.LOGGER.debug("[RSI] Drag-distribute blocked by security manager for {}", player.getGameProfile().getName());
            return;
        }
        if (!network.canRun()) return;

        var tracker = network.getItemStorageTracker();

        for (ItemStack template : dragItems) {
            ItemStack req = template.copy();
            req.setCount(1);

            // SIMULATE first — matches RS pattern
            ItemStack sim = network.extractItem(req, 1, Action.SIMULATE);
            if (sim.isEmpty()) continue;

            if (tracker != null) tracker.changed(player, req.copy());

            ItemStack extracted = network.extractItem(req, 1, Action.PERFORM);
            if (!extracted.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                        playerFullInv(player), extracted, false);
                if (!remainder.isEmpty()) player.drop(remainder, false);
            }
        }

        // Energy drain per extracted item — matches RS onExtract
        try {
            var nim = network.getNetworkItemManager();
            if (nim != null) {
                int extractCost = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid().getExtractUsage() * dragItems.size();
                nim.drainEnergy(player, extractCost);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Energy drain failed after drag-distribute", e);
        }

        // Delta is handled by the storage-cache listener.
    }

    /** Full player inventory including main (36), armor (4), and offhand (1) slots.
     *  Matches RS native {@code ItemGridHandler} behavior for shift-click extraction. */
    private static net.minecraftforge.items.IItemHandler playerFullInv(ServerPlayer player) {
        return new PlayerMainInvWrapper(player.getInventory());
    }

    private static void forceSyncZero(ServerPlayer player, ItemStack targetItem, UUID panelId) {
        ItemStack zeroStack = targetItem.copy();
        zeroStack.setCount(0);
        RSSidePanelNetworkHandler.sendDeltaImmediate(player,
                panelId != null ? panelId : UUID.randomUUID(),
                zeroStack, System.currentTimeMillis(), false);
    }

    /** Sync the cursor slot to the client.  The client optimistically clears
     *  the carried item on insert/extract, so every server-side path — including
     *  early returns — must send a cursor sync so the client doesn't lose items. */
    private static void syncCursorSlot(ServerPlayer player) {
        syncCursorSlot(player, player.containerMenu.getCarried());
    }

    private static void syncCursorSlot(ServerPlayer player, ItemStack stack) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, stack));
    }
}