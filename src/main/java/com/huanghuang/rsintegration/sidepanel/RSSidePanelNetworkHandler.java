package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;

public final class RSSidePanelNetworkHandler {

    private static final String PROTOCOL_VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "rs_side_panel"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    // ── Per-player server-side cache + storage-cache listener ─────
    private static final Map<UUID, ListenerEntry> playerListeners = new java.util.concurrent.ConcurrentHashMap<>();
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
    }

    // ── convenience senders ────────────────────────────────────────

    public static void sendRequestSync() {
        CHANNEL.sendToServer(new RSSidePanelRequestPacket());
    }

    public static void sendSync(ServerPlayer player, List<ItemStack> items,
                                List<Long> timestamps,
                                List<Boolean> craftableFlags,
                                int totalSlotCount, boolean networkAvailable,
                                String networkName) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RSSidePanelSyncPacket(items, timestamps, craftableFlags,
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

    // ── storage cache listener management ──────────────────────────

    /**
     * Registers an {@link IStorageCacheListener} on the RS storage cache so
     * the server pushes incremental deltas to the client instead of the client
     * polling for full re-syncs.
     */
    /**
     * Registers a push listener. Returns true if this was a fresh binding
     * (player had no listener before), false if it replaced a stale one.
     */
    @SuppressWarnings("unchecked")
    public static boolean registerListener(ServerPlayer player,
                                           com.refinedmods.refinedstorage.api.network.INetwork network) {
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return false;

        UUID pid = player.getUUID();
        boolean isNew = !playerListeners.containsKey(pid);
        unregisterListener(pid); // detach stale listener first

        // Snapshot craftable item keys at registration time
        var craftableKeys = new java.util.HashSet<net.minecraft.resources.ResourceLocation>();
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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        var tracker = network.getItemStorageTracker();

        IStorageCacheListener<ItemStack> listener = new IStorageCacheListener<>() {
            @Override
            public void onAttached() {}

            @Override
            public void onInvalidated() {}

            private void sendDelta(ItemStack stack, int change) {
                if (stack == null) return;
                if (stack.getItem() == null) return;

                // stack.copy() returns EMPTY for count=0 stacks because
                // ItemStack.isEmpty() checks count <= 0.  Manually construct
                // a count=0 copy so the delta packet carries the item identity.
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

                RSSidePanelDeltaPacket.send(player, toSend, ts, craftable);
            }

            @Override
            public void onChanged(StackListResult<ItemStack> delta) {
                sendDelta(delta.getStack(), delta.getChange());
            }

            @Override
            public void onChangedBulk(List<StackListResult<ItemStack>> deltas) {
                for (var d : deltas) sendDelta(d.getStack(), d.getChange());
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
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            unregisterListener(event.getEntity().getUUID());
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
