package com.huanghuang.rsintegration.crafting.batch;

import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the single terminal response allowed for one planning request. */
final class PlanResultGate {
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final long generation;
    private final long createdNanos;

    PlanResultGate() {
        this(0L, System.nanoTime());
    }

    PlanResultGate(long generation, long createdNanos) {
        this.generation = generation;
        this.createdNanos = createdNanos;
    }

    boolean tryEnterTerminal() {
        return terminal.compareAndSet(false, true);
    }

    boolean tryEnterTerminal(long callbackGeneration) {
        if (generation != 0L && callbackGeneration != 0L && generation != callbackGeneration) {
            return false;
        }
        return tryEnterTerminal();
    }

    boolean expired(long nowNanos, long ttlNanos) {
        return ttlNanos > 0L && nowNanos - createdNanos >= ttlNanos;
    }

    void cancel() {
        terminal.set(true);
    }

    boolean isTerminal() {
        return terminal.get();
    }
}
