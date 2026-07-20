package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.backpack.OpenResonanceBackpackPacket;
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
import com.huanghuang.rsintegration.sidepanel.network.MachineStatusDeltaPacket;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.sidepanel.network.ReturnToRSPacket;
import com.huanghuang.rsintegration.sidepanel.network.RSBindingSyncPacket;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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

    // ── Pending deltas per player — collected during a tick, flushed at end ──
    private static final AtomicBatchQueue<UUID, RSSidePanelDeltaPacket.Entry> pendingDeltas = new AtomicBatchQueue<>();
    // ── Machine status: last-pushed snapshot per (player, dim, pos) for diff ──
    private static final Map<UUID, Map<String, MachineStatus>> lastPushedStatuses = new ConcurrentHashMap<>();
    private static final Set<UUID> dirtyMachinePlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> syncGenerations = new ConcurrentHashMap<>();
    private static int machineScanCounter;
    private static long machineStatusSequence;
    private static boolean registered;

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
        ch.registerMessage(NetworkPacketIds.OPEN_RESONANCE_BACKPACK, OpenResonanceBackpackPacket.class,
                OpenResonanceBackpackPacket::encode, OpenResonanceBackpackPacket::decode, OpenResonanceBackpackPacket::handle,
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

            // Consolidate: if same UUID appears multiple times in this batch,
            // only keep the last entry (most recent count wins).
            Map<UUID, RSSidePanelDeltaPacket.Entry> consolidated = new LinkedHashMap<>();
            for (RSSidePanelDeltaPacket.Entry d : deltas) {
                consolidated.put(d.stackId, d);
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
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        collectBindingsFromStacks(List.of(stacks.getStackInSlot(s)), bindings);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] reflection probe failed", e);
        }

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


    @OnlyIn(Dist.CLIENT)
    public static void sendRequestSync() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(true, false));
    }

    public static void sendCloseRequest() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(false, true));
    }

    /** Starts a server-thread refresh. The task scheduler hook is kept here so
     * packet handling never performs an unbounded scan before enqueueing. */
    public static void startRefresh(ServerPlayer player, boolean forceFullSync) {
        RSSidePanelRequestPacket.refreshOnServerThread(player, forceFullSync);
    }

    public static void sendBindingSync(ServerPlayer player) {
        List<BindingInfo> bindings = new ArrayList<>();
        try {
            collectBindingsFromStacks(player.getInventory().items, bindings);
            collectBindingsFromStacks(player.getInventory().offhand, bindings);
            collectBindingsFromStacks(player.getInventory().armor, bindings);
            try {
                var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (opt.isPresent()) {
                    for (var handler : opt.get().getCurios().values()) {
                        var stacks = handler.getStacks();
                        for (int s = 0; s < stacks.getSlots(); s++) {
                            collectBindingsFromStacks(List.of(stacks.getStackInSlot(s)), bindings);
                        }
                    }
                }
            } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] reflection probe failed", e);
        }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to collect bindings for sync", e);
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RSBindingSyncPacket(bindings));
    }

    public static void sendSync(ServerPlayer player, List<UUID> ids, List<ItemStack> items,
                                List<Long> timestamps,
                                List<Boolean> craftableFlags,
                                int totalSlotCount, boolean networkAvailable,
                                String networkName) {
        // Build binding info list from player's inventory bindings
        List<BindingInfo> bindings = new ArrayList<>();
        try {
            collectBindingsFromStacks(player.getInventory().items, bindings);
            collectBindingsFromStacks(player.getInventory().offhand, bindings);
            collectBindingsFromStacks(player.getInventory().armor, bindings);
            try {
                var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (opt.isPresent()) {
                    for (var handler : opt.get().getCurios().values()) {
                        var stacks = handler.getStacks();
                        for (int s = 0; s < stacks.getSlots(); s++) {
                            collectBindingsFromStacks(List.of(stacks.getStackInSlot(s)), bindings);
                        }
                    }
                }
            } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] reflection probe failed", e);
        }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to build binding info", e);
        }

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
        RSSidePanelClickPacket packet = new RSSidePanelClickPacket(targetItem, action, isShift, panelId);
        CHANNEL.sendToServer(packet);
        return packet.operationId;
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
        RSSidePanelDeltaPacket.send(player, stackId, stack, timestamp, craftable);
    }

    // ── Queued delta (for storage-cache listener) ──────────────────

    /** Queue a delta to be sent at the end of the current tick.
     *  Multiple changes to the same stackId within a tick are consolidated. */
    public static void queueDelta(ServerPlayer player, UUID stackId,
                                  ItemStack stack, long timestamp, boolean craftable) {
        pendingDeltas.add(player.getUUID(), new RSSidePanelDeltaPacket.Entry(stackId, stack, timestamp, craftable));
    }

    // ── storage cache listener management ──────────────────────────

    @SuppressWarnings("unchecked")
    public static boolean registerListener(ServerPlayer player,
                                           com.refinedmods.refinedstorage.api.network.INetwork network) {
        dirtyMachinePlayers.add(player.getUUID());
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return false;

        UUID pid = player.getUUID();
        boolean isNew = !playerListeners.containsKey(pid);
        unregisterListener(pid);

        // Snapshot craftable item keys
        var craftableKeys = new HashSet<ResourceLocation>();
        try {
            var cm = network.getCraftingManager();
            if (cm != null) {
                for (var pattern : cm.getPatterns()) {
                    for (ItemStack out : pattern.getOutputs()) {
                        if (!out.isEmpty()) {
                            var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(out.getItem());
                            if (k != null) craftableKeys.add(k);
                        }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Craftable probe failed", e); }

        var tracker = network.getItemStorageTracker();
        final ListenerEntry[] entryHolder = new ListenerEntry[1];

        IStorageCacheListener<ItemStack> listener = new IStorageCacheListener<>() {
            @Override
            public void onAttached() {}

            @Override
            public void onInvalidated() {
                ListenerEntry entry = entryHolder[0];
                if (entry == null || playerListeners.get(pid) != entry) return;
                RSIntegrationMod.LOGGER.warn("[RSI] Storage cache invalidated for player {} — attempting re-registration", pid);
                if (!playerListeners.remove(pid, entry)) return;
                pendingDeltas.clear(pid);
                com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(pid);

                // Attempt immediate re-registration on the new cache.
                // Some RS storage implementations rebuild the cache on certain
                // operations and fire onInvalidated spuriously.  If the network
                // is still alive, grab the fresh cache and re-attach — otherwise
                // the panel would stay blank until the next periodic sync (15s).
                net.minecraft.server.MinecraftServer server = player.getServer();
                if (server != null) {
                    ServerPlayer sp = server.getPlayerList().getPlayer(pid);
                    if (sp != null) {
                        try {
                            IStorageCache<ItemStack> freshCache = network.getItemStorageCache();
                            if (freshCache != null && freshCache != cache) {
                                RSIntegrationMod.LOGGER.debug("[RSI] Cache rebuilt — re-registering listener for {}", pid);
                                // registerListener will add the listener to the freshCache
                                registerListener(sp, network);
                                return;
                            }
                        } catch (Exception ex) {
                            RSIntegrationMod.LOGGER.warn("[RSI] Re-registration attempt failed for {}", pid, ex);
                        }
                        // Network is truly gone — notify client
                        RSIntegrationMod.LOGGER.debug("[RSI] Network unavailable for {} — clearing panel", pid);
                        sendSync(sp,
                                Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                0, false, "");
                    }
                }
            }

            private void queue(ItemStack stack, int change, UUID entryId) {
                ListenerEntry entry = entryHolder[0];
                if (entry == null || playerListeners.get(pid) != entry) return;
                if (stack == null || stack.getItem() == null) return;

                RSIntegrationMod.LOGGER.debug("[RSI-Delta] Cache onChanged: item={} change={} id={}",
                        stack.getHoverName().getString(), change, entryId);

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
                    if (trackerEntry != null) ts = trackerEntry.getTime();
                }
                var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                boolean craftable = k != null && craftableKeys.contains(k);

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
        entryHolder[0] = entry;
        playerListeners.put(pid, entry);
        return isNew;
    }

    public static void unregisterListener(UUID playerId) {
        ListenerEntry old = playerListeners.remove(playerId);
        if (old != null) {
            try {
                old.cache.removeListener(old.listener);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Listener removal failed", e); }
        }
        RSSidePanelRequestPacket.cancelRefresh(playerId);
        pendingDeltas.clear(playerId);
        com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(playerId);
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
        dirtyMachinePlayers.clear();
        lastPushedStatuses.clear();
        syncGenerations.clear();
        machineScanCounter = 0;
        machineStatusSequence = 0;
        tickFiringConfirmed = false;
        com.huanghuang.rsintegration.network.RSIntegrationNetwork.clearNetworkResolutionCache();
    }

    /** @return true if the player has an active storage-cache listener. */
    public static boolean hasListener(UUID playerId) {
        return playerListeners.containsKey(playerId);
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
            GuiOpenRateLimiter.onPlayerLogout(pid);
            com.huanghuang.rsintegration.crafting.PreviewRateLimiter.onPlayerLogout(pid);
            SidePanelRequestRateLimiter.onPlayerLogout(pid);
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            RemoteGuiAuth.deauthorize(sp.getUUID(), event.getContainer());
            com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(sp.getUUID());
        }
    }

    // ── inner types ────────────────────────────────────────────────

    private static class ListenerEntry {
        final IStorageCacheListener<ItemStack> listener;
        final IStorageCache<ItemStack> cache;
        final com.refinedmods.refinedstorage.api.network.INetwork network;

        ListenerEntry(IStorageCacheListener<ItemStack> listener,
                      IStorageCache<ItemStack> cache,
                      com.refinedmods.refinedstorage.api.network.INetwork network) {
            this.listener = listener;
            this.cache = cache;
            this.network = network;
        }
    }
}
