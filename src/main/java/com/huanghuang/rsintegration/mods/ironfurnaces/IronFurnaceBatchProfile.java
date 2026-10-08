package com.huanghuang.rsintegration.mods.ironfurnaces;

/**
 * Shared physical batching rules for Iron Furnaces single lanes and factories.
 *
 * <p>The profile deliberately separates input capacity from processing-cycle
 * semantics. Ordinary furnaces preload a stack and consume one item per cycle;
 * rainbow furnaces consume the whole physical batch in one cycle.</p>
 */
public record IronFurnaceBatchProfile(
        boolean factory,
        boolean rainbow,
        int laneCount,
        int laneCapacity) {

    public IronFurnaceBatchProfile {
        laneCount = Math.max(1, laneCount);
        laneCapacity = Math.max(1, laneCapacity);
    }

    public static IronFurnaceBatchProfile of(boolean factory, boolean rainbow,
                                             int laneCapacity) {
        return of(factory, rainbow, laneCapacity, 2);
    }

    public static IronFurnaceBatchProfile of(boolean factory, boolean rainbow,
                                             int laneCapacity, int factoryTier) {
        int lanes = 0;
        for (boolean enabled : IronFactoryLanePlan.enabledLanes(factoryTier)) {
            if (enabled) lanes++;
        }
        return new IronFurnaceBatchProfile(factory, rainbow, factory ? lanes : 1, laneCapacity);
    }

    /** Maximum number of logical operations placed in one physical dispatch. */
    public int physicalCapacity() {
        return laneCount * laneCapacity;
    }

    /** Number of logical operations to place in the next physical dispatch. */
    public int plannedBatchSize(int remainingOperations) {
        return Math.min(physicalCapacity(), Math.max(0, remainingOperations));
    }

    /**
     * Processing cycles needed by this physical dispatch.
     * Factory energy is not fuel-counted by the delegate, but its cycle count
     * remains useful for diagnostics and future energy budgeting.
     */
    public int processingCycles(int operations) {
        int requested = Math.max(0, operations);
        if (requested == 0) return 0;
        if (rainbow) return ceilDiv(requested, physicalCapacity());
        return factory ? ceilDiv(requested, laneCount) : requested;
    }

    public boolean preloadsInput() {
        return laneCapacity > 1;
    }

    private static int ceilDiv(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
