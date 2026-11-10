package com.huanghuang.rsintegration.crafting.loadbalancer;

import java.util.HashMap;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.List;
import java.util.Map;

/** Tracks single-operation leases for a dynamic machine worker pool. */
public final class OperationQueue {

    private final int totalOperations;
    private final Map<Integer, List<Integer>> inFlight = new HashMap<>();
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
        List<Integer> claimed = claimBatch(workerId, 1);
        return claimed.isEmpty() ? -1 : claimed.get(0);
    }

    /** Claim up to {@code maxOperations} consecutive operations for one worker start. */
    public List<Integer> claimBatch(int workerId, int maxOperations) {
        if (dispatchStopped || inFlight.containsKey(workerId) || nextOperation >= totalOperations
                || maxOperations <= 0) return List.of();
        int count = Math.min(maxOperations, totalOperations - nextOperation);
        List<Integer> claimed = Collections.unmodifiableList(new OperationRange(nextOperation, count));
        nextOperation += count;
        inFlight.put(workerId, claimed);
        return claimed;
    }

    /** Complete the worker's current operation and return its operation id. */
    public int complete(int workerId) {
        List<Integer> completed = completeBatch(workerId);
        return completed.get(0);
    }

    public List<Integer> completeBatch(int workerId) {
        List<Integer> completed = removeInFlight(workerId);
        completedOperations += completed.size();
        return completed;
    }

    /**
     * Remove an operation that can no longer be observed safely. Its reservation
     * remains unsettled so the owner can clean the machine and refund it later.
     */
    public int abandon(int workerId) {
        return abandonBatch(workerId).get(0);
    }

    public List<Integer> abandonBatch(int workerId) {
        return removeInFlight(workerId);
    }

    private List<Integer> removeInFlight(int workerId) {
        List<Integer> operations = inFlight.remove(workerId);
        if (operations == null) {
            throw new IllegalStateException("worker has no in-flight operation: " + workerId);
        }
        return operations;
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
        return inFlight.values().stream().mapToInt(List::size).sum();
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

    /** 连续编号只保存起点和长度，不随批次数分配装箱整数。 */
    private static final class OperationRange extends AbstractList<Integer> implements RandomAccess {
        private final int first;
        private final int count;

        private OperationRange(int first, int count) {
            this.first = first;
            this.count = count;
        }

        @Override public Integer get(int index) {
            return first + Objects.checkIndex(index, count);
        }

        @Override public int size() { return count; }

        @Override public List<Integer> subList(int from, int to) {
            Objects.checkFromToIndex(from, to, count);
            return Collections.unmodifiableList(new OperationRange(first + from, to - from));
        }

        @Override public String toString() {
            return "[" + first + ".." + ((long) first + count) + ")";
        }
    }
}
