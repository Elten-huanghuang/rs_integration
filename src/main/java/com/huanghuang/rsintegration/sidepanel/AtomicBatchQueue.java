package com.huanghuang.rsintegration.sidepanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Per-key queues whose producers and drains are serialized by ConcurrentHashMap. */
final class AtomicBatchQueue<K, V> {
    private final ConcurrentHashMap<K, List<V>> queues = new ConcurrentHashMap<>();

    void add(K key, V value) {
        queues.compute(key, (ignored, queue) -> {
            List<V> target = queue == null ? new ArrayList<>() : queue;
            target.add(value);
            return target;
        });
    }

    List<V> drain(K key) {
        AtomicReference<List<V>> drained = new AtomicReference<>();
        queues.computeIfPresent(key, (ignored, queue) -> {
            drained.set(queue);
            return null;
        });
        List<V> result = drained.get();
        return result == null ? Collections.emptyList() : result;
    }

    Set<K> keysSnapshot() {
        return Set.copyOf(queues.keySet());
    }

    void clear(K key) {
        queues.remove(key);
    }

    void clear() {
        queues.clear();
    }
}
