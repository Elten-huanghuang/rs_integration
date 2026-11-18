package com.huanghuang.rsintegration.feature.sidepanel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Extracts sync/delta packet handling from {@link RSSidePanelClient}.
 */
final class SyncHandler {

    private static final Logger LOGGER = LogManager.getLogger("RS Integration");
    private SyncHandler() {}

    // ── Chunked sync accumulator (§13.5 N-1) ─────────────────────────
    private static final Map<Integer, RSSidePanelSyncPacket> chunkAccum = new HashMap<>();
    private static int expectedChunks = 0;
    private static long lastChunkTime = 0;
    private static final long CHUNK_EXPIRY_MS = 5_000;
    private static long appliedGeneration = -1L;
    private static long assemblingGeneration = -1L;

    static void onSyncReceived(RSSidePanelSyncPacket packet) {
        if (packet.generation > 0 && packet.generation < Math.max(appliedGeneration, assemblingGeneration)) return;
        if (packet.isChunked()) {
            handleChunked(packet);
            return;
        }
        clearAssembly();
        applySync(packet.ids, packet.items, packet.timestamps, packet.craftableFlags,
                packet.totalSlotCount, packet.networkAvailable, packet.networkName);
        if (packet.generation > 0) appliedGeneration = packet.generation;
    }

    /** Clear the chunked-sync accumulator on client logout so a stale partial
     *  sync from a previous server can't bleed into the next one. */
    public static void clearOnLogout() {
        clearAssembly();
        appliedGeneration = -1L;
    }

    private static void clearAssembly() {
        chunkAccum.clear();
        expectedChunks = 0;
        assemblingGeneration = -1L;
        lastChunkTime = 0;
    }

    private static void handleChunked(RSSidePanelSyncPacket packet) {
        long now = System.currentTimeMillis();
        // Keep an in-flight generation intact while later chunks arrive.  Only
        // an expired assembly or a new chunk-0 starts a fresh accumulator.
        if (lastChunkTime > 0 && now - lastChunkTime > CHUNK_EXPIRY_MS) {
            chunkAccum.clear();
            expectedChunks = 0;
            assemblingGeneration = -1L;
        }
        lastChunkTime = now;

        if (packet.chunkIndex < 0 || packet.chunkIndex >= packet.totalChunks) return;
        if (assemblingGeneration != packet.generation || expectedChunks == 0) {
            if (packet.generation > 0 && packet.generation < appliedGeneration) return;
            chunkAccum.clear();
            expectedChunks = packet.totalChunks;
            assemblingGeneration = packet.generation;
        }
        if (packet.generation != assemblingGeneration) return;
        if (packet.totalChunks != expectedChunks) return;

        chunkAccum.put(packet.chunkIndex, packet);
        LOGGER.trace("[RSI] Chunk {}/{} received", packet.chunkIndex + 1, expectedChunks);

        if (chunkAccum.size() == expectedChunks) {
            List<UUID> allIds = new ArrayList<>();
            List<ItemStack> allItems = new ArrayList<>();
            List<Long> allTimestamps = new ArrayList<>();
            List<Boolean> allFlags = new ArrayList<>();
            for (int i = 0; i < expectedChunks; i++) {
                RSSidePanelSyncPacket chunk = chunkAccum.get(i);
                if (chunk == null) return;
                allIds.addAll(chunk.ids);
                allItems.addAll(chunk.items);
                allTimestamps.addAll(chunk.timestamps);
                allFlags.addAll(chunk.craftableFlags);
            }

            LOGGER.debug("[RSI] All {} chunks received — applying merged sync ({} items)",
                    expectedChunks, allItems.size());

            applySync(allIds, allItems, allTimestamps, allFlags,
                    chunkAccum.get(0).totalSlotCount,
                    chunkAccum.get(0).networkAvailable,
                    chunkAccum.get(0).networkName);
            if (assemblingGeneration > 0) appliedGeneration = assemblingGeneration;

            clearAssembly();
        }
    }

    private static void applySync(List<UUID> ids, List<ItemStack> items,
                                  List<Long> timestamps, List<Boolean> flags,
                                  int totalSlotCount, boolean networkAvailable,
                                  String networkName) {
        List<PanelStack> newPanels = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            UUID id = i < ids.size() ? ids.get(i) : UUID.randomUUID();
            long ts = i < timestamps.size() ? timestamps.get(i) : 0L;
            boolean cf = i < flags.size() ? flags.get(i) : false;
            newPanels.add(new PanelStack(id, s, ts, cf));
        }

        RSSidePanelClient.dataModel.fullSync(newPanels, totalSlotCount, networkAvailable, networkName);
        RSSidePanelClient.clampScroll();

    }

    static void onDeltaReceived(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        RSSidePanelClient.dataModel.applyDelta(id, stack, timestamp, craftable,
                RSIntegrationConfig.RS_SIDE_PANEL_MAX_SLOTS.get(), System.currentTimeMillis());
    }

    static void onOperationResult(RSSidePanelOperationResultPacket packet) {
        long opId = packet.operationId();
        UUID trackedStackId = RSSidePanelClient.dataModel.acknowledge(opId);

        if (!packet.success()) {
            LOGGER.debug("[RSI] Operation {} failed: code={}", opId, packet.errorCode());
            // Keep the pending extraction until an authoritative delta/full sync
            // arrives. Removing it here would discard the rollback snapshot even
            // though failed operations normally produce no storage delta.
        } else {
            LOGGER.debug("[RSI] Operation {} succeeded: count={}", opId, packet.actualCount());
        }

        UUID packetStackId = packet.stackId();
        if (trackedStackId != null && packetStackId != null
                && !trackedStackId.equals(packetStackId)) {
            LOGGER.warn("[RSI] Operation {} stack mismatch: tracked={}, acknowledged={}",
                    opId, trackedStackId, packetStackId);
        }
    }
}
