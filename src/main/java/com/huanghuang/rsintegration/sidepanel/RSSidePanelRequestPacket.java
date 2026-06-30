package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class RSSidePanelRequestPacket {

    final boolean forceFullSync;
    final boolean isClosing;
    final byte sortMode;
    final boolean sortAsc;

    RSSidePanelRequestPacket() {
        this(false, false, (byte) 0, true);
    }

    RSSidePanelRequestPacket(boolean forceFullSync, boolean isClosing) {
        this(forceFullSync, isClosing, (byte) 0, true);
    }

    RSSidePanelRequestPacket(boolean forceFullSync, boolean isClosing, byte sortMode, boolean sortAsc) {
        this.forceFullSync = forceFullSync;
        this.isClosing = isClosing;
        this.sortMode = sortMode;
        this.sortAsc = sortAsc;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(forceFullSync);
        buf.writeBoolean(isClosing);
        buf.writeByte(sortMode);
        buf.writeBoolean(sortAsc);
    }

    static RSSidePanelRequestPacket decode(FriendlyByteBuf buf) {
        boolean forceFullSync = buf.readBoolean();
        boolean isClosing = buf.readBoolean();
        byte sortMode = 0;
        boolean sortAsc = true;
        if (buf.readableBytes() >= 2) {
            sortMode = buf.readByte();
            sortAsc = buf.readBoolean();
        }
        return new RSSidePanelRequestPacket(forceFullSync, isClosing, sortMode, sortAsc);
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

            int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();

            INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
            if (network == null) {
                RSSidePanelNetworkHandler.unregisterListener(player.getUUID());
                RSSidePanelNetworkHandler.sendSync(player,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        0, false, "");
                return;
            }

            RSSidePanelNetworkHandler.registerListener(player, network);

            try {
                IStorageCache<?> cache = network.getItemStorageCache();
                if (cache == null) {
                    RSSidePanelNetworkHandler.sendSync(player,
                            Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(), Collections.emptyList(),
                            0, true, "");
                    return;
                }
                var list = cache.getList();
                if (list == null) {
                    RSSidePanelNetworkHandler.sendSync(player,
                            Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(), Collections.emptyList(),
                            0, true, "");
                    return;
                }

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
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Craftable probe failed", e); }

                // Collect ALL items first, then sort by client-requested mode, then truncate.
                List<UUID> allIds = new ArrayList<>();
                List<ItemStack> allItems = new ArrayList<>();
                List<Long> allTimestamps = new ArrayList<>();
                List<Boolean> allCraftableFlags = new ArrayList<>();
                int totalCount = 0;
                var tracker = network.getItemStorageTracker();
                for (var entry : list.getStacks()) {
                    Object raw = entry.getStack();
                    if (!(raw instanceof ItemStack stored) || stored.isEmpty()) continue;
                    totalCount++;
                    allIds.add(entry.getId());
                    allItems.add(stored.copy());
                    long ts = 0L;
                    if (tracker != null) {
                        var trackerEntry = tracker.get(stored);
                        if (trackerEntry != null) ts = trackerEntry.getTime();
                    }
                    allTimestamps.add(ts);
                    var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stored.getItem());
                    allCraftableFlags.add(k != null && craftableKeys.contains(k));
                }

                // Sort according to client's active sort mode so truncation
                // keeps the most relevant items for the current view.
                Integer[] indices = new Integer[allItems.size()];
                for (int i = 0; i < indices.length; i++) indices[i] = i;
                Comparator<Integer> cmp = buildComparator(packet.sortMode, packet.sortAsc, allItems, allTimestamps);
                java.util.Arrays.sort(indices, cmp);

                // Truncate to maxSlots AFTER sorting
                int limit = Math.min(indices.length, maxSlots);
                List<UUID> ids = new ArrayList<>(limit);
                List<ItemStack> items = new ArrayList<>(limit);
                List<Long> timestamps = new ArrayList<>(limit);
                List<Boolean> craftableFlags = new ArrayList<>(limit);
                for (int i = 0; i < limit; i++) {
                    int idx = indices[i];
                    ids.add(allIds.get(idx));
                    items.add(allItems.get(idx));
                    timestamps.add(allTimestamps.get(idx));
                    craftableFlags.add(allCraftableFlags.get(idx));
                }

                String netName = "";
                try {
                    var level = network.getLevel();
                    var pos = network.getPosition();
                    if (level != null && pos != null) {
                        netName = level.getBlockState(pos).getBlock().getName().getString();
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Name probe failed", e); }

                RSSidePanelNetworkHandler.sendSync(player, ids, items, timestamps, craftableFlags, totalCount, true, netName);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] SidePanel request error: {}", e.toString());
                RSSidePanelNetworkHandler.sendSync(player,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        0, true, "");
            }
        });
        context.setPacketHandled(true);
    }

    private static Comparator<Integer> buildComparator(byte sortMode, boolean asc,
                                                       List<ItemStack> items, List<Long> timestamps) {
        return switch (sortMode) {
            case 1 -> { // Count
                Comparator<Integer> c = Comparator.comparingInt(a -> items.get(a).getCount());
                yield asc ? c : c.reversed();
            }
            case 3 -> { // Timestamp (last modified)
                Comparator<Integer> c = Comparator.comparingLong(timestamps::get);
                yield asc ? c : c.reversed();
            }
            default -> { // 0=Name, 2=Registry key — both use registry key
                Comparator<Integer> c = (a, b) -> {
                    var ka = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(items.get(a).getItem());
                    var kb = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(items.get(b).getItem());
                    String sa = ka != null ? ka.toString() : "";
                    String sb = kb != null ? kb.toString() : "";
                    return sa.compareToIgnoreCase(sb);
                };
                yield asc ? c : c.reversed();
            }
        };
    }
}
