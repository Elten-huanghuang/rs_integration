package com.huanghuang.rsintegration.crafting.planning;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Fair server-thread admission queue that retains only the latest typed preview per player. */
public final class TypedPreviewAdmissionQueue {
    public record Request(UUID playerId, long generation, long queuedNanos,
                          Runnable task, Runnable expired, Consumer<Throwable> failed) {
        public Request {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(expired, "expired");
            Objects.requireNonNull(failed, "failed");
        }
    }

    public enum OfferResult {
        QUEUED,
        REPLACED,
        FULL
    }

    private final int capacity;
    private final ArrayDeque<UUID> order = new ArrayDeque<>();
    private final Map<UUID, Request> latest = new HashMap<>();
    private Request admitted;

    public TypedPreviewAdmissionQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized OfferResult offer(Request request) {
        Request previous = latest.put(request.playerId(), request);
        if (previous != null) return OfferResult.REPLACED;
        if (latest.size() > capacity) {
            latest.remove(request.playerId());
            return OfferResult.FULL;
        }
        order.offer(request.playerId());
        return OfferResult.QUEUED;
    }

    public int run(int maximum, long nowNanos, long maxQueueNanos,
                   Predicate<Request> current) {
        int executed = 0;
        while (executed < Math.max(0, maximum)) {
            Request request = poll();
            if (request == null) break;
            if (!current.test(request)) continue;
            if (maxQueueNanos >= 0L && nowNanos - request.queuedNanos() > maxQueueNanos) {
                request.expired().run();
                continue;
            }
            synchronized (this) {
                admitted = request;
            }
            try {
                request.task().run();
            } catch (RuntimeException | LinkageError failure) {
                try {
                    request.failed().accept(failure);
                } catch (RuntimeException | LinkageError ignored) {
                    // A reporting failure must not escape into the server tick.
                }
            } finally {
                synchronized (this) {
                    admitted = null;
                }
            }
            executed++;
        }
        return executed;
    }

    public synchronized boolean isAdmitted(UUID playerId, long generation) {
        return admitted != null && admitted.playerId().equals(playerId)
                && admitted.generation() == generation;
    }

    public synchronized int size() {
        return latest.size();
    }

    public synchronized void remove(UUID playerId) {
        latest.remove(playerId);
    }

    public synchronized void clear() {
        order.clear();
        latest.clear();
        admitted = null;
    }

    private synchronized Request poll() {
        while (!order.isEmpty()) {
            UUID playerId = order.poll();
            Request request = latest.remove(playerId);
            if (request != null) return request;
        }
        return null;
    }
}
