package com.huanghuang.rsintegration.network.binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In-memory binding index partitioned by owner and binding type.
 *
 * <p>The index deliberately knows nothing about RS or any other storage backend.
 * A backend supplies its own slot key and value while callers must present the
 * player owner for every read or mutation that can affect stored items.</p>
 */
public final class OwnerScopedBindingIndex<K, S, V> {
    private final Map<K, Map<UUID, Map<S, V>>> entries = new HashMap<>();

    public synchronized void put(K key, UUID ownerId, S slot, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(value, "value");
        entries.computeIfAbsent(key, ignored -> new HashMap<>())
                .computeIfAbsent(ownerId, ignored -> new HashMap<>())
                .put(slot, value);
    }

    public synchronized boolean remove(K key, UUID ownerId, S slot) {
        Map<UUID, Map<S, V>> owners = entries.get(key);
        if (owners == null) return false;
        Map<S, V> bindings = owners.get(ownerId);
        if (bindings == null || bindings.remove(slot) == null) return false;
        if (bindings.isEmpty()) owners.remove(ownerId);
        if (owners.isEmpty()) entries.remove(key);
        return true;
    }

    public synchronized void removeAll(K key) {
        entries.remove(key);
    }

    public synchronized V get(K key, UUID ownerId, S slot) {
        Map<UUID, Map<S, V>> owners = entries.get(key);
        if (owners == null) return null;
        Map<S, V> bindings = owners.get(ownerId);
        return bindings == null ? null : bindings.get(slot);
    }

    public synchronized List<V> valuesFor(K key, UUID ownerId) {
        Map<UUID, Map<S, V>> owners = entries.get(key);
        if (owners == null) return List.of();
        Map<S, V> bindings = owners.get(ownerId);
        return bindings == null ? List.of() : List.copyOf(bindings.values());
    }

    public synchronized List<V> allValues(K key) {
        Map<UUID, Map<S, V>> owners = entries.get(key);
        if (owners == null) return List.of();
        List<V> result = new ArrayList<>();
        for (Map<S, V> bindings : owners.values()) result.addAll(bindings.values());
        return List.copyOf(result);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
