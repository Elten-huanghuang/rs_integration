package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static com.huanghuang.rsintegration.crafting.plan.PlanQuantityControls.applyOffset;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingPlanQuantityTest {
    @Test
    void stackButtonSetsOneToSixtyFourThenAddsSixtyFour() {
        assertEquals(64L, applyOffset(1, 64));
        assertEquals(128L, applyOffset(64, 64));
        assertEquals(192L, applyOffset(128, 64));
        assertEquals(67L, applyOffset(3, 64));
        assertEquals(Integer.MAX_VALUE + 64L, applyOffset(Integer.MAX_VALUE, 64));
    }

    @Test
    void otherButtonsKeepTheirExistingBehavior() {
        assertEquals(1L, applyOffset(128, 0));
        assertEquals(2L, applyOffset(1, 1));
        assertEquals(11L, applyOffset(1, 10));
        assertEquals(59L, applyOffset(64, -5));
    }
}
