package com.huanghuang.rsintegration.compat.emi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmiCraftButtonPlacementTest {
    @Test
    void reservesTheFirstRightColumnWhenEmiHasNoButtons() {
        assertEquals(0, EmiCraftButtonPlacement.columnOffset(0));
        assertEquals(13, EmiCraftButtonPlacement.sideWidth(2, 2));
    }

    @Test
    void startsAfterExistingEmiButtonColumns() {
        assertEquals(14, EmiCraftButtonPlacement.columnOffset(13));
        assertEquals(28, EmiCraftButtonPlacement.columnOffset(27));
    }

    @Test
    void stacksCraftAndMachineButtonsInTheReservedSideColumn() {
        assertArrayEquals(new int[]{139, 17},
                EmiCraftButtonPlacement.position(0, 2, 2, 120, 26, 14));
        assertArrayEquals(new int[]{139, 3},
                EmiCraftButtonPlacement.position(1, 2, 2, 120, 26, 14));
    }

    @Test
    void usesAnotherColumnWhenOnlyOneRowFits() {
        assertEquals(27, EmiCraftButtonPlacement.sideWidth(2, 1));
        assertArrayEquals(new int[]{125, 4},
                EmiCraftButtonPlacement.position(0, 2, 1, 120, 14, 0));
        assertArrayEquals(new int[]{139, 4},
                EmiCraftButtonPlacement.position(1, 2, 1, 120, 14, 0));
    }
}
