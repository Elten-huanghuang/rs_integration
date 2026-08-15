package com.huanghuang.rsintegration.mods.botania;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManaPoolBatchDelegateTest {

    @Test
    void graphBatchIsAnnouncedBeforeCaptureIsArmed() {
        assertEquals(128, ManaPoolBatchDelegate.graphBatchSize(128));
        assertEquals(1024, ManaPoolBatchDelegate.graphBatchSize(4096));
    }

    @Test
    void batchesUpToRequestAndPhysicalLimit() {
        assertEquals(128, ManaPoolBatchDelegate.physicalBatchSize(128, 1_000_000, 500));
        assertEquals(1024, ManaPoolBatchDelegate.physicalBatchSize(4096, 1_000_000, 1));
    }

    @Test
    void batchIsCappedByCurrentlyAffordableMana() {
        assertEquals(20, ManaPoolBatchDelegate.physicalBatchSize(128, 10_000, 500));
        assertEquals(1, ManaPoolBatchDelegate.physicalBatchSize(128, 499, 500));
    }

    @Test
    void freeRecipesCanUseTheFullPhysicalBatch() {
        assertEquals(128, ManaPoolBatchDelegate.physicalBatchSize(128, 0, 0));
        assertEquals(0, ManaPoolBatchDelegate.physicalBatchSize(0, 1_000_000, 500));
    }

    @Test
    void parallelWorkersReceiveEvenBatchesCappedAt128() {
        assertEquals(32, ManaPoolBatchDelegate.parallelWorkerBatchSize(128, 4));
        assertEquals(128, ManaPoolBatchDelegate.parallelWorkerBatchSize(1024, 4));
        assertEquals(128, ManaPoolBatchDelegate.parallelWorkerBatchSize(4096, 8));
    }

    @Test
    void adjacentPoolsCanOwnDistinctCaptureBoxes() {
        var first = ManaPoolBatchDelegate.captureRegion(new BlockPos(0, 64, 0));
        var second = ManaPoolBatchDelegate.captureRegion(new BlockPos(1, 64, 0));
        assertFalse(first.intersects(second));
    }
}
