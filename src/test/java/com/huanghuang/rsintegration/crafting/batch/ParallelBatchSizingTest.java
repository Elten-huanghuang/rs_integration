package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParallelBatchSizingTest {

    @Test
    void physicalBatchIsBoundedByRequestAndCapacity() {
        assertEquals(8, ParallelBatchSizing.boundedBatch(8, 8));
        assertEquals(8, ParallelBatchSizing.boundedBatch(64, 8));
        assertEquals(3, ParallelBatchSizing.boundedBatch(3, 8));
        assertEquals(1, ParallelBatchSizing.boundedBatch(0, 8));
        assertEquals(1, ParallelBatchSizing.boundedBatch(8, 0));
    }

    @Test
    void operationsAreEvenlySharedWithoutExceedingPhysicalCapacity() {
        assertEquals(6, ParallelBatchSizing.boundedEvenShare(17, 3, 8));
        assertEquals(8, ParallelBatchSizing.boundedEvenShare(17, 1, 8));
        assertEquals(1, ParallelBatchSizing.boundedEvenShare(2, 3, 8));
        assertEquals(1, ParallelBatchSizing.boundedEvenShare(17, 0, 8));
        assertEquals(1, ParallelBatchSizing.boundedEvenShare(17, 3, 0));
        assertEquals(Integer.MAX_VALUE,
                ParallelBatchSizing.boundedEvenShare(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }

    @Test
    void multipliedCountsSaturateInsteadOfOverflowing() {
        assertEquals(24, ParallelBatchSizing.multipliedCount(8, 3));
        assertEquals(0, ParallelBatchSizing.multipliedCount(8, 0));
        assertEquals(Integer.MAX_VALUE,
                ParallelBatchSizing.multipliedCount(Integer.MAX_VALUE, 2));
    }
}
