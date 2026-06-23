package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "rs_side_panel"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    // Per-player server-side cache to avoid re-scanning RS storage on every poll
    private static final Map<UUID, CachedItemList> cache = new HashMap<>();
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

    public static void sendClick(int slotIndex, byte action, boolean isShift) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(slotIndex, action, isShift));
    }

    public static void sendDragDistribute(List<Integer> slots) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(slots));
    }

    public static void sendInsert(int slotIndex, ItemStack carried) {
        CHANNEL.sendToServer(new RSSidePanelClickPacket(slotIndex, carried));
    }

    // ── server-side cache ──────────────────────────────────────────

    /** Returns cached items regardless of TTL — used for click validation. */
    public static List<ItemStack> getCachedItems(UUID playerId) {
        CachedItemList entry = cache.get(playerId);
        int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();
        if (entry != null && entry.maxSlots == maxSlots) {
            return entry.items;
        }
        return null;
    }

    /** Returns cached items only if fresh (within 1 second) — used to skip re-scanning. */
    public static List<ItemStack> getFreshCachedItems(UUID playerId) {
        CachedItemList entry = cache.get(playerId);
        int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();
        if (entry != null && entry.maxSlots == maxSlots
                && System.currentTimeMillis() - entry.timestamp < 1000) {
            return entry.items;
        }
        return null;
    }

    /** Returns cached timestamps — only valid alongside getFreshCachedItems. */
    public static List<Long> getCachedTimestamps(UUID playerId) {
        CachedItemList entry = cache.get(playerId);
        int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();
        if (entry != null && entry.maxSlots == maxSlots) {
            return entry.timestamps;
        }
        return null;
    }

    /** Returns cached craftable flags — only valid alongside getFreshCachedItems. */
    public static List<Boolean> getCachedCraftableFlags(UUID playerId) {
        CachedItemList entry = cache.get(playerId);
        int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();
        if (entry != null && entry.maxSlots == maxSlots) {
            return entry.craftableFlags;
        }
        return null;
    }

    public static void putCachedItems(UUID playerId, List<ItemStack> items,
                                      List<Long> timestamps,
                                      List<Boolean> craftableFlags, int total) {
        int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();
        cache.put(playerId, new CachedItemList(items, timestamps, craftableFlags, total, maxSlots));
    }

    public static void invalidateCache(UUID playerId) {
        cache.remove(playerId);
    }

    public static int getCachedTotal(UUID playerId) {
        CachedItemList entry = cache.get(playerId);
        return entry != null ? entry.totalSlotCount : 0;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            cache.remove(event.getEntity().getUUID());
        }
    }

    // ── inner types ────────────────────────────────────────────────

    static class CachedItemList {
        final List<ItemStack> items;
        final List<Long> timestamps;
        final List<Boolean> craftableFlags;
        final int totalSlotCount;
        final int maxSlots;
        final long timestamp;

        CachedItemList(List<ItemStack> items, List<Long> timestamps,
                       List<Boolean> craftableFlags, int totalSlotCount, int maxSlots) {
            this.items = new ArrayList<>(items);
            this.timestamps = new ArrayList<>(timestamps);
            this.craftableFlags = new ArrayList<>(craftableFlags);
            this.totalSlotCount = totalSlotCount;
            this.maxSlots = maxSlots;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
