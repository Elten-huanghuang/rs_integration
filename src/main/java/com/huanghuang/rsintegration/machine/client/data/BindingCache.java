package com.huanghuang.rsintegration.machine.client.data;

import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineBindingInfoFactory;
import com.huanghuang.rsintegration.machine.client.MachineEntryPolicy;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Client-side cache of bound machines. Updated by full-sync packets.
 * Tracks which inventory items are bound to machines and whether those
 * machines have a GUI that can be opened.
 * <p>
 * Multiple bindings per itemKey are supported — a single wireless terminal
 * can bind several machines (e.g., smithing table + stonecutter).
 */
public final class BindingCache {

    private static final BindingCache INSTANCE = new BindingCache();

    /** itemKey (registry name string) → list of bound machines */
    private final Map<String, List<BindingInfo>> byItemKey = new LinkedHashMap<>();

    /** All bound machines in insertion order. */
    private List<BindingInfo> allBindings = List.of();
    private final Map<UUID, List<BindingInfo>> byBookId = new LinkedHashMap<>();
    private final Map<UUID, List<BindingStorage.BindingEntry>> entriesByBookId =
            new LinkedHashMap<>();

    private BindingCache() {}

    public static BindingCache getInstance() { return INSTANCE; }

    // ── Full-sync update ────────────────────────────────────────────

    /** Replace all bindings with a fresh list from the server. */
    public void updateBindings(List<BindingInfo> bindings) {
        byItemKey.clear();
        byBookId.clear();
        entriesByBookId.clear();
        if (bindings == null) {
            allBindings = List.of();
            return;
        }
        List<BindingInfo> updated = new ArrayList<>(bindings.size());
        for (var info : bindings) {
            if (info != null && info.itemKey() != null) {
                byItemKey.computeIfAbsent(info.itemKey(), k -> new ArrayList<>()).add(info);
                updated.add(info);
                if (info.bindingBookId() != null) {
                    byBookId.computeIfAbsent(info.bindingBookId(), k -> new ArrayList<>())
                            .add(info);
                    entriesByBookId.computeIfAbsent(
                            info.bindingBookId(), k -> new ArrayList<>())
                            .add(MachineBindingInfoFactory.toEntry(info));
                }
            }
        }
        byItemKey.replaceAll((ignored, values) -> List.copyOf(values));
        byBookId.replaceAll((ignored, values) -> List.copyOf(values));
        entriesByBookId.replaceAll((ignored, values) -> List.copyOf(values));
        allBindings = List.copyOf(updated);
    }

    // ── Queries ─────────────────────────────────────────────────────

    /** Return the first binding for this itemKey, or null. */
    public BindingInfo getBinding(String itemKey) {
        List<BindingInfo> list = byItemKey.get(itemKey);
        if (list == null) return null;
        for (BindingInfo info : list) {
            if (MachineEntryPolicy.isSelectable(info)) return info;
        }
        return null;
    }

    /** Return all bindings for this itemKey (immutable). */
    public List<BindingInfo> getBindings(String itemKey) {
        List<BindingInfo> list = byItemKey.get(itemKey);
        return list != null ? list : List.of();
    }

    public int getMachineCount() {
        return allBindings.size();
    }

    public List<BindingInfo> getAll() {
        return allBindings;
    }

    public List<BindingInfo> getByBookId(@Nullable UUID bookId) {
        if (bookId == null) return List.of();
        List<BindingInfo> bindings = byBookId.get(bookId);
        return bindings == null ? List.of() : bindings;
    }

    public List<BindingStorage.BindingEntry> getEntriesByBookId(@Nullable UUID bookId) {
        if (bookId == null) return List.of();
        List<BindingStorage.BindingEntry> entries = entriesByBookId.get(bookId);
        return entries == null ? List.of() : entries;
    }

    /** Whether any bindings exist at all. */
    public boolean isEmpty() {
        return allBindings.isEmpty();
    }

    // ── GUI capability tracking ─────────────────────────────────────

    /** Whether any binding exists for this itemKey. */
    public boolean hasGui(String itemKey) {
        return getBinding(itemKey) != null;
    }

    /** Check if the binding exists (regardless of GUI status). */
    public boolean isBound(String itemKey) {
        return byItemKey.containsKey(itemKey);
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /** Reset all state (e.g. on world unload). */
    public void clear() {
        byItemKey.clear();
        allBindings = List.of();
        byBookId.clear();
        entriesByBookId.clear();
    }
}
