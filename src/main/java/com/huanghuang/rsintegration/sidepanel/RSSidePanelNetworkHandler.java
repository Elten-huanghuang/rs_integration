package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RSSidePanelNetworkHandler {

    private static final String PROTOCOL_VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "rs_side_panel"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    // ── Per-player server-side cache + storage-cache listener ─────
    private static final Map<UUID, ListenerEntry> playerListeners = new ConcurrentHashMap<>();
    private static net.minecraft.server.MinecraftServer cachedServer;

    // ── Pending deltas per player — collected during a tick, flushed at end ──
    private static final Map<UUID, List<RSSidePanelDeltaPacket.Entry>> pendingDeltas = new ConcurrentHashMap<>();
    private static boolean tickHookRegistered;
    private static boolean logoutHookRegistered;

    private RSSidePanelNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(0,
                RSSidePanelRequestPacket.class,
                RSSidePanelRequestPacket::encode,
                RSSidePanelRequestPacket::decode,
                RSSidePanelRequestPacket::handle);
        CHANNEL.registerMessage(1,
                RSSidePanelSyncPacket.class,
                RSSidePanelSyncPacket::encode,
                RSSidePanelSyncPacket::decode,
                RSSidePanelSyncPacket::handle);
        CHANNEL.registerMessage(2,
                RSSidePanelClickPacket.class,
                RSSidePanelClickPacket::encode,
                RSSidePanelClickPacket::decode,
                RSSidePanelClickPacket::handle);
        CHANNEL.registerMessage(3,
                RSSidePanelDeltaPacket.class,
                RSSidePanelDeltaPacket::encode,
                RSSidePanelDeltaPacket::decode,
                RSSidePanelDeltaPacket::handle);
        registered = true;

        if (!logoutHookRegistered) {
            MinecraftForge.EVENT_BUS.register(RSSidePanelNetworkHandler.class);
            logoutHookRegistered = true;
        }
        if (!tickHookRegistered) {
            MinecraftForge.EVENT_BUS.addListener(RSSidePanelNetworkHandler::onServerTickEnd);
            tickHookRegistered = true;
        }
    }

    // ── Tick-end delta flush ──────────────────────────────────────

    private static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (pendingDeltas.isEmpty()) return;

        // Snapshot and clear — matching RS native GridItemDeltaMessage batching
        Map<UUID, List<RSSidePanelDeltaPacket.Entry>> snapshot = new HashMap<>(pendingDeltas);
        pendingDeltas.clear();

        for (var entry : snapshot.entrySet()) {
            UUID playerId = entry.getKey();
            List<RSSidePanelDeltaPacket.Entry> deltas = entry.getValue();
            if (deltas.isEmpty()) continue;

            ServerPlayer player = findPlayer(event.getServer(), playerId);
            if (player == null) continue;

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

    // ── convenience senders ────────────────────────────────────────

    public static void sendRequestSync() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket());
    }

    public static void sendSync(ServerPlayer player, List<UUID> ids, List<ItemStack> items,
                                List<Long> timestamps,
                                List<Boolean> craftableFlags,
                                int totalSlotCount, boolean networkAvailable,
                                String networkName) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelSyncPacket(ids, items, timestamps, craftableFlags,
                        totalSlotCount, networkAvailable, networkName));
    }

    public static void sendClick(ItemStack targetItem, byte action, boolean isShift) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(targetItem, action, isShift));
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
        pendingDeltas.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
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
                playerListeners.remove(pid);
                // Notify client immediately — matches RS GridScreen closing on network loss
                net.minecraft.server.MinecraftServer server = cachedServer;
                if (server != null) {
                    ServerPlayer sp = server.getPlayerList().getPlayer(pid);
                    if (sp != null) {
                        sendSync(sp,
                                Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                0, false, "");
                    }
                }
            }

            private void queue(ItemStack stack, int change, UUID entryId) {
                if (stack == null || stack.getItem() == null) return;

                // Get stable UUID from the storage cache entry when possible
                UUID stackId = entryId;
                if (stackId == null) {
                    // Fallback: try to look up from cache list
                    var list = cache.getList();
                    if (list != null) {
                        var entry = list.getEntry(stack, 1);
                        if (entry != null) stackId = entry.getId();
                    }
                }
                if (stackId == null) return;

                ItemStack toSend;
                if (stack.getCount() <= 0) {
                    toSend = new ItemStack(stack.getItem(), 0);
                    if (stack.getTag() != null) toSend.setTag(stack.getTag().copy());
                } else {
                    toSend = stack.copy();
                }

                long ts = System.currentTimeMillis();
                if (tracker != null) {
                    var trackerEntry = tracker.get(stack);
                    if (trackerEntry != null) ts = trackerEntry.getTime();
                }
                var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                boolean craftable = k != null && craftableKeys.contains(k);

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
        playerListeners.put(pid, new ListenerEntry(listener, cache));
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

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            unregisterListener(sp.getUUID());
        }
    }

    // ── inner types ────────────────────────────────────────────────

    private static class ListenerEntry {
        final IStorageCacheListener<ItemStack> listener;
        final IStorageCache<ItemStack> cache;

        ListenerEntry(IStorageCacheListener<ItemStack> listener,
                      IStorageCache<ItemStack> cache) {
            this.listener = listener;
            this.cache = cache;
        }
    }
}
