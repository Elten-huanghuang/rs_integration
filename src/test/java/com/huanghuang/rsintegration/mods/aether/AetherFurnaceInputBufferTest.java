package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AetherFurnaceInputBufferTest extends BootstrapTest {
    @Test
    void capacityIsBoundedByBothInputAndOutputSlots() {
        assertEquals(32, AetherFurnaceBatchDelegate.bufferedOperationCapacity(
                64, 2, 64, 1, 64));
        assertEquals(16, AetherFurnaceBatchDelegate.bufferedOperationCapacity(
                64, 1, 64, 4, 64));
    }

    @Test
    void configuredLimitRemainsAnUpperBound() {
        assertEquals(8, AetherFurnaceBatchDelegate.bufferedOperationCapacity(
                8, 1, 64, 1, 64));
    }

    @Test
    void invalidPerOperationCountsDisableBuffering() {
        assertEquals(0, AetherFurnaceBatchDelegate.bufferedOperationCapacity(
                64, 0, 64, 1, 64));
    }
}
