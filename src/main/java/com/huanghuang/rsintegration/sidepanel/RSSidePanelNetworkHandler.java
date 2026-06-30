package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.machine.MachineStatusReader;
import com.huanghuang.rsintegration.network.BindingStorage;
import com.huanghuang.rsintegration.network.ConfigSyncPacket;
import com.huanghuang.rsintegration.network.NetworkHandler;
import com.huanghuang.rsintegration.network.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.network.MachineCollectPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineInsertPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineStatusDeltaPacket;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.sidepanel.network.RSBindingSyncPacket;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
    private static net.minecraft.server.MinecraftServer cachedServer;

    // ── Pending deltas per player — collected during a tick, flushed at end ──
    private static final Map<UUID, List<RSSidePanelDeltaPacket.Entry>> pendingDeltas = new ConcurrentHashMap<>();
    // ── Machine status: last-pushed snapshot per (player, dim, pos) for diff ──
    private static final Map<UUID, Map<String, MachineStatus>> lastPushedStatuses = new ConcurrentHashMap<>();
    private static int machineScanCounter;
    private static boolean registered;

    private RSSidePanelNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), RSSidePanelRequestPacket.class,
                RSSidePanelRequestPacket::encode, RSSidePanelRequestPacket::decode, RSSidePanelRequestPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSSidePanelSyncPacket.class,
                RSSidePanelSyncPacket::encode, RSSidePanelSyncPacket::decode, RSSidePanelSyncPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSSidePanelClickPacket.class,
                RSSidePanelClickPacket::encode, RSSidePanelClickPacket::decode, RSSidePanelClickPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSSidePanelDeltaPacket.class,
                RSSidePanelDeltaPacket::encode, RSSidePanelDeltaPacket::decode, RSSidePanelDeltaPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSInventoryTransferPacket.class,
                RSInventoryTransferPacket::encode, RSInventoryTransferPacket::decode, RSInventoryTransferPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), OpenBoundMachineGuiPacket.class,
                OpenBoundMachineGuiPacket::encode, OpenBoundMachineGuiPacket::decode, OpenBoundMachineGuiPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), MachineStatusDeltaPacket.class,
                MachineStatusDeltaPacket::encode, MachineStatusDeltaPacket::decode, MachineStatusDeltaPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), MachineCollectPacket.class,
                MachineCollectPacket::encode, MachineCollectPacket::decode, MachineCollectPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), MachineInsertPacket.class,
                MachineInsertPacket::encode, MachineInsertPacket::decode, MachineInsertPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSBindingSyncPacket.class,
                RSBindingSyncPacket::encode, RSBindingSyncPacket::decode, RSBindingSyncPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), ConfigSyncPacket.class,
                ConfigSyncPacket::encode, ConfigSyncPacket::decode, ConfigSyncPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSItemLockPacket.class,
                RSItemLockPacket::encode, RSItemLockPacket::decode, RSItemLockPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), RSItemLockSyncPacket.class,
                RSItemLockSyncPacket::encode, RSItemLockSyncPacket::decode, RSItemLockSyncPacket::handle);
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
        machineScanCounter++;
        if (machineScanCounter % 40 == 0) {
            pushMachineStatusDeltas(event.getServer());
        }

        if (pendingDeltas.isEmpty()) return;

        RSIntegrationMod.LOGGER.debug("[RSI-Delta] Tick-end flush: {} players with pending deltas",
                pendingDeltas.size());

        // Snapshot and clear — matching RS native GridItemDeltaMessage batching
        Map<UUID, List<RSSidePanelDeltaPacket.Entry>> snapshot = new HashMap<>(pendingDeltas);
        pendingDeltas.clear();

        for (var entry : snapshot.entrySet()) {
            UUID playerId = entry.getKey();
            List<RSSidePanelDeltaPacket.Entry> deltas = entry.getValue();
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

    private static void pushMachineStatusDeltas(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pushMachineStatusDeltasFor(player);
        }
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
        } catch (Exception ignored) {}

        UUID pid = player.getUUID();
        Map<String, MachineStatus> playerLast = lastPushedStatuses.computeIfAbsent(pid,
                k -> new ConcurrentHashMap<>());

        List<MachineStatusDeltaPacket.Entry> changed = new ArrayList<>();
        var level = player.level();

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

            String key = statusKey(info.dim(), info.pos());
            MachineStatus current = MachineStatusReader.read(level, info.pos());
            MachineStatus last = playerLast.get(key);

            if (last == null || !current.equals(last)) {
                changed.add(new MachineStatusDeltaPacket.Entry(info.dim(), info.pos(), current));
                playerLast.put(key, current);
            }
        }

        if (!changed.isEmpty()) {
            CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new MachineStatusDeltaPacket(changed));
        }
    }

    private static String statusKey(net.minecraft.resources.ResourceLocation dim, net.minecraft.core.BlockPos pos) {
        return dim.toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ── convenience senders ────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static void sendRequestSync() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(true, false,
                (byte) RSSidePanelClient.sortMode, RSSidePanelClient.sortAsc));
    }

    public static void sendCloseRequest() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket(false, true));
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
            } catch (Exception ignored) {}
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to collect bindings for sync: {}", e.toString());
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
            } catch (Exception ignored) {}
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to build binding info: {}", e.toString());
        }

        // Piggyback lock sync
        Set<ResourceLocation> locked = PlayerLockManager.getLockedItems(player);
        if (!locked.isEmpty()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RSItemLockSyncPacket(new ArrayList<>(locked)));
        }

        int total = ids.size();
        if (total <= RSSidePanelSyncPacket.CHUNK_SIZE) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RSSidePanelSyncPacket(ids, items, timestamps, craftableFlags,
                            totalSlotCount, networkAvailable, networkName, bindings));
        } else {
            int totalChunks = (int) Math.ceil((double) total / RSSidePanelSyncPacket.CHUNK_SIZE);
            RSIntegrationMod.LOGGER.debug("[RSI] Splitting sync into {} chunks ({} items > {})",
                    totalChunks, total, RSSidePanelSyncPacket.CHUNK_SIZE);
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
                                cBindings, i, totalChunks));
            }
        }
    }

    private static void collectBindingsFromStacks(List<ItemStack> stacks, List<BindingInfo> out) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            var itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemKey == null) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                String displayName = resolveDisplayName(entry.blockKey());
                out.add(new BindingInfo(itemKey.toString(), entry.dim(), entry.pos(),
                        entry.blockKey(), displayName));
            }
        }
    }

    private static String resolveDisplayName(String blockKey) {
        if (blockKey == null || blockKey.isEmpty()) return "?";
        // Strip optional prefix: "{prefix}||block.modid.name"
        // The remainder is already a block description ID, usable directly by I18n.
        int sep = blockKey.indexOf("||");
        return sep >= 0 ? blockKey.substring(sep + 2) : blockKey;
    }

    public static void sendClick(ItemStack targetItem, byte action, boolean isShift, UUID panelId) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(targetItem, action, isShift, panelId));
    }

    public static void sendDragDistribute(List<ItemStack> items) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(items));
    }

    public static void sendInsert(ItemStack carried, boolean isRightClick) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(carried, isRightClick));
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
        pendingDeltas.computeIfAbsent(player.getUUID(), k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new RSSidePanelDeltaPacket.Entry(stackId, stack, timestamp, craftable));
    }

    // ── storage cache listener management ──────────────────────────

    @SuppressWarnings("unchecked")
    public static boolean registerListener(ServerPlayer player,
                                           com.refinedmods.refinedstorage.api.network.INetwork network) {
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return false;

        UUID pid = player.getUUID();
        if (player.getServer() != null) cachedServer = player.getServer();
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

        IStorageCacheListener<ItemStack> listener = new IStorageCacheListener<>() {
            @Override
            public void onAttached() {}

            @Override
            public void onInvalidated() {
                RSIntegrationMod.LOGGER.warn("[RSI] Storage cache invalidated for player {} — attempting re-registration", pid);
                playerListeners.remove(pid);
                pendingDeltas.remove(pid);

                // Attempt immediate re-registration on the new cache.
                // Some RS storage implementations rebuild the cache on certain
                // operations and fire onInvalidated spuriously.  If the network
                // is still alive, grab the fresh cache and re-attach — otherwise
                // the panel would stay blank until the next periodic sync (15s).
                net.minecraft.server.MinecraftServer server = cachedServer;
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
                            RSIntegrationMod.LOGGER.warn("[RSI] Re-registration attempt failed for {}: {}", pid, ex.toString());
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
                        var entry = list.getEntry(stack, com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT);
                        if (entry != null) {
                            var es = entry.getStack();
                            if (es != null) absoluteCount = es.getCount();
                            if (stackId == null) stackId = entry.getId();
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
        playerListeners.put(pid, new ListenerEntry(listener, cache, network));
        return isNew;
    }

    public static void unregisterListener(UUID playerId) {
        ListenerEntry old = playerListeners.remove(playerId);
        if (old != null) {
            try {
                old.cache.removeListener(old.listener);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Listener removal failed", e); }
        }
        pendingDeltas.remove(playerId);
    }

    /** @return true if the player has an active storage-cache listener. */
    public static boolean hasListener(UUID playerId) {
        return playerListeners.containsKey(playerId);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Set<ResourceLocation> locked = PlayerLockManager.getLockedItems(sp);
            if (!locked.isEmpty()) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new RSItemLockSyncPacket(new ArrayList<>(locked)));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            UUID pid = sp.getUUID();
            unregisterListener(pid);
            lastPushedStatuses.remove(pid);
            RemoteGuiAuth.onPlayerLogout(pid);
            GuiOpenRateLimiter.onPlayerLogout(pid);
            com.huanghuang.rsintegration.crafting.PreviewRateLimiter.onPlayerLogout(pid);
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            RemoteGuiAuth.deauthorize(sp.getUUID());
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
