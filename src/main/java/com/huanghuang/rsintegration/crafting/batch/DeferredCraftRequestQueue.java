package com.huanghuang.rsintegration.crafting.batch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Bounded server-thread queue that keeps only the newest preview per player. */
final class DeferredCraftRequestQueue<T> {
    record Entry<T>(UUID playerId, boolean preview, long generation, T payload, long queuedAtNanos) {
        Entry(UUID playerId, boolean preview, long generation, T payload) {
            this(playerId, preview, generation, payload, System.nanoTime());
        }
    }

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

    /** 无论目录是否就绪，都逐个结束到期请求，避免无限等待或以后意外执行。 */
    synchronized Entry<T> pollExpired(long nowNanos, long timeoutNanos) {
        var iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (nowNanos - entry.queuedAtNanos() >= timeoutNanos) {
                iterator.remove();
                return entry;
            }
        }
        return null;
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
