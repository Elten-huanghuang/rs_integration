package com.huanghuang.rsintegration.sidepanel.data;

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
    private final List<BindingInfo> allBindings = new ArrayList<>();

    private BindingCache() {}

    public static BindingCache getInstance() { return INSTANCE; }

    // ── Full-sync update ────────────────────────────────────────────

    /** Replace all bindings with a fresh list from the server. */
    public void updateBindings(List<BindingInfo> bindings) {
        byItemKey.clear();
        allBindings.clear();
        if (bindings == null) return;
        for (var info : bindings) {
            if (info != null && info.itemKey() != null) {
                byItemKey.computeIfAbsent(info.itemKey(), k -> new ArrayList<>()).add(info);
                allBindings.add(info);
            }
        }
    }

    // ── Queries ─────────────────────────────────────────────────────

    /** Return the first binding for this itemKey, or null. */
    public BindingInfo getBinding(String itemKey) {
        List<BindingInfo> list = byItemKey.get(itemKey);
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    /** Return all bindings for this itemKey (immutable). */
    public List<BindingInfo> getBindings(String itemKey) {
        List<BindingInfo> list = byItemKey.get(itemKey);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public int getMachineCount() {
        return allBindings.size();
    }

    public Collection<BindingInfo> getAll() {
        return Collections.unmodifiableList(allBindings);
    }

    /** Whether any bindings exist at all. */
    public boolean isEmpty() {
        return allBindings.isEmpty();
    }

    // ── GUI capability tracking ─────────────────────────────────────

    /** Whether any binding exists for this itemKey. */
    public boolean hasGui(String itemKey) {
        return byItemKey.containsKey(itemKey);
    }

    /** Check if the binding exists (regardless of GUI status). */
    public boolean isBound(String itemKey) {
        return byItemKey.containsKey(itemKey);
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /** Reset all state (e.g. on world unload). */
    public void clear() {
        byItemKey.clear();
        allBindings.clear();
    }
}
