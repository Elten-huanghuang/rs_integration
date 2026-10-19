package com.huanghuang.rsintegration.mods.farmingforblockheads;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketBatchDelegateTest extends BootstrapTest {

    @Test
    void flatBatchUsesTheRemainingOperationsByDefault() {
        MarketBatchDelegate delegate = new MarketBatchDelegate();

        assertEquals(1000, delegate.prepareFlatBatch(1000));
        assertEquals(12, delegate.prepareFlatBatch(12));
        assertEquals(0, delegate.prepareFlatBatch(0));
    }

    @Test
    void graphBatchCanRepresentTheWholeNodeInOneWorker() {
        MarketBatchDelegate delegate = new MarketBatchDelegate();

        delegate.prepareGraphBatch(1000);
        assertEquals(1000, delegate.preferredParallelBatchSize(1000, 1));
        assertEquals(Integer.MAX_VALUE, delegate.flatBatchOperationLimit(32));
        assertTrue(delegate.expandsFlatBatchOperationLimit());
    }
}
