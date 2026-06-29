package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Extracts sync/delta packet handling from {@link RSSidePanelClient}.
 */
final class SyncHandler {

    private SyncHandler() {}

    // ── Chunked sync accumulator (§13.5 N-1) ─────────────────────────
    private static final Map<Integer, RSSidePanelSyncPacket> chunkAccum = new HashMap<>();
    private static int expectedChunks = 0;
    private static long lastChunkTime = 0;
    private static final long CHUNK_EXPIRY_MS = 30_000;

    static void onSyncReceived(RSSidePanelSyncPacket packet) {
        if (packet.isChunked()) {
            handleChunked(packet);
            return;
        }
        applySync(packet.ids, packet.items, packet.timestamps, packet.craftableFlags,
                packet.totalSlotCount, packet.networkAvailable, packet.networkName,
                packet.getBindings());
    }

    private static void handleChunked(RSSidePanelSyncPacket packet) {
        long now = System.currentTimeMillis();
        if (now - lastChunkTime > CHUNK_EXPIRY_MS) {
            chunkAccum.clear();
            expectedChunks = 0;
        }
        lastChunkTime = now;

        if (packet.chunkIndex == 0) {
            chunkAccum.clear();
            expectedChunks = packet.totalChunks;
        }
        if (packet.totalChunks != expectedChunks) return;

        chunkAccum.put(packet.chunkIndex, packet);
        RSIntegrationMod.LOGGER.debug("[RSI] Chunk {}/{} received", packet.chunkIndex + 1, expectedChunks);

        if (chunkAccum.size() == expectedChunks) {
            List<UUID> allIds = new ArrayList<>();
            List<ItemStack> allItems = new ArrayList<>();
            List<Long> allTimestamps = new ArrayList<>();
            List<Boolean> allFlags = new ArrayList<>();
            List<BindingInfo> allBindings = new ArrayList<>();

            for (int i = 0; i < expectedChunks; i++) {
                RSSidePanelSyncPacket chunk = chunkAccum.get(i);
                if (chunk == null) return;
                allIds.addAll(chunk.ids);
                allItems.addAll(chunk.items);
                allTimestamps.addAll(chunk.timestamps);
                allFlags.addAll(chunk.craftableFlags);
                if (i == 0 && chunk.bindings != null) allBindings.addAll(chunk.bindings);
            }

            RSIntegrationMod.LOGGER.debug("[RSI] All {} chunks received — applying merged sync ({} items)",
                    expectedChunks, allItems.size());

            applySync(allIds, allItems, allTimestamps, allFlags,
                    chunkAccum.get(0).totalSlotCount,
                    chunkAccum.get(0).networkAvailable,
                    chunkAccum.get(0).networkName,
                    allBindings);

            chunkAccum.clear();
            expectedChunks = 0;
        }
    }

    private static void applySync(List<UUID> ids, List<ItemStack> items,
                                  List<Long> timestamps, List<Boolean> flags,
                                  int totalSlotCount, boolean networkAvailable,
                                  String networkName, List<BindingInfo> bindings) {
        int prevSize = RSSidePanelClient.panels.size();
        boolean wasAvailable = RSSidePanelClient.networkAvailable;
        if (items.isEmpty() && prevSize > 0) {
            RSIntegrationMod.LOGGER.warn("[RSI] Received empty sync — clearing {} panel entries. networkAvailable was {}, now {}",
                    prevSize, wasAvailable, networkAvailable);
        }
        RSSidePanelClient.panels.clear();
        RSSidePanelClient.idToIndex.clear();
        RSSidePanelClient.pendingExtractions.clear();

        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            UUID id = i < ids.size() ? ids.get(i) : UUID.randomUUID();
            long ts = i < timestamps.size() ? timestamps.get(i) : 0L;
            boolean cf = i < flags.size() ? flags.get(i) : false;
            PanelStack ps = new PanelStack(id, s, ts, cf);
            RSSidePanelClient.idToIndex.put(id, RSSidePanelClient.panels.size());
            RSSidePanelClient.panels.add(ps);
        }

        RSSidePanelClient.totalSlotCount = totalSlotCount;
        RSSidePanelClient.networkAvailable = networkAvailable;
        RSSidePanelClient.networkName = networkName;
        RSSidePanelClient.displayDirty = true;
        RSSidePanelClient.clampScroll();

        RSSidePanelClient.dataModel.updatePanels(new ArrayList<>(RSSidePanelClient.panels), RSSidePanelClient.totalSlotCount);
        BindingCache.getInstance().updateBindings(bindings);
    }

    static void onDeltaReceived(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        if (stack == null || stack.getItem() == null || id == null) return;

        RSIntegrationMod.LOGGER.debug("[RSI-Delta] Client received: id={} item={} count={} craftable={}",
                id, ForgeRegistries.ITEMS.getKey(stack.getItem()), stack.getCount(), craftable);

        RSSidePanelClient.pendingExtractions.remove(id);

        int count = stack.getCount();
        Integer idx = RSSidePanelClient.idToIndex.get(id);
        PanelStack existing = idx != null && idx < RSSidePanelClient.panels.size() ? RSSidePanelClient.panels.get(idx) : null;
        int oldCount = existing != null ? existing.getCount() : 0;
        int delta = count - oldCount;

        if (count <= 0 && existing == null) {
            RSIntegrationMod.LOGGER.debug("[RSI] Delta zero for unknown UUID {} (item={} key={}) — searchKey matching",
                    id, stack.getHoverName().getString(), RSSidePanelClient.keyOf(stack));
        }

        UUID animId = id;

        if (count <= 0) {
            if (existing != null) {
                existing.setCount(0);
                existing.craftable = craftable;
                animId = existing.getId();
            } else {
                String deadKey = RSSidePanelClient.keyOf(stack);
                RSSidePanelClient.clearPendingBySearchKey(deadKey);
                for (PanelStack p : RSSidePanelClient.panels) {
                    if (p.searchKey().equals(deadKey)) {
                        p.setCount(0);
                        animId = p.getId();
                        break;
                    }
                }
            }
        } else {
            if (existing != null) {
                existing.setCount(count);
                existing.timestamp = timestamp;
                existing.craftable = craftable;
                animId = existing.getId();
            } else {
                String newKey = RSSidePanelClient.keyOf(stack);
                RSSidePanelClient.clearPendingBySearchKey(newKey);
                PanelStack mergeTarget = null;
                for (PanelStack p : RSSidePanelClient.panels) {
                    if (p.searchKey().equals(newKey)) { mergeTarget = p; break; }
                }
                if (mergeTarget != null) {
                    mergeTarget.setCount(count);
                    mergeTarget.timestamp = timestamp;
                    mergeTarget.craftable = craftable;
                    animId = mergeTarget.getId();
                } else {
                    PanelStack ps = new PanelStack(id, stack, timestamp, craftable);
                    RSSidePanelClient.idToIndex.put(id, RSSidePanelClient.panels.size());
                    RSSidePanelClient.panels.add(ps);
                    RSSidePanelClient.totalSlotCount++;
                    animId = id;
                }
            }
        }

        if (delta != 0) RSSidePanelClient.recordSlotAnim(animId, delta);

        RSSidePanelClient.deltaBatch.add(id);
        RSSidePanelClient.deltaBatchDirty = true;
        RSSidePanelClient.displayDirty = true;

        RSSidePanelClient.dataModel.applyDelta(id, stack, timestamp, craftable);
    }
}
