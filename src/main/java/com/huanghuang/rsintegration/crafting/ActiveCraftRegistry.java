package com.huanghuang.rsintegration.crafting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Insertion-ordered active craft registry keyed by stable craft identity. */
final class ActiveCraftRegistry<K, V> {

    private final Map<K, V> crafts = new LinkedHashMap<>();

    synchronized boolean add(K craftId, V craft) {
        Objects.requireNonNull(craftId, "craftId");
        Objects.requireNonNull(craft, "craft");
        return crafts.putIfAbsent(craftId, craft) == null;
    }

    synchronized V get(K craftId) {
        return crafts.get(craftId);
    }

    synchronized boolean remove(K craftId) {
        return crafts.remove(craftId) != null;
    }

    synchronized List<V> snapshot() {
        return List.copyOf(crafts.values());
    }

    synchronized int size() {
        return crafts.size();
    }
}
