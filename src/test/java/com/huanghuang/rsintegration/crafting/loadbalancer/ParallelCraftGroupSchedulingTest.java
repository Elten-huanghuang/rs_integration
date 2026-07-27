package com.huanghuang.rsintegration.crafting.loadbalancer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftGroupSchedulingTest {
    @Test
    void exclusiveDelegateCanDrainOperationsThroughOneSerialWorker() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(true, 1));
    }

    @Test
    void exclusiveDelegateCannotJoinMultiWorkerGroup() {
        assertFalse(ParallelCraftGroup.operationGroupAcceptsChild(true, 2));
    }

    @Test
    void concurrencySafeDelegateCanJoinMultiWorkerGroup() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(false, 2));
    }
}
