package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Set;
import java.util.HashSet;

public final class RSSidePanelRequestPacket {

    final boolean forceFullSync;
    final boolean isClosing;

    RSSidePanelRequestPacket() {
        this(false, false);
    }

    RSSidePanelRequestPacket(boolean forceFullSync, boolean isClosing) {
        this.forceFullSync = forceFullSync;
        this.isClosing = isClosing;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(forceFullSync);
        buf.writeBoolean(isClosing);
    }

    static RSSidePanelRequestPacket decode(FriendlyByteBuf buf) {
        boolean forceFullSync = buf.readBoolean();
        boolean isClosing = buf.readBoolean();
        return new RSSidePanelRequestPacket(forceFullSync, isClosing);
    }

    private static final java.util.Map<UUID, RefreshTask> REFRESH_TASKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int ENTRIES_PER_TICK = 64;

    static boolean refreshOnServerThread(ServerPlayer player, boolean forceFullSync) {
        UUID id = player.getUUID();
        if (REFRESH_TASKS.containsKey(id)) return true;
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            network = RSSidePanelNetworkHandler.getListenerNetwork(id);
        }
        if (network == null) {
            RSSidePanelNetworkHandler.unregisterListener(id);
            RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, false, "");
            return false;
        }
        try {
            RSSidePanelNetworkHandler.registerListener(player, network);
            IStorageCache<ItemStack> cache = network.getItemStorageCache();
            if (cache == null) {
                RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true, "");
                return false;
            }
            var list = cache.getList();
            if (list == null) {
                RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true, "");
                return false;
            }
            // Snapshot both the collection and each mutable stack before the
            // refresh is spread across later server ticks.
            var available = list.getStacks();
            int snapshotLimit = SidePanelSyncPolicy.snapshotLimit(
                    RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get(), available.size());
            List<StackListEntry<ItemStack>> snapshot = new ArrayList<>(snapshotLimit);
            Set<UUID> included = new HashSet<>();
            var priorityTimestamps = RSSidePanelNetworkHandler.snapshotPriorities(id);
            java.util.Map<UUID, Long> acknowledgedPriorities = new java.util.LinkedHashMap<>();
            for (var priority : priorityTimestamps.entrySet()) {
                if (snapshot.size() >= snapshotLimit) break;
                UUID preferredId = priority.getKey();
                ItemStack current = list.get(preferredId);
                if (current != null && !current.isEmpty()) {
                    snapshot.add(new StackListEntry<>(preferredId, current.copy()));
                    included.add(preferredId);
                }
                acknowledgedPriorities.put(preferredId, priority.getValue());
            }
            for (UUID preferredId : RSSidePanelNetworkHandler.trackedStackIds(id)) {
                if (snapshot.size() >= snapshotLimit) break;
                if (included.contains(preferredId)) continue;
                ItemStack current = list.get(preferredId);
                if (current != null && !current.isEmpty()) {
                    snapshot.add(new StackListEntry<>(preferredId, current.copy()));
                    included.add(preferredId);
                }
            }
            for (StackListEntry<ItemStack> entry : available) {
                if (snapshot.size() >= snapshotLimit) break;
                if (entry == null || !included.add(entry.getId())) continue;
                ItemStack stack = entry.getStack();
                if (stack != null && !stack.isEmpty()) {
                    snapshot.add(new StackListEntry<>(entry.getId(), stack.copy()));
                }
            }
            REFRESH_TASKS.put(id, new RefreshTask(player, network, snapshot.iterator(),
                    available.size(), acknowledgedPriorities));
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] SidePanel refresh setup failed", e);
            RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true, "");
            return false;
        }
    }

    static void advanceRefreshTasks(net.minecraft.server.MinecraftServer server) {
        for (RefreshTask task : List.copyOf(REFRESH_TASKS.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(task.playerId);
            if (player == null || REFRESH_TASKS.get(task.playerId) != task) {
                REFRESH_TASKS.remove(task.playerId, task);
                continue;
            }
            if (!task.advance()) REFRESH_TASKS.remove(task.playerId, task);
        }
    }

    static void cancelRefresh(UUID playerId) { REFRESH_TASKS.remove(playerId); }

    static boolean hasRefresh(UUID playerId) { return REFRESH_TASKS.containsKey(playerId); }

    private static final class RefreshTask {
        final UUID playerId;
        final ServerPlayer player;
        final INetwork network;
        final java.util.Iterator<StackListEntry<ItemStack>> entries;
        final List<UUID> ids = new ArrayList<>();
        final List<ItemStack> items = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
        final List<Boolean> craftable = new ArrayList<>();
        int total;
        final Set<String> craftableKeys = new HashSet<>();
        final java.util.Map<UUID, Long> priorityTimestamps;
        final String networkName;

        RefreshTask(ServerPlayer player, INetwork network,
                    java.util.Iterator<StackListEntry<ItemStack>> entries,
                    int totalSlotCount, java.util.Map<UUID, Long> priorityTimestamps) {
            this.player = player; this.playerId = player.getUUID(); this.network = network; this.entries = entries;
            this.total = Math.max(0, totalSlotCount);
            this.priorityTimestamps = java.util.Map.copyOf(priorityTimestamps);
            this.networkName = resolveNetworkName(network);
            try {
                var manager = network.getCraftingManager();
                if (manager != null) for (var pattern : manager.getPatterns()) {
                    for (ItemStack output : pattern.getOutputs()) {
                        if (!output.isEmpty()) {
                            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(output.getItem());
                            if (key != null) craftableKeys.add(key.toString());
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI] Craftable snapshot failed", e);
            }
        }

        boolean advance() {
            int processed = 0;
            var tracker = network.getItemStorageTracker();
            while (entries.hasNext() && processed++ < ENTRIES_PER_TICK) {
                StackListEntry<ItemStack> entry = entries.next();
                try {
                    ItemStack stored = entry.getStack();
                    if (stored == null || stored.isEmpty()) continue;
                    UUID id = entry.getId();
                    ids.add(id); items.add(stored.copy());
                    var tracked = tracker != null ? tracker.get(stored) : null;
                    long trackedTime = tracked != null ? tracked.getTime() : 0L;
                    timestamps.add(trackedTime > 0L
                            ? trackedTime
                            : priorityTimestamps.getOrDefault(id, 0L));
                    var itemKey = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stored.getItem());
                    craftable.add(itemKey != null && craftableKeys.contains(itemKey.toString()));
                } catch (RuntimeException ignored) {
                    RSIntegrationMod.LOGGER.debug("[RSI] Invalid storage entry during refresh");
                }
            }
            if (entries.hasNext()) return true;
            RSSidePanelNetworkHandler.sendSync(player, ids, items, timestamps, craftable, total, true, networkName);
            if (RSSidePanelNetworkHandler.acknowledgeSnapshotPriorities(
                    playerId, priorityTimestamps)) {
                RSSidePanelNetworkHandler.schedulePriorityRefresh(
                        playerId, player.getServer().getTickCount());
            }
            return false;
        }
    }

    private static String resolveNetworkName(INetwork network) {
        try {
            var level = network.getLevel();
            var pos = network.getPosition();
            if (level != null && pos != null) return level.getBlockState(pos).getBlock().getName().getString();
        } catch (Exception ignored) {}
        return "";
    }

    static void handle(RSSidePanelRequestPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (!RSSidePanelModule.isEnabled()) {
            context.setPacketHandled(true);
            return;
        }
        ServerPlayer player = context.getSender();
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            if (packet.isClosing) {
                RSSidePanelNetworkHandler.unregisterListener(player.getUUID());
                return;
            }
            if (SidePanelRequestRateLimiter.isRateLimited(player.getUUID())) return;
            RSSidePanelNetworkHandler.startRefresh(player, packet.forceFullSync);
        });
        context.setPacketHandled(true);
    }

}
