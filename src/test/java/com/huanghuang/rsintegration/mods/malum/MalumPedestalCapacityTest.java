package com.huanghuang.rsintegration.mods.malum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MalumPedestalCapacityTest {
    @Test
    void nonStackableIngredientsUseOnePedestalPerItem() {
        assertEquals(2, MalumBatchDelegate.pedestalSlotsForCount(2, 1));
    }

    @Test
    void stackableIngredientsAreSplitOnlyAtTheirRealLimit() {
        assertEquals(1, MalumBatchDelegate.pedestalSlotsForCount(64, 64));
        assertEquals(2, MalumBatchDelegate.pedestalSlotsForCount(65, 64));
    }

    @Test
    void emptyRequirementsUseNoPedestals() {
        assertEquals(0, MalumBatchDelegate.pedestalSlotsForCount(0, 1));
    }
}
