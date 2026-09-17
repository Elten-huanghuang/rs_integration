package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.machine.MachineStatusReader;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.network.MachineCollectPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineInsertPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineFavoriteTogglePacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineFavoritesSyncPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineStatusDeltaPacket;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.sidepanel.network.PlaceboRemoteMenuSnapshotPacket;
import com.huanghuang.rsintegration.sidepanel.network.ReturnToRSPacket;
import com.huanghuang.rsintegration.sidepanel.network.RSBindingSyncPacket;
import com.huanghuang.rsintegration.sidepanel.network.RSBindingSyncRequestPacket;
import com.huanghuang.rsintegration.sidepanel.network.UnbindMachinePacket;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

public final class RSSidePanelNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static final Map<UUID, ListenerEntry> playerListeners = new ConcurrentHashMap<>();
    /** Last validated RS network retained after the side-panel/terminal UI closes. */
    private static final Map<UUID, com.refinedmods.refinedstorage.api.network.INetwork>
            lastKnownNetworks = new ConcurrentHashMap<>();
    /** One tick-deferred rebind per native cache, even when many players share it. */
    private static final IdentityInvalidationQueue<
            IStorageCache<ItemStack>, com.refinedmods.refinedstorage.api.network.INetwork>
            pendingInvalidatedCaches = new IdentityInvalidationQueue<>();

    // ── Pending deltas per player — collected during a tick, flushed at end ──
    private static final AtomicBatchQueue<UUID, RSSidePanelDeltaPacket.Entry> pendingDeltas = new AtomicBatchQueue<>();
    private static final Map<UUID, Set<UUID>> synchronizedStackIds = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Long>> pendingSnapshotPriorities = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> nextPriorityRefreshTick = new ConcurrentHashMap<>();
    private static final int PRIORITY_REFRESH_INTERVAL_TICKS = 20;
    private static final int MAX_SNAPSHOT_PRIORITIES = 256;
    // ── Machine status: last-pushed snapshot per (player, dim, pos) for diff ──
    private static final Map<UUID, Map<String, MachineStatus>> lastPushedStatuses = new ConcurrentHashMap<>();
    private static final Set<UUID> dirtyMachinePlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> syncGenerations = new ConcurrentHashMap<>();
    private static int machineScanCounter;
    private static long machineStatusSequence;
    private static boolean registered;
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_OPERATION_IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private RSSidePanelNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.SIDE_PANEL_REQUEST, RSSidePanelRequestPacket.class,
                RSSidePanelRequestPacket::encode, RSSidePanelRequestPacket::decode, RSSidePanelRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.SIDE_PANEL_SYNC, RSSidePanelSyncPacket.class,
                RSSidePanelSyncPacket::encode, RSSidePanelSyncPacket::decode, RSSidePanelSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.SIDE_PANEL_CLICK, RSSidePanelClickPacket.class,
                RSSidePanelClickPacket::encode, RSSidePanelClickPacket::decode, RSSidePanelClickPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.SIDE_PANEL_DELTA, RSSidePanelDeltaPacket.class,
                RSSidePanelDeltaPacket::encode, RSSidePanelDeltaPacket::decode, RSSidePanelDeltaPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.INVENTORY_TRANSFER, RSInventoryTransferPacket.class,
                RSInventoryTransferPacket::encode, RSInventoryTransferPacket::decode, RSInventoryTransferPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.OPEN_BOUND_MACHINE_GUI, OpenBoundMachineGuiPacket.class,
                OpenBoundMachineGuiPacket::encode, OpenBoundMachineGuiPacket::decode, OpenBoundMachineGuiPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.MACHINE_STATUS_DELTA, MachineStatusDeltaPacket.class,
                MachineStatusDeltaPacket::encode, MachineStatusDeltaPacket::decode, MachineStatusDeltaPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.MACHINE_COLLECT, MachineCollectPacket.class,
                MachineCollectPacket::encode, MachineCollectPacket::decode, MachineCollectPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.MACHINE_INSERT, MachineInsertPacket.class,
                MachineInsertPacket::encode, MachineInsertPacket::decode, MachineInsertPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.RS_BINDING_SYNC, RSBindingSyncPacket.class,
                RSBindingSyncPacket::encode, RSBindingSyncPacket::decode, RSBindingSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.RETURN_TO_RS, ReturnToRSPacket.class,
                ReturnToRSPacket::encode, ReturnToRSPacket::decode, ReturnToRSPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.SIDE_PANEL_OPERATION_RESULT, RSSidePanelOperationResultPacket.class,
                RSSidePanelOperationResultPacket::encode, RSSidePanelOperationResultPacket::decode, RSSidePanelOperationResultPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.UNBIND_MACHINE, UnbindMachinePacket.class,
                UnbindMachinePacket::encode, UnbindMachinePacket::decode, UnbindMachinePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.MACHINE_FAVORITE_TOGGLE, MachineFavoriteTogglePacket.class,
                MachineFavoriteTogglePacket::encode, MachineFavoriteTogglePacket::decode,
                MachineFavoriteTogglePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.MACHINE_FAVORITES_SYNC, MachineFavoritesSyncPacket.class,
                MachineFavoritesSyncPacket::encode, MachineFavoritesSyncPacket::decode,
                MachineFavoritesSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.PLACEBO_REMOTE_MENU_SNAPSHOT,
                PlaceboRemoteMenuSnapshotPacket.class,
                PlaceboRemoteMenuSnapshotPacket::encode, PlaceboRemoteMenuSnapshotPacket::decode,
                PlaceboRemoteMenuSnapshotPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.RS_BINDING_SYNC_REQUEST,
                RSBindingSyncRequestPacket.class,
                RSBindingSyncRequestPacket::encode, RSBindingSyncRequestPacket::decode,
                RSBindingSyncRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        registered = true;

        MinecraftForge.EVENT_BUS.register(RSSidePanelNetworkHandler.class);
        MinecraftForge.EVENT_BUS.addListener(RSSidePanelNetworkHandler::onServerTickEnd);
    }

    // ── Tick-end delta flush ──────────────────────────────────────

    private static volatile boolean tickFiringConfirmed;

    private static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Periodic cleanup of expired RemoteGuiAuth entries (every 30s)
        if (event.getServer().getTickCount() % 600 == 0) {
            RemoteGuiAuth.cleanExpired();
            com.huanghuang.rsintegration.mods.embers.EreAlchemyLock.cleanExpired();
        }

        if (!tickFiringConfirmed) {
            tickFiringConfirmed = true;
            RSIntegrationMod.LOGGER.debug("[RSI-Delta] onServerTickEnd is firing (listener registered OK)");
        }

        // Never detach an RS cache listener from onInvalidated(). RS 1.12.4
        // iterates a LinkedList directly, so listener removal in that callback
        // throws ConcurrentModificationException. This tick boundary is the
        // first point guaranteed to be outside the native callback stack.
        for (var invalidation : pendingInvalidatedCaches.drain()) {
            rebindInvalidatedCache(event.getServer(), invalidation.getValue(),
                    invalidation.getKey());
        }

        // ── Machine status push (every 40 ticks) ─────────────────
        RSSidePanelRequestPacket.advanceRefreshTasks(event.getServer());

        machineScanCounter++;
        if (machineScanCounter % 40 == 0) {
            for (UUID playerId : List.copyOf(dirtyMachinePlayers)) {
                ServerPlayer player = findPlayer(event.getServer(), playerId);
                if (player != null) pushMachineStatusDeltasFor(player);
                dirtyMachinePlayers.remove(playerId);
            }
        }

        flushPriorityRefreshes(event.getServer());

        if (pendingDeltas.keysSnapshot().isEmpty()) return;

        RSIntegrationMod.LOGGER.debug("[RSI-Delta] Tick-end flush: {} players with pending deltas",
                pendingDeltas.keysSnapshot().size());

        for (UUID playerId : pendingDeltas.keysSnapshot()) {
            List<RSSidePanelDeltaPacket.Entry> deltas = pendingDeltas.drain(playerId);
            if (deltas.isEmpty()) continue;

            ServerPlayer player = findPlayer(event.getServer(), playerId);
            if (player == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Delta] Tick-end flush: player {} not found, dropping {} deltas",
                        playerId, deltas.size());
                continue;
            }

            // Resolve each UUID to its final state before classifying it as
            // tracked or new. This prevents a zero-then-positive change in
            // one flush window from producing a stale removal packet.
            Map<UUID, RSSidePanelDeltaPacket.Entry> latestById = new LinkedHashMap<>();
            for (RSSidePanelDeltaPacket.Entry d : deltas) latestById.put(d.stackId, d);
            Map<UUID, RSSidePanelDeltaPacket.Entry> consolidated = new LinkedHashMap<>();
            Set<UUID> trackedIds = synchronizedStackIds.get(playerId);
            Set<UUID> removedTrackedIds = new HashSet<>();
            for (RSSidePanelDeltaPacket.Entry d : latestById.values()) {
                if (trackedIds != null && trackedIds.contains(d.stackId)) {
                    consolidated.put(d.stackId, d);
                    if (d.stack.getCount() <= 0) removedTrackedIds.add(d.stackId);
                } else if (d.stack.getCount() > 0) {
                    rememberSnapshotPriority(playerId, d.stackId, d.timestamp,
                            event.getServer().getTickCount());
                }
            }
            if (!removedTrackedIds.isEmpty()) {
                synchronizedStackIds.computeIfPresent(playerId, (ignored, ids) -> {
                    Set<UUID> remaining = new HashSet<>(ids);
                    remaining.removeAll(removedTrackedIds);
                    return Set.copyOf(remaining);
                });
            }
            RSSidePanelDeltaPacket.sendBatch(player, new ArrayList<>(consolidated.values()));
        }
    }

    private static ServerPlayer findPlayer(net.minecraft.server.MinecraftServer server, UUID playerId) {
        if (server == null || server.getPlayerList() == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }

    // ── Machine status push ───────────────────────────────────────

    public static void markMachineStatusDirty(UUID playerId) {
        if (playerId != null) dirtyMachinePlayers.add(playerId);
    }

    private static void pushMachineStatusDeltasFor(ServerPlayer player) {
        List<BindingInfo> bindings = new ArrayList<>();
        collectBindingsFromStacks(player.getInventory().items, bindings);
        collectBindingsFromStacks(player.getInventory().offhand, bindings);
        collectBindingsFromStacks(player.getInventory().armor, bindings);
        collectBindingsFromStacks(
                com.huanghuang.rsintegration.util.CuriosAccess.stacks(player), bindings);

        UUID pid = player.getUUID();
        Map<String, MachineStatus> playerLast = lastPushedStatuses.computeIfAbsent(pid,
                k -> new ConcurrentHashMap<>());

        List<MachineStatusDeltaPacket.Entry> changed = new ArrayList<>();
        Set<String> activeKeys = new HashSet<>();

        for (BindingInfo info : bindings) {
            // Defensive: skip entries with invalid dims before they reach network encoding
            if (info.dim() == null
                    || net.minecraft.resources.ResourceLocation.tryParse(info.dim().toString()) == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Delta] Skipping binding with invalid dim: dim={} blockKey={}",
                        info.dim(), info.blockKey());
                continue;
            }
            if (MachineInteractType.fromBlockKey(info.blockKey()) != MachineInteractType.QUICK)
                continue;

            var machineLevel = player.server.getLevel(info.dimensionKey());
            if (machineLevel == null) continue;

            String key = statusKey(info.dim(), info.pos());
            activeKeys.add(key);
            MachineStatus current = MachineStatusReader.read(machineLevel, info.pos());
            MachineStatus last = playerLast.get(key);

            if (last == null || !current.equals(last)) {
                changed.add(new MachineStatusDeltaPacket.Entry(info.dim(), info.pos(), current));
                playerLast.put(key, current);
            }
        }

        for (String staleKey : new ArrayList<>(playerLast.keySet())) {
            if (activeKeys.contains(staleKey)) continue;
            StatusAddress address = parseStatusKey(staleKey);
            if (address != null) changed.add(MachineStatusDeltaPacket.Entry.removed(address.dim, address.pos));
            playerLast.remove(staleKey);
        }

        if (!changed.isEmpty()) {
            CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new MachineStatusDeltaPacket(changed, ++machineStatusSequence));
        }
    }

    private static String statusKey(net.minecraft.resources.ResourceLocation dim, net.minecraft.core.BlockPos pos) {
        return dim.toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record StatusAddress(ResourceLocation dim, net.minecraft.core.BlockPos pos) {}

    private static StatusAddress parseStatusKey(String key) {
        try {
            int comma2 = key.lastIndexOf(',');
            int comma1 = key.lastIndexOf(',', comma2 - 1);
            int colon = key.lastIndexOf(':', comma1 - 1);
            if (colon < 0 || comma1 < 0 || comma2 < 0) return null;
            ResourceLocation dim = ResourceLocation.tryParse(key.substring(0, colon));
            if (dim == null) return null;
            int x = Integer.parseInt(key.substring(colon + 1, comma1));
            int y = Integer.parseInt(key.substring(comma1 + 1, comma2));
            int z = Integer.parseInt(key.substring(comma2 + 1));
            return new StatusAddress(dim, new net.minecraft.core.BlockPos(x, y, z));
        } catch (RuntimeException ignored) {
            return null;
        }
    }


    public static void sendRequestSync() {
        if (!RSSidePanelModule.isEnabled()) return;
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(true, false));
    }

    /** Refresh machine-tab bindings without requesting a side-panel snapshot. */
    public static void sendBindingSyncRequest() {
        CHANNEL.sendToServer(new RSBindingSyncRequestPacket());
    }

    public static void sendCloseRequest() {
        if (!RSSidePanelModule.isEnabled()) return;
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(false, true));
    }

    /** Starts a server-thread refresh. The task scheduler hook is kept here so
     * packet handling never performs an unbounded scan before enqueueing. */
    public static boolean startRefresh(ServerPlayer player, boolean forceFullSync) {
        if (!RSSidePanelModule.isEnabled()) return false;
        return RSSidePanelRequestPacket.refreshOnServerThread(player, forceFullSync);
    }

    public static void sendBindingSync(ServerPlayer player) {
        List<BindingInfo> bindings = collectPlayerBindings(player);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RSBindingSyncPacket(bindings));
        sendMachineFavoritesSync(player);
    }

    public static void sendSync(ServerPlayer player, List<UUID> ids, List<ItemStack> items,
                                List<Long> timestamps,
                                List<Boolean> craftableFlags,
                                int totalSlotCount, boolean networkAvailable,
                                String networkName) {
        if (!RSSidePanelModule.isEnabled()) return;
        synchronizedStackIds.put(player.getUUID(), Set.copyOf(ids));
        // Build binding info list from player's inventory bindings
        List<BindingInfo> bindings = collectPlayerBindings(player);

        int total = ids.size();
        if (total <= RSSidePanelSyncPacket.CHUNK_SIZE) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RSSidePanelSyncPacket(ids, items, timestamps, craftableFlags,
                            totalSlotCount, networkAvailable, networkName, bindings,
                            0, 1, nextSyncGeneration(player)));
        } else {
            int totalChunks = (int) Math.ceil((double) total / RSSidePanelSyncPacket.CHUNK_SIZE);
            RSIntegrationMod.LOGGER.debug("[RSI] Splitting sync into {} chunks ({} items > {})",
                    totalChunks, total, RSSidePanelSyncPacket.CHUNK_SIZE);
            long generation = nextSyncGeneration(player);
            for (int i = 0; i < totalChunks; i++) {
                int from = i * RSSidePanelSyncPacket.CHUNK_SIZE;
                int to = Math.min(from + RSSidePanelSyncPacket.CHUNK_SIZE, total);
                List<UUID> cIds = new ArrayList<>(ids.subList(from, to));
                List<ItemStack> cItems = new ArrayList<>(items.subList(from, to));
                List<Long> cTimestamps = new ArrayList<>(timestamps.subList(from, to));
                List<Boolean> cFlags = new ArrayList<>(craftableFlags.subList(from, to));
                List<BindingInfo> cBindings = (i == 0) ? bindings : Collections.emptyList();
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new RSSidePanelSyncPacket(cIds, cItems, cTimestamps, cFlags,
                                totalSlotCount, networkAvailable, networkName,
                                cBindings, i, totalChunks, generation));
            }
        }
        sendMachineFavoritesSync(player);
    }

    public static List<BindingInfo> collectPlayerBindings(ServerPlayer player) {
        List<BindingInfo> bindings = new ArrayList<>();
        try {
            collectBindingsFromStacks(player.getInventory().items, bindings);
            collectBindingsFromStacks(player.getInventory().offhand, bindings);
            collectBindingsFromStacks(player.getInventory().armor, bindings);
            collectBindingsFromStacks(
                    com.huanghuang.rsintegration.util.CuriosAccess.stacks(player), bindings);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to collect player bindings", e);
        }
        return List.copyOf(bindings);
    }

    public static void sendMachineFavoritesSync(ServerPlayer player) {
        MachineFavoritesSavedData data = MachineFavoritesSavedData.get(player.server);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MachineFavoritesSyncPacket(data.getFavorites(player.getUUID())));
    }

    private static void collectBindingsFromStacks(List<ItemStack> stacks, List<BindingInfo> out) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            var itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemKey == null) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (!BindingEventHandler.supportsGuiByBlockKey(entry.blockKey())) continue;
                String displayName = resolveDisplayName(entry.blockKey(), entry.blockRegKey(), entry.displayStack());
                out.add(new BindingInfo(itemKey.toString(), entry.dim(), entry.pos(),
                        entry.blockKey(), displayName, entry.blockRegKey(), entry.displayStack()));
            }
        }
    }

    private static String resolveDisplayName(String blockKey, String blockRegKey,
                                             ItemStack displayStack) {
        if (blockKey != null && blockKey.startsWith("pmmo_salvage||")) {
            return "rsi.batch.mod.pmmo_salvage";
        }
        // Gun-pack workbenches carry their real translation key via the item's
        // hover name (e.g. GunSmithTableItem.getName reads BlockId from NBT and
        // returns Component.translatable("emxarms.block.emx_workbench_table")).
        if (displayStack != null && !displayStack.isEmpty()) {
            var contents = displayStack.getHoverName().getContents();
            if (contents instanceof TranslatableContents translatable) {
                String key = translatable.getKey();
                // Only accept it if it differs from the generic fallback below,
                // otherwise let the normal MULTI_PART_ROOT_MAP path handle it.
                String mapped = blockRegKey != null
                        ? com.huanghuang.rsintegration.network.binding.BindingEventHandler.MULTI_PART_ROOT_MAP
                            .get(blockRegKey)
                        : null;
                if (!key.equals(mapped)) {
                    return key;
                }
            }
        }

        if (blockRegKey != null) {
            String effectiveKey = com.huanghuang.rsintegration.network.binding.BindingEventHandler.MULTI_PART_ROOT_MAP
                    .getOrDefault(blockRegKey, blockRegKey);
            var rl = net.minecraft.resources.ResourceLocation.tryParse(effectiveKey);
            if (rl != null) {
                var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null) return block.getDescriptionId();
            }
        }
        if (blockKey == null || blockKey.isEmpty()) return "?";
        int sep = blockKey.indexOf("||");
        return sep >= 0 ? blockKey.substring(sep + 2) : blockKey;
    }

    public static long sendClick(ItemStack targetItem, byte action, boolean isShift, UUID panelId) {
        long operationId = nextClientOperationId();
        RSSidePanelClickPacket packet = new RSSidePanelClickPacket(
                targetItem, action, isShift, panelId, operationId);
        CHANNEL.sendToServer(packet);
        return packet.operationId;
    }

    private static long nextClientOperationId() {
        return CLIENT_OPERATION_IDS.getAndUpdate(current ->
                current >= Long.MAX_VALUE ? 1L : current + 1L);
    }

    public static long sendDragDistribute(List<ItemStack> items) {
        RSSidePanelClickPacket packet = new RSSidePanelClickPacket(items);
        CHANNEL.sendToServer(packet);
        return packet.operationId;
    }

    public static long sendInsert(ItemStack carried, boolean isRightClick) {
        RSSidePanelClickPacket packet = new RSSidePanelClickPacket(carried, isRightClick);
        CHANNEL.sendToServer(packet);
        return packet.operationId;
    }

    // ── Operation result (server → client) ─────────────────────────

    /** Send the result of a side-panel operation back to the client. */
    public static void sendOperationResult(ServerPlayer player, long operationId,
                                           RSSidePanelClickPacket.OperationResult result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelOperationResultPacket(operationId, result.success(),
                        result.stackId(), result.actualCount(), result.errorCode()));
    }

    // ── Manual delta (for sendDeltaForItem safety net) ─────────────

    /** Send a single immediate delta — bypasses batching.
     *  Used as a safety net in {@code RSSidePanelClickPacket}. */
    public static void sendDeltaImmediate(ServerPlayer player, UUID stackId,
                                          ItemStack stack, long timestamp, boolean craftable) {
        if (!RSSidePanelModule.isEnabled()) return;
        RSSidePanelDeltaPacket.send(player, stackId, stack, timestamp, craftable);
    }

    // ── Queued delta (for storage-cache listener) ──────────────────

    /** Queue a delta to be sent at the end of the current tick.
     *  Multiple changes to the same stackId within a tick are consolidated. */
    public static void queueDelta(ServerPlayer player, UUID stackId,
                                  ItemStack stack, long timestamp, boolean craftable) {
        if (!RSSidePanelModule.isEnabled()) return;
        pendingDeltas.add(player.getUUID(), new RSSidePanelDeltaPacket.Entry(stackId, stack, timestamp, craftable));
    }

    // ── storage cache listener management ──────────────────────────

    @SuppressWarnings("unchecked")
    public static boolean registerListener(ServerPlayer player,
                                           com.refinedmods.refinedstorage.api.network.INetwork network) {
        if (!RSSidePanelModule.isEnabled()) return false;
        dirtyMachinePlayers.add(player.getUUID());
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return false;

        UUID pid = player.getUUID();
        ListenerEntry existing = playerListeners.get(pid);
        if (existing != null && existing.network == network && existing.cache == cache) {
            refreshCraftableKeys(network, existing.craftableKeys);
            return false;
        }
        boolean isNew = existing == null;
        unregisterListener(pid);

        // Snapshot craftable item keys
        var craftableKeys = new HashSet<ResourceLocation>();
        refreshCraftableKeys(network, craftableKeys);

        var tracker = network.getItemStorageTracker();
        final ListenerEntry[] entryHolder = new ListenerEntry[1];

        IStorageCacheListener<ItemStack> listener = new IStorageCacheListener<>() {
            @Override
            public void onAttached() {}

            @Override
            public void onInvalidated() {
                ListenerEntry entry = entryHolder[0];
                if (entry == null || playerListeners.get(pid) != entry) return;
                // RS invokes this callback once per attached listener. Only
                // enqueue here: MinecraftServer.execute() may run inline when
                // called from the server thread and is therefore not a safe
                // deferral boundary for listener removal.
                pendingInvalidatedCaches.offer(cache, network);
            }

            private void queue(ItemStack stack, int change, UUID entryId) {
                ListenerEntry entry = entryHolder[0];
                if (entry == null || playerListeners.get(pid) != entry) return;
                if (stack == null || stack.getItem() == null) return;

                // Never resolve a display name on the dedicated server. Some
                // item implementations (for example Bountiful's bounty item)
                // load client-only classes from getHoverName().
                var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                RSIntegrationMod.LOGGER.debug("[RSI-Delta] Cache onChanged: item={} change={} id={}",
                        itemId != null ? itemId : "unknown", change, entryId);

                // Query the real remaining count from the storage cache.
                // RS fires onChanged with the pre-extraction stack, so
                // stack.getCount() may be stale (e.g. 1 when the last item
                // was just extracted).  Always read the cache for ground truth.
                var list = cache.getList();
                int absoluteCount = 0;
                UUID stackId = entryId;

                if (list != null) {
                    ItemStack cached = stackId != null ? list.get(stackId) : null;
                    if (cached != null && !cached.isEmpty()) {
                        absoluteCount = cached.getCount();
                    } else {
                        var stackEntry = list.getEntry(stack, com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT);
                        if (stackEntry != null) {
                            var es = stackEntry.getStack();
                            if (es != null) absoluteCount = es.getCount();
                            if (stackId == null) stackId = stackEntry.getId();
                        }
                    }
                }
                if (stackId == null) {
                    stackId = UUID.randomUUID();
                }

                ItemStack toSend;
                if (absoluteCount <= 0) {
                    toSend = new ItemStack(stack.getItem(), 0);
                    if (stack.getTag() != null) toSend.setTag(stack.getTag().copy());
                } else {
                    toSend = stack.copy();
                    toSend.setCount(absoluteCount);
                }

                long ts = System.currentTimeMillis();
                if (tracker != null) {
                    var trackerEntry = tracker.get(stack);
                    if (trackerEntry != null && trackerEntry.getTime() > 0L) {
                        ts = trackerEntry.getTime();
                    }
                }
                var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                boolean craftable = k != null && entryHolder[0].craftableKeys.contains(k);

                RSIntegrationMod.LOGGER.debug("[RSI-Delta] Queueing delta: player={} id={} item={} count={} craftable={}",
                        player.getName().getString(), stackId,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(toSend.getItem()),
                        absoluteCount, craftable);
                queueDelta(player, stackId, toSend, ts, craftable);
            }

            @Override
            public void onChanged(StackListResult<ItemStack> delta) {
                queue(delta.getStack(), delta.getChange(), delta.getId());
            }

            @Override
            public void onChangedBulk(List<StackListResult<ItemStack>> deltas) {
                for (var d : deltas) queue(d.getStack(), d.getChange(), d.getId());
            }
        };
        cache.addListener(listener);
        ListenerEntry entry = new ListenerEntry(listener, cache, network);
        entry.craftableKeys.addAll(craftableKeys);
        entryHolder[0] = entry;
        playerListeners.put(pid, entry);
        lastKnownNetworks.put(pid, network);
        // Keep the common RS resolver in sync with the UI listener.  Recursive
        // crafting must work even after the panel is closed and must not depend
        // on the client-side panel state.
        com.huanghuang.rsintegration.network.RSIntegrationNetwork
                .rememberResolvedNetwork(player, network);
        return isNew;
    }

    private static void rebindInvalidatedCache(
            net.minecraft.server.MinecraftServer server,
            com.refinedmods.refinedstorage.api.network.INetwork network,
            IStorageCache<ItemStack> invalidatedCache) {
        List<UUID> affected = playerListeners.entrySet().stream()
                .filter(e -> e.getValue().cache == invalidatedCache
                        && e.getValue().network == network)
                .map(Map.Entry::getKey)
                .toList();
        if (!affected.isEmpty()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI] Storage cache invalidated; rebinding {} players as one batch",
                    affected.size());
        }
        IStorageCache<ItemStack> freshCache;
        try {
            freshCache = network.getItemStorageCache();
        } catch (RuntimeException | LinkageError failure) {
            freshCache = null;
        }
        for (UUID playerId : affected) {
            ListenerEntry current = playerListeners.get(playerId);
            if (current == null || current.cache != invalidatedCache
                    || current.network != network) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            unregisterListener(playerId);
            if (player == null) continue;
            if (freshCache != null && freshCache != invalidatedCache) {
                registerListener(player, network);
            } else {
                sendSync(player,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        0, false, "");
            }
        }
    }

    public static void unregisterListener(UUID playerId) {
        unregisterListener(playerId, true);
    }

    /**
     * Detach the live side-panel cache listener while optionally retaining the
     * last validated RS network for crafting requests. Closing the panel is a
     * UI lifecycle event, not a network invalidation; clearing the resolution
     * cache here made the presence of BD incorrectly hide an otherwise usable
     * RS network until the terminal was opened again.
     */
    public static void unregisterListener(UUID playerId, boolean invalidateResolution) {
        ListenerEntry old = playerListeners.remove(playerId);
        if (old != null) {
            try {
                old.cache.removeListener(old.listener);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Listener removal failed", e); }
        }
        RSSidePanelRequestPacket.cancelRefresh(playerId);
        pendingDeltas.clear(playerId);
        synchronizedStackIds.remove(playerId);
        pendingSnapshotPriorities.remove(playerId);
        nextPriorityRefreshTick.remove(playerId);
        if (invalidateResolution) {
            lastKnownNetworks.remove(playerId);
            com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(playerId);
        }
    }

    private static long nextSyncGeneration(ServerPlayer player) {
        return syncGenerations.merge(player.getUUID(), 1L, Long::sum);
    }

    public static void clearServerState() {
        for (ListenerEntry entry : List.copyOf(playerListeners.values())) {
            try {
                entry.cache.removeListener(entry.listener);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI] Listener removal failed during server cleanup", e);
            }
        }
        playerListeners.clear();
        pendingDeltas.clear();
        synchronizedStackIds.clear();
        pendingSnapshotPriorities.clear();
        nextPriorityRefreshTick.clear();
        dirtyMachinePlayers.clear();
        lastPushedStatuses.clear();
        syncGenerations.clear();
        lastKnownNetworks.clear();
        pendingInvalidatedCaches.clear();
        machineScanCounter = 0;
        machineStatusSequence = 0;
        tickFiringConfirmed = false;
        com.huanghuang.rsintegration.network.RSIntegrationNetwork.clearNetworkResolutionCache();
        com.huanghuang.rsintegration.crafting.batch.CraftCancelPacket.clearServerState();
        com.huanghuang.rsintegration.crafting.batch.CraftStatusRequestPacket.clearServerState();
    }

    /** @return true if the player has an active storage-cache listener. */
    public static boolean hasListener(UUID playerId) {
        return playerListeners.containsKey(playerId);
    }

    /** Detach panel-only storage listeners after a common-config reload. */
    public static void onSidePanelConfigReload() {
        if (RSSidePanelModule.isEnabled()) return;
        for (UUID playerId : List.copyOf(playerListeners.keySet())) {
            unregisterListener(playerId, false);
        }
        RSSidePanelRequestPacket.cancelAllRefreshTasks();
        for (UUID playerId : pendingDeltas.keysSnapshot()) {
            pendingDeltas.clear(playerId);
        }
    }

    private static void refreshCraftableKeys(
            com.refinedmods.refinedstorage.api.network.INetwork network,
            Set<ResourceLocation> target) {
        target.clear();
        try {
            var cm = network.getCraftingManager();
            if (cm != null) {
                for (var pattern : cm.getPatterns()) {
                    for (ItemStack out : pattern.getOutputs()) {
                        if (!out.isEmpty()) {
                            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(out.getItem());
                            if (key != null) target.add(key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Craftable probe failed", e);
        }
    }

    private static void rememberSnapshotPriority(UUID playerId, UUID stackId,
                                                 long timestamp, long currentTick) {
        Map<UUID, Long> priorities = pendingSnapshotPriorities.computeIfAbsent(
                playerId, ignored -> new ConcurrentHashMap<>());
        SidePanelSyncPolicy.rememberPriority(priorities, stackId,
                timestamp > 0L ? timestamp : System.currentTimeMillis(),
                MAX_SNAPSHOT_PRIORITIES);
        nextPriorityRefreshTick.putIfAbsent(
                playerId, currentTick + PRIORITY_REFRESH_INTERVAL_TICKS);
    }

    private static void flushPriorityRefreshes(net.minecraft.server.MinecraftServer server) {
        long currentTick = server.getTickCount();
        for (Map.Entry<UUID, Long> entry : List.copyOf(nextPriorityRefreshTick.entrySet())) {
            if (currentTick < entry.getValue()) continue;
            UUID playerId = entry.getKey();
            if (RSSidePanelRequestPacket.hasRefresh(playerId)) continue;
            if (!nextPriorityRefreshTick.remove(playerId, entry.getValue())) continue;
            ServerPlayer player = findPlayer(server, playerId);
            if (player == null) {
                pendingSnapshotPriorities.remove(playerId);
                continue;
            }
            if (pendingSnapshotPriorities.containsKey(playerId)
                    && !startRefresh(player, true)) {
                nextPriorityRefreshTick.putIfAbsent(
                        playerId, currentTick + PRIORITY_REFRESH_INTERVAL_TICKS);
            }
        }
    }

    /** Returns the validated network backing the player's active side-panel listener. */
    public static com.refinedmods.refinedstorage.api.network.INetwork getListenerNetwork(UUID playerId) {
        ListenerEntry entry = playerListeners.get(playerId);
        return entry != null ? entry.network : lastKnownNetworks.get(playerId);
    }

    /** Returns only the live listener network; retained last-known state is not an access credential. */
    public static com.refinedmods.refinedstorage.api.network.INetwork getActiveListenerNetwork(UUID playerId) {
        ListenerEntry entry = playerListeners.get(playerId);
        return entry == null ? null : entry.network;
    }

    static Set<UUID> trackedStackIds(UUID playerId) {
        Set<UUID> ids = synchronizedStackIds.get(playerId);
        return ids == null ? Set.of() : ids;
    }

    static Map<UUID, Long> snapshotPriorities(UUID playerId) {
        Map<UUID, Long> priorities = pendingSnapshotPriorities.get(playerId);
        if (priorities == null || priorities.isEmpty()) return Map.of();
        return SidePanelSyncPolicy.newestPrioritiesFirst(priorities);
    }

    static boolean acknowledgeSnapshotPriorities(UUID playerId, Map<UUID, Long> included) {
        Map<UUID, Long> priorities = pendingSnapshotPriorities.get(playerId);
        if (priorities == null) return false;
        included.forEach((stackId, timestamp) -> priorities.remove(stackId, timestamp));
        if (priorities.isEmpty()) {
            pendingSnapshotPriorities.remove(playerId, priorities);
            return false;
        }
        return true;
    }

    static void schedulePriorityRefresh(UUID playerId, long currentTick) {
        nextPriorityRefreshTick.putIfAbsent(
                playerId, currentTick + PRIORITY_REFRESH_INTERVAL_TICKS);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            UUID pid = sp.getUUID();
            unregisterListener(pid);
            lastPushedStatuses.remove(pid);
            dirtyMachinePlayers.remove(pid);
            syncGenerations.remove(pid);
            RemoteGuiAuth.onPlayerLogout(pid);
            try {
                GuiOpenRateLimiter.onPlayerLogout(pid);
            } catch (LinkageError ignored) {
                // Rate limiting is optional and must not break player logout.
            }
            com.huanghuang.rsintegration.crafting.PreviewRateLimiter.onPlayerLogout(pid);
            SidePanelRequestRateLimiter.onPlayerLogout(pid);
            com.huanghuang.rsintegration.crafting.batch.CraftCancelPacket.onPlayerLogout(pid);
            com.huanghuang.rsintegration.crafting.batch.CraftStatusRequestPacket.onPlayerLogout(pid);
            com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket.onPlayerLogout(pid);
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            RemoteGuiAuth.deauthorize(sp.getUUID(), event.getContainer());
            // Closing a container is only a UI lifecycle event. Keep the last
            // validated RS network available for a crafting request that is
            // submitted immediately after the terminal closes. Explicit cache
            // invalidation remains responsible for actual network changes.
        }
    }

    // ── inner types ────────────────────────────────────────────────

    private static class ListenerEntry {
        final IStorageCacheListener<ItemStack> listener;
        final IStorageCache<ItemStack> cache;
        final com.refinedmods.refinedstorage.api.network.INetwork network;
        final Set<ResourceLocation> craftableKeys;

        ListenerEntry(IStorageCacheListener<ItemStack> listener,
                      IStorageCache<ItemStack> cache,
                      com.refinedmods.refinedstorage.api.network.INetwork network) {
            this.listener = listener;
            this.cache = cache;
            this.network = network;
            this.craftableKeys = ConcurrentHashMap.newKeySet();
        }
    }
}
