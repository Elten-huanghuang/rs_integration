package com.huanghuang.rsintegration.crafting.batch;

/** Shared arithmetic for assigning bounded physical batches to graph workers. */
public final class ParallelBatchSizing {
    private ParallelBatchSizing() {}

    public static int boundedBatch(int requestedOperations, int capacity) {
        if (requestedOperations <= 0 || capacity <= 0) return 1;
        return Math.max(1, Math.min(requestedOperations, capacity));
    }

    public static int boundedEvenShare(int totalOperations, int workerCount, int capacity) {
        if (totalOperations <= 0 || workerCount <= 0 || capacity <= 0) return 1;
        int evenShare = (int) (((long) totalOperations + workerCount - 1L) / workerCount);
        return Math.max(1, Math.min(capacity, evenShare));
    }

    public static int multipliedCount(int count, int executions) {
        if (count <= 0 || executions <= 0) return 0;
        long multiplied = (long) count * executions;
        return (int) Math.min(Integer.MAX_VALUE, multiplied);
    }
}
