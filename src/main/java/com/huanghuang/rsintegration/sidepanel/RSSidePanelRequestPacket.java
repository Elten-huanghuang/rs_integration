package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
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

    static void refreshOnServerThread(ServerPlayer player, boolean forceFullSync) {
        UUID id = player.getUUID();
        REFRESH_TASKS.remove(id);
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            RSSidePanelNetworkHandler.unregisterListener(id);
            RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, false, "");
            return;
        }
        try {
            RSSidePanelNetworkHandler.registerListener(player, network);
            IStorageCache<?> cache = network.getItemStorageCache();
            var list = cache == null ? null : cache.getList();
            if (list == null) {
                RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true, "");
                return;
            }
            REFRESH_TASKS.put(id, new RefreshTask(player, network, list.getStacks().iterator()));
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] SidePanel refresh setup failed", e);
            RSSidePanelNetworkHandler.sendSync(player, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true, "");
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

    private static final class RefreshTask {
        final UUID playerId;
        final ServerPlayer player;
        final INetwork network;
        final java.util.Iterator<?> entries;
        final List<UUID> ids = new ArrayList<>();
        final List<ItemStack> items = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
        final List<Boolean> craftable = new ArrayList<>();
        int total;
        final Set<String> craftableKeys = new HashSet<>();
        final String networkName;

        RefreshTask(ServerPlayer player, INetwork network, java.util.Iterator<?> entries) {
            this.player = player; this.playerId = player.getUUID(); this.network = network; this.entries = entries;
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
                Object entry = entries.next();
                try {
                    ItemStack stored = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                    if (stored == null || stored.isEmpty()) continue;
                    total++;
                    UUID id = (UUID) entry.getClass().getMethod("getId").invoke(entry);
                    ids.add(id); items.add(stored.copy());
                    var tracked = tracker != null ? tracker.get(stored) : null;
                    timestamps.add(tracked != null ? tracked.getTime() : 0L);
                    var itemKey = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stored.getItem());
                    craftable.add(itemKey != null && craftableKeys.contains(itemKey.toString()));
                } catch (ReflectiveOperationException | ClassCastException ignored) {
                    RSIntegrationMod.LOGGER.debug("[RSI] Invalid storage entry during refresh");
                }
            }
            if (entries.hasNext()) return true;
            RSSidePanelNetworkHandler.sendSync(player, ids, items, timestamps, craftable, total, true, networkName);
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
