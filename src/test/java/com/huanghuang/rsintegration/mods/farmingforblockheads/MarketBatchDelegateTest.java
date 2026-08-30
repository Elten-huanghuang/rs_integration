package com.huanghuang.rsintegration.mods.farmingforblockheads;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketBatchDelegateTest extends BootstrapTest {

    @Test
    void flatBatchIsBoundedPerTick() {
        MarketBatchDelegate delegate = new MarketBatchDelegate();

        assertEquals(MarketBatchDelegate.MAX_TRADES_PER_BATCH, delegate.prepareFlatBatch(1000));
        assertEquals(12, delegate.prepareFlatBatch(12));
        assertEquals(0, delegate.prepareFlatBatch(0));
    }

    @Test
    void graphBatchIsBoundedPerWorkerStart() {
        MarketBatchDelegate delegate = new MarketBatchDelegate();

        delegate.prepareGraphBatch(1000);
        assertEquals(MarketBatchDelegate.MAX_TRADES_PER_BATCH,
                delegate.preferredParallelBatchSize(1000, 1));
    }

    @Test
    void graphBatchScalesResultOnceForTheWholeTradeBatch() {
        MarketBatchDelegate delegate = new MarketBatchDelegate();
        delegate.prepareGraphBatch(1000);

        ItemStack result = MarketBatchDelegate.scaledResult(new ItemStack(Items.DIAMOND, 2), 1000);

        assertEquals(2000, result.getCount());
        assertTrue(result.is(Items.DIAMOND));
    }

    @Test
    void resultScalingSaturatesInsteadOfWrapping() {
        ItemStack result = MarketBatchDelegate.scaledResult(
                new ItemStack(Items.DIAMOND, Integer.MAX_VALUE), 2);

        assertEquals(Integer.MAX_VALUE, result.getCount());
    }
}
