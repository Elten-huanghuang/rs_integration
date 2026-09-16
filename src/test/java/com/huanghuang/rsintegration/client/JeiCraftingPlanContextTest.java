package com.huanghuang.rsintegration.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeiCraftingPlanContextTest {
    @Test
    void incrementalStockReducesAndEventuallyClearsThePlanShortage() {
        var demand = new JeiCraftingPlanContext.Demand(2, 0, 2, false);

        assertEquals(2, demand.remainingMissing(0));
        assertEquals(1, demand.remainingMissing(1));
        assertEquals(0, demand.remainingMissing(2));
        assertEquals(0, demand.remainingMissing(20));
    }

    @Test
    void extractedPlanTimeStockIncreasesTheRemainingShortage() {
        var demand = new JeiCraftingPlanContext.Demand(3, 1, 2, false);

        assertEquals(3, demand.remainingMissing(0));
    }

    @Test
    void craftableIntermediateDoesNotBecomeAShortageWhenStockChanges() {
        var demand = new JeiCraftingPlanContext.Demand(2, 0, 0, false);

        assertEquals(0, demand.remainingMissing(0));
    }
}
