package com.huanghuang.rsintegration.feature.sidepanel;

import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 所有入口均由客户端主线程调用。库存与预测以稳定的槽位 ID 为准。 */
public final class PanelDataModel {
    private final Map<UUID, PanelStack> entries = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<Long, UUID> operations = new HashMap<>();
    private final Map<UUID, SlotAnim> animations = new HashMap<>();
    private boolean networkAvailable;
    private String networkName = "";
    private int totalSlotCount;
    private long revision;

    private record Pending(PanelStack before, long started, boolean requestedSync) {}
    public record SlotAnim(long started, int delta) {
        public static final int DURATION_MS = 400;
    }
    record Change(UUID target, int delta) {}

    List<PanelStack> entries() { return List.copyOf(entries.values()); }
    PanelStack getById(UUID id) { return entries.get(id); }
    long revision() { return revision; }
    boolean networkAvailable() { return networkAvailable; }
    String networkName() { return networkName; }
    int getTotalSlotCount() { return totalSlotCount; }
    SlotAnim animation(UUID id) { return animations.get(id); }

    void fullSync(List<PanelStack> incoming, int slots, boolean available, String name) {
        networkAvailable = available;
        networkName = name;
        if (SidePanelSyncPolicy.shouldRetainCurrentEntries(entries.size(), incoming.size(), available)) return;
        entries.clear();
        for (PanelStack entry : incoming) entries.put(entry.getId(), entry);
        totalSlotCount = slots;
        pending.clear();
        operations.clear();
        animations.clear();
        revision++;
    }

    void predictExtraction(long operation, UUID id, int quantity, long now) {
        if (deduct(id, quantity, now)) operations.put(operation, id);
    }

    void predictDistribution(long operation, Collection<UUID> ids, long now) {
        for (UUID id : ids) {
            if (deduct(id, 1, now)) operations.putIfAbsent(operation, id);
        }
    }

    private boolean deduct(UUID id, int quantity, long now) {
        PanelStack entry = entries.get(id);
        if (entry == null || entry.getCount() == 0 || quantity <= 0) return false;
        // 连续预测仍以第一次扣减前的库存为恢复依据。
        Pending previous = pending.get(id);
        pending.put(id, new Pending(previous == null ? entry : previous.before, now, false));
        change(entry, Math.max(0, entry.getCount() - quantity), now, entry.craftable, now);
        return true;
    }

    void predictInsert(ItemStack item, int quantity, long now) {
        if (quantity <= 0 || item.isEmpty()) return;
        PanelStack entry = findByKey(PanelStack.searchKeyOf(item));
        if (entry != null) change(entry, entry.getCount() + quantity, now, entry.craftable, now);
    }

    // 成功和失败回执都只结束操作跟踪；库存由 delta、全量同步或超时决定。
    UUID acknowledge(long operation) { return operations.remove(operation); }

    Change applyDelta(UUID id, ItemStack stack, long timestamp, boolean craftable, int limit, long now) {
        if (id == null || stack == null) return null;
        int count = stack.getCount();
        PanelStack target = entries.get(id);
        if (target == null && !stack.isEmpty()) target = findByKey(PanelStack.searchKeyOf(stack));
        if (target == null) {
            if (count <= 0 || entries.size() >= limit) return null;
            entries.put(id, new PanelStack(id, stack, timestamp, craftable));
            totalSlotCount++;
            return changed(id, count, now);
        }
        UUID targetId = target.getId();
        forgetPrediction(targetId);
        if (count > 0) return change(target, count, timestamp, craftable, now);
        entries.remove(targetId);
        totalSlotCount = Math.max(0, totalSlotCount - 1);
        return changed(targetId, -target.getCount(), now);
    }

    private PanelStack findByKey(String key) {
        for (PanelStack entry : entries.values()) {
            if (entry.searchKey().equals(key)) return entry;
        }
        return null;
    }

    private void forgetPrediction(UUID id) {
        pending.remove(id);
        operations.values().removeIf(id::equals);
    }

    private Change change(PanelStack before, int count, long timestamp, boolean craftable, long now) {
        entries.put(before.getId(), before.withQuantity(count, timestamp, craftable));
        return changed(before.getId(), count - before.getCount(), now);
    }

    private Change changed(UUID id, int delta, long now) {
        if (delta != 0) animations.put(id, new SlotAnim(now, delta));
        revision++;
        return new Change(id, delta);
    }

    /** 一次全量确认可覆盖本轮所有超时条目，真正的恢复留到下次检查。 */
    boolean tick(int tick, long now, int timeout) {
        if (tick % 10 == 0) animations.values().removeIf(a -> now - a.started > SlotAnim.DURATION_MS);
        if (tick % 20 != 0) return false;
        boolean requestSync = false;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var item = it.next();
            Pending p = item.getValue();
            if (now - p.started <= timeout) continue;
            if (!p.requestedSync) {
                item.setValue(new Pending(p.before, p.started, true));
                requestSync = true;
            } else {
                UUID id = item.getKey();
                PanelStack current = entries.put(id, p.before);
                changed(id, p.before.getCount() - current.getCount(), now);
                operations.values().removeIf(id::equals);
                it.remove();
            }
        }
        return requestSync;
    }

    void clear() {
        entries.clear();
        fullSync(List.of(), 0, false, "");
    }
}
