package com.huanghuang.rsintegration.crafting.loadbalancer;

import java.util.HashMap;
import java.util.Map;

/** Tracks single-operation leases for a dynamic machine worker pool. */
public final class OperationQueue {

    private final int totalOperations;
    private final Map<Integer, Integer> inFlight = new HashMap<>();
    private int nextOperation;
    private int completedOperations;
    private boolean dispatchStopped;

    public OperationQueue(int totalOperations) {
        if (totalOperations <= 0) {
            throw new IllegalArgumentException("totalOperations must be positive");
        }
        this.totalOperations = totalOperations;
    }

    /** Return the next operation id without consuming it. */
    public int nextQueuedOperation() {
        return dispatchStopped || nextOperation >= totalOperations ? -1 : nextOperation;
    }

    /** Claim the next operation for a currently idle worker. */
    public int claim(int workerId) {
        if (dispatchStopped || inFlight.containsKey(workerId) || nextOperation >= totalOperations) {
            return -1;
        }
        int operation = nextOperation++;
        inFlight.put(workerId, operation);
        return operation;
    }

    /** Complete the worker's current operation and return its operation id. */
    public int complete(int workerId) {
        Integer operation = inFlight.remove(workerId);
        if (operation == null) {
            throw new IllegalStateException("worker has no in-flight operation: " + workerId);
        }
        completedOperations++;
        return operation;
    }

    /**
     * Remove an operation that can no longer be observed safely. Its reservation
     * remains unsettled so the owner can clean the machine and refund it later.
     */
    public int abandon(int workerId) {
        Integer operation = inFlight.remove(workerId);
        if (operation == null) {
            throw new IllegalStateException("worker has no in-flight operation: " + workerId);
        }
        return operation;
    }

    /** Stop assigning queued work while allowing existing leases to settle. */
    public void stopDispatch() {
        dispatchStopped = true;
    }

    public int totalOperations() {
        return totalOperations;
    }

    public int queuedOperations() {
        return totalOperations - nextOperation;
    }

    public int runningOperations() {
        return inFlight.size();
    }

    public int completedOperations() {
        return completedOperations;
    }

    public boolean isComplete() {
        return !dispatchStopped && completedOperations == totalOperations && inFlight.isEmpty();
    }

    public boolean isDrained() {
        return dispatchStopped && inFlight.isEmpty();
    }

    public boolean isDispatchStopped() {
        return dispatchStopped;
    }
}
