package com.huanghuang.rsintegration.crafting.consumption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputConsumptionTest {
    @Test
    void durabilityUsesTotalDamageBeforeCeiling() {
        InputConsumption durability = InputConsumption.durability(2L);

        assertEquals(3L, durability.requiredPhysicalAmount(65L, 1L, 64L));
        assertEquals(1L, durability.requiredPhysicalAmount(32L, 1L, 64L));
        assertEquals(2L, durability.requiredPhysicalAmount(65L, 1L, 64L + 1L));
    }

    @Test
    void durabilityNeverReservesMoreThanOneToolPerOperation() {
        assertEquals(2L,
                InputConsumption.durability(60_000L)
                        .requiredPhysicalAmount(2L, 1L, 64L));
    }

    @Test
    void catalystAndFuelHaveDistinctScalingRules() {
        assertEquals(1L,
                InputConsumption.catalyst().requiredPhysicalAmount(
                        1_000_000L, 1L, 1L));
        assertEquals(5L,
                InputConsumption.fuel(1L).requiredPhysicalAmount(
                        1_000L, 1L, 200L));
    }

    @Test
    void consumedAmountSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE,
                InputConsumption.consumed()
                        .requiredPhysicalAmount(Long.MAX_VALUE, 2L, 1L));
    }

    @Test
    void invalidUnitCombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputConsumption(InputConsumption.Kind.DURABILITY, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new InputConsumption(InputConsumption.Kind.CONSUMED, 1L));
    }
}
