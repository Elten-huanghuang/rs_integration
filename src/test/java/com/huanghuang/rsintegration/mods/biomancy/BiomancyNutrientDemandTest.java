package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 生物炼金台每次执行都会扣营养，一批执行必须按总需求备料，否则中途营养耗尽会让结果槽变空。
 */
class BiomancyNutrientDemandTest extends BootstrapTest {
    @Test
    void batchDemandMultipliesRecipeCostByExecutions() {
        assertEquals(1000, BiomancyBatchDelegate.nutrientDemand(100, 10));
        assertEquals(10, BiomancyBatchDelegate.nutrientDemand(1, 10));
    }

    @Test
    void singleExecutionKeepsRecipeCost() {
        assertEquals(100, BiomancyBatchDelegate.nutrientDemand(100, 1));
    }

    @Test
    void zeroExecutionsIsTreatedAsOne() {
        assertEquals(100, BiomancyBatchDelegate.nutrientDemand(100, 0));
    }

    @Test
    void unknownCostStaysNegative() {
        assertEquals(-1, BiomancyBatchDelegate.nutrientDemand(-1, 10));
    }

    @Test
    void oversizedDemandClampsInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, BiomancyBatchDelegate.nutrientDemand(Integer.MAX_VALUE, 2));
    }
}
