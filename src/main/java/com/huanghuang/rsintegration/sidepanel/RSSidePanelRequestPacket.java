package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class RSSidePanelRequestPacket {

    /** If true, the server should force a full re-scan even if a listener is active. */
    final boolean forceFullSync;

    RSSidePanelRequestPacket() {
        this(false);
    }

    RSSidePanelRequestPacket(boolean forceFullSync) {
        this.forceFullSync = forceFullSync;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(forceFullSync);
    }

    static RSSidePanelRequestPacket decode(FriendlyByteBuf buf) {
        return new RSSidePanelRequestPacket(buf.readBoolean());
    }

    static void handle(RSSidePanelRequestPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            int maxSlots = RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get();

            INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
            if (network == null) {
                RSSidePanelNetworkHandler.unregisterListener(player.getUUID());
                RSSidePanelNetworkHandler.sendSync(player,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), 0, false, "");
                return;
            }

            // Always register/renew the push listener and perform a full
            // snapshot.  Skipping the scan based on listener state is unsafe:
            // a stale/detached listener would leave the client stuck with
            // no data.  The listener handles subsequent incremental deltas,
            // so the one-time full-scan cost is acceptable.
            RSSidePanelNetworkHandler.registerListener(player, network);

            try {
                IStorageCache<?> cache = network.getItemStorageCache();
                if (cache == null) {
                    RSSidePanelNetworkHandler.sendSync(player,
                            Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(), 0, true, "");
                    return;
                }
                var list = cache.getList();
                if (list == null) {
                    RSSidePanelNetworkHandler.sendSync(player,
                            Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(), 0, true, "");
                    return;
                }

                // Build craftable item set from crafting patterns
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
                } catch (Exception ignored) {}

                List<ItemStack> items = new ArrayList<>();
                List<Long> timestamps = new ArrayList<>();
                List<Boolean> craftableFlags = new ArrayList<>();
                int totalCount = 0;
                var tracker = network.getItemStorageTracker();
                for (var entry : list.getStacks()) {
                    Object raw = entry.getStack();
                    if (!(raw instanceof ItemStack stored) || stored.isEmpty()) continue;
                    totalCount++;
                    if (items.size() < maxSlots) {
                        items.add(stored.copy());
                        long ts = 0L;
                        if (tracker != null) {
                            var trackerEntry = tracker.get(stored);
                            if (trackerEntry != null) ts = trackerEntry.getTime();
                        }
                        timestamps.add(ts);
                        var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stored.getItem());
                        craftableFlags.add(k != null && craftableKeys.contains(k));
                    }
                }

                String netName = "";
                try {
                    var level = network.getLevel();
                    var pos = network.getPosition();
                    if (level != null && pos != null) {
                        netName = level.getBlockState(pos).getBlock().getName().getString();
                    }
                } catch (Exception ignored) {}

                RSSidePanelNetworkHandler.sendSync(player, items, timestamps, craftableFlags, totalCount, true, netName);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.info("[RSI] SidePanel request error: {}", e.toString());
                RSSidePanelNetworkHandler.sendSync(player,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), 0, true, "");
            }
        });
        context.setPacketHandled(true);
    }
}
