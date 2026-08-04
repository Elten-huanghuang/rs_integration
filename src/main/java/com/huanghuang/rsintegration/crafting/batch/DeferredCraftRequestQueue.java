package com.huanghuang.rsintegration.crafting.batch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Bounded server-thread queue that keeps only the newest preview per player. */
final class DeferredCraftRequestQueue<T> {
    record Entry<T>(UUID playerId, boolean preview, long generation, T payload) {}

    private final int capacity;
    private final Deque<Entry<T>> entries = new ArrayDeque<>();

    DeferredCraftRequestQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean offer(Entry<T> request) {
        if (request.preview()) {
            entries.removeIf(existing -> existing.preview()
                    && existing.playerId().equals(request.playerId()));
        }
        if (entries.size() >= capacity) return false;
        entries.addLast(request);
        return true;
    }

    synchronized Entry<T> poll() {
        return entries.pollFirst();
    }

    synchronized void removePlayer(UUID playerId) {
        entries.removeIf(request -> request.playerId().equals(playerId));
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }
}
