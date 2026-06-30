package com.huanghuang.rsintegration.sidepanel.model;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.PanelStack;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe data model for the side panel. Separated from rendering/input logic.
 * Holds all mutable panel state: item stacks, display list, animations,
 * pending extractions, and delta batching.
 *
 * <p>This is the first extraction step — RSSidePanelClient still holds its own
 * parallel fields.  A future stage will remove those and use only this model.</p>
 */
public final class PanelDataModel {

    // ── Core data structures (mirror RSSidePanelClient fields) ──────
    private final List<PanelStack> panels = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, Integer> idToIndex = new ConcurrentHashMap<>();

    private final List<PanelStack> displayList = new ArrayList<>();
    private volatile boolean displayDirty = true;

    private volatile int totalSlotCount;

    // ── Animation tracking ──────────────────────────────────────────
    private final Map<UUID, SlotAnim> slotAnims = new ConcurrentHashMap<>();
    private final Set<UUID> deltaBatch = ConcurrentHashMap.newKeySet();
    private volatile boolean deltaBatchDirty;

    // ── Pending extractions (client-side predictions) ───────────────
    private final Map<UUID, PendingExtraction> pendingExtractions = new ConcurrentHashMap<>();

    // ── Inner types ─────────────────────────────────────────────────

    public static class SlotAnim {
        public final long startTime;
        public final int delta;
        public SlotAnim(long t, int d) { startTime = t; delta = d; }
        public boolean expired() { return System.currentTimeMillis() - startTime > 400; }
        public float fade() { return 1.0F - Mth.clamp((System.currentTimeMillis() - startTime) / 400F, 0F, 1F); }
    }

    public static class PendingExtraction {
        public final ItemStack previousStack;
        public final long timestamp;
        public final boolean craftable;
        public final long createdAt;

        public PendingExtraction(ItemStack stack, long timestamp, boolean craftable) {
            this.previousStack = stack.copy();
            this.timestamp = timestamp;
            this.craftable = craftable;
            this.createdAt = System.currentTimeMillis();
        }
    }

    // ── Accessors ───────────────────────────────────────────────────

    /** Returns a defensive copy of the current panel list. */
    public List<PanelStack> getPanels() {
        synchronized (panels) { return new ArrayList<>(panels); }
    }

    /** Direct reference to the synchronized panel list — use with care. */
    public List<PanelStack> getPanelsRaw() { return panels; }

    /** Direct reference to the id→index map. */
    public Map<UUID, Integer> getIdToIndex() { return idToIndex; }

    public PanelStack getByIndex(int idx) {
        synchronized (panels) { return idx >= 0 && idx < panels.size() ? panels.get(idx) : null; }
    }

    public PanelStack getById(UUID id) {
        Integer idx = idToIndex.get(id);
        return idx != null && idx < panels.size() ? panels.get(idx) : null;
    }

    public int size() { return panels.size(); }
    public int getTotalSlotCount() { return totalSlotCount; }

    // ── Display list ────────────────────────────────────────────────

    public void markDirty() { displayDirty = true; }
    public boolean isDirty() { return displayDirty; }

    public List<PanelStack> getDisplayList() { return displayList; }

    /** Replace the display list (caller is responsible for filtering/sorting). */
    public void setDisplayList(List<PanelStack> list) {
        displayList.clear();
        displayList.addAll(list);
        displayDirty = false;
    }

    /** Clear display dirty without rebuilding (used when external rebuild happens). */
    public void clearDirty() { displayDirty = false; }

    // ── Animation & delta batch ─────────────────────────────────────

    public Map<UUID, SlotAnim> getSlotAnims() { return slotAnims; }

    public void recordSlotAnim(UUID id, int delta) {
        slotAnims.put(id, new SlotAnim(System.currentTimeMillis(), delta));
    }

    public Set<UUID> getDeltaBatch() { return deltaBatch; }

    public boolean isDeltaBatchDirty() { return deltaBatchDirty; }
    public void setDeltaBatchDirty(boolean v) { deltaBatchDirty = v; }

    /** Flush batched deltas — called once per tick. */
    public void flushDeltaBatch() {
        deltaBatchDirty = false;
        deltaBatch.clear();
        markDirty();
    }

    // ── Pending extractions ─────────────────────────────────────────

    public Map<UUID, PendingExtraction> getPendingExtractions() { return pendingExtractions; }

    // ── Full sync update ────────────────────────────────────────────

    /**
     * Replace all panels with a new set from a full-sync packet.
     * @param newPanels pre-built PanelStack list (already filtered for empty stacks)
     * @param totalSlots total slot count reported by the server
     */
    public void updatePanels(List<PanelStack> newPanels, int totalSlots) {
        synchronized (panels) {
            panels.clear();
            panels.addAll(newPanels);
            idToIndex.clear();
            for (int i = 0; i < newPanels.size(); i++) {
                idToIndex.put(newPanels.get(i).getId(), i);
            }
        }
        this.totalSlotCount = totalSlots;
        pendingExtractions.clear();
        markDirty();
    }

    // ── Delta merge (single entry update) ───────────────────────────

    /**
     * Apply a single delta update from the server.  Mirrors the logic in
     * {@code RSSidePanelClient.onDeltaReceived}.
     */
    public UUID applyDelta(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        if (stack == null || stack.getItem() == null || id == null) return null;

        pendingExtractions.remove(id);

        int count = stack.getCount();
        Integer idx = idToIndex.get(id);
        PanelStack existing = idx != null && idx < panels.size() ? panels.get(idx) : null;

        // Track which UUID the animation should target
        UUID animId = id;

        if (count <= 0) {
            // Match RS GridViewImpl.postChange(): map.remove(stackId)
            if (existing != null) {
                animId = existing.getId();
                removePanel(animId);
            } else {
                String deadKey = keyOf(stack);
                clearPendingBySearchKey(deadKey);
                UUID toRemove = null;
                synchronized (panels) {
                    for (PanelStack p : panels) {
                        if (p.searchKey().equals(deadKey)) {
                            toRemove = p.getId();
                            break;
                        }
                    }
                }
                if (toRemove != null) {
                    animId = toRemove;
                    removePanel(toRemove);
                }
            }
        } else {
            if (existing != null) {
                existing.setCount(count);
                existing.timestamp = timestamp;
                existing.craftable = craftable;
                animId = existing.getId();
            } else {
                String newKey = keyOf(stack);
                clearPendingBySearchKey(newKey);
                PanelStack mergeTarget = null;
                synchronized (panels) {
                    for (PanelStack p : panels) {
                        if (p.searchKey().equals(newKey)) { mergeTarget = p; break; }
                    }
                }
                if (mergeTarget != null) {
                    mergeTarget.setCount(count);
                    mergeTarget.timestamp = timestamp;
                    mergeTarget.craftable = craftable;
                    animId = mergeTarget.getId();
                } else {
                    PanelStack ps = new PanelStack(id, stack, timestamp, craftable);
                    synchronized (panels) {
                        idToIndex.put(id, panels.size());
                        panels.add(ps);
                    }
                    totalSlotCount++; // new item type added via delta
                    animId = id;
                }
            }
        }

        deltaBatch.add(id);
        deltaBatchDirty = true;
        markDirty();
        return animId;
    }

    // ── Panel removal ───────────────────────────────────────────────

    public void removePanel(UUID id) {
        Integer idx = idToIndex.remove(id);
        if (idx == null) return;
        synchronized (panels) {
            panels.remove((int) idx);
        }
        totalSlotCount = Math.max(0, totalSlotCount - 1);
        for (int i = idx; i < panels.size(); i++) {
            idToIndex.put(panels.get(i).getId(), i);
        }
    }

    // ── Internal helpers (mirrored from RSSidePanelClient) ──────────

    private static String keyOf(ItemStack stack) {
        if (stack == null || stack.getItem() == net.minecraft.world.item.Items.AIR) return "";
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String key = rl != null ? rl.toString() : "";
        String nbt = PanelStack.stableNbtString(stack.getTag());
        if (!nbt.isEmpty()) key += "|" + nbt;
        return key;
    }

    /** Remove all pending extractions whose stack matches {@code searchKey}. */
    public void clearPendingBySearchKey(String searchKey) {
        var it = pendingExtractions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (keyOf(e.getValue().previousStack).equals(searchKey)) {
                it.remove();
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /** Reset all state (e.g. on world unload). */
    public void clear() {
        synchronized (panels) {
            panels.clear();
            idToIndex.clear();
        }
        displayList.clear();
        displayDirty = true;
        totalSlotCount = 0;
        slotAnims.clear();
        deltaBatch.clear();
        deltaBatchDirty = false;
        pendingExtractions.clear();
    }
}
