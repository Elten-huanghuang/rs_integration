package com.huanghuang.rsintegration.sidepanel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Tick-bound queue that coalesces invalidations by native cache identity. */
final class IdentityInvalidationQueue<K, V> {
    private final Map<K, V> pending = new IdentityHashMap<>();

    synchronized void offer(K key, V value) {
        pending.putIfAbsent(key, value);
    }

    synchronized List<Map.Entry<K, V>> drain() {
        List<Map.Entry<K, V>> snapshot = new ArrayList<>(pending.size());
        pending.forEach((key, value) -> snapshot.add(Map.entry(key, value)));
        pending.clear();
        return snapshot;
    }

    synchronized void clear() {
        pending.clear();
    }
}
