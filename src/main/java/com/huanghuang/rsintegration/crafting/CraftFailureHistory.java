package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Session-local failure history, separate from live HUD/status reconciliation. */
final class CraftFailureHistory {
    static final int MAX_ENTRIES = 8;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private final Set<UUID> seen = new HashSet<>();

    boolean record(CraftProgressSnapshot snapshot, ItemStack target) {
        return record(snapshot, target, CraftFailureContext.unknown("unknown"));
    }

    boolean record(CraftProgressSnapshot snapshot, ItemStack target, CraftFailureContext context) {
        if (snapshot.result() != CraftProgressSnapshot.Result.FAILED || !seen.add(snapshot.craftId())) return false;
        entries.put(snapshot.craftId(), new Entry(snapshot, target, context));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        return true;
    }

    List<CraftProgressSnapshot> snapshots() {
        return entries.values().stream().map(Entry::snapshot).toList();
    }

    ItemStack target(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? ItemStack.EMPTY : entry.target().copy();
    }

    void remove(UUID id) { entries.remove(id); }
    boolean seen(UUID id) { return seen.contains(id); }
    Entry get(UUID id) { return entries.get(id); }
    void clear() { entries.clear(); seen.clear(); }

    record Entry(CraftProgressSnapshot snapshot, ItemStack target, CraftFailureContext context) {
        Entry { target = target.copy(); }
        @Override public ItemStack target() { return target.copy(); }
    }
}
