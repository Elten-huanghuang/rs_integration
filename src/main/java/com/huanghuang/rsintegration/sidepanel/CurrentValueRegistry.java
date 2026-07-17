package com.huanghuang.rsintegration.sidepanel;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks one current value per key and supports identity-guarded removal. */
final class CurrentValueRegistry<K, V> {
    private final Map<K, V> values = new ConcurrentHashMap<>();

    @Nullable
    V put(K key, V value) {
        return values.put(key, value);
    }

    @Nullable
    V get(K key) {
        return values.get(key);
    }

    @Nullable
    V remove(K key) {
        return values.remove(key);
    }

    boolean removeCurrent(K key, V expected) {
        return values.remove(key, expected);
    }

    boolean contains(K key) {
        return values.containsKey(key);
    }

    Collection<V> snapshotValues() {
        return java.util.List.copyOf(values.values());
    }

    void clear() {
        values.clear();
    }
}
