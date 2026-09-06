package com.huanghuang.rsintegration.crafting.planning;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared lifecycle and budget for every phase of one background planning request. */
public final class PlanningSession {
    private final long requestGeneration;
    private final long startedNanos;
    private final long deadlineNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Phase phase = Phase.QUEUED;

    public PlanningSession(long requestGeneration, long timeoutMs) {
        this(requestGeneration, System.nanoTime(), timeoutMs);
    }

    PlanningSession(long requestGeneration, long startedNanos, long timeoutMs) {
        this.requestGeneration = requestGeneration;
        this.startedNanos = startedNanos;
        long budget = Math.max(1L, timeoutMs) * 1_000_000L;
        long deadline = startedNanos + budget;
        this.deadlineNanos = deadline < startedNanos ? Long.MAX_VALUE : deadline;
    }

    public long requestGeneration() { return requestGeneration; }
    public long startedNanos() { return startedNanos; }
    public long deadlineNanos() { return deadlineNanos; }
    public Phase phase() { return phase; }

    public void phase(Phase phase) {
        this.phase = phase == null ? Phase.UNKNOWN : phase;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public boolean expired() {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    public void checkCancelled() {
        if (cancelled()) throw new CancellationException("Planning session cancelled");
    }

    public void checkBudget() {
        checkCancelled();
        if (expired()) throw new PlanningBudgetExceededException(phase);
    }

    public enum Phase {
        QUEUED,
        PREPARATION,
        DEPENDENCY_PROJECTION,
        SMITHING_STATE_BINDING,
        INVENTORY_BINDING,
        DEMAND_TREE,
        RECURSIVE_SEARCH,
        RESULT_HANDOFF,
        UNKNOWN
    }

    public static final class PlanningBudgetExceededException extends RuntimeException {
        private final Phase phase;

        public PlanningBudgetExceededException(Phase phase) {
            super("Planning budget exhausted during " + phase);
            this.phase = phase;
        }

        public Phase phase() { return phase; }
    }
}
