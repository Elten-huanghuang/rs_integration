package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

/**
 * Shared bounded batching contract for machines that complete every recipe
 * execution without a native processing tick.
 */
public abstract class InstantBatchDelegate extends AbstractBatchDelegate {
    protected static final int MAX_INSTANT_BATCH = 64;

    private int preparedExecutions = 1;

    /** Machines with conditional modes override this instead of duplicating all three batch hooks. */
    protected boolean supportsInstantBatch() {
        return true;
    }

    protected int instantBatchCapacity() {
        return MAX_INSTANT_BATCH;
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInstantBatch()) return remainingOperations > 0 ? 1 : 0;
        if (remainingOperations <= 0) {
            preparedExecutions = 1;
            return 0;
        }
        preparedExecutions = Math.min(
                Math.max(1, instantBatchCapacity()), remainingOperations);
        return preparedExecutions;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        preparedExecutions = supportsInstantBatch()
                ? Math.max(1, Math.min(Math.max(1, instantBatchCapacity()), executions))
                : 1;
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return supportsInstantBatch()
                ? ParallelBatchSizing.boundedEvenShare(
                totalOperations, workerCount, Math.max(1, instantBatchCapacity()))
                : 1;
    }

    protected final int preparedExecutions() {
        return Math.max(1, preparedExecutions);
    }

    protected final void resetInstantBatch() {
        preparedExecutions = 1;
    }

    protected final ItemStack scaledCopy(ItemStack source, int executions) {
        if (source == null || source.isEmpty()) return ItemStack.EMPTY;
        ItemStack scaled = source.copy();
        scaled.setCount(scaledCount(source.getCount(), executions));
        return scaled;
    }

    protected final int scaledCount(int count, int executions) {
        return ParallelBatchSizing.multipliedCount(count, Math.max(1, executions));
    }
}
