package com.huanghuang.rsintegration.crafting.consumption;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.resources.ResourceLocation;
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
        assertEquals(16,
                FuelMath.requiredItemsForOperations(64L, 4, 64));
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
        assertThrows(IllegalArgumentException.class,
                () -> new InputConsumption(InputConsumption.Kind.CONSUMED, 0L,
                        new ResourceLocation("test", "returned")));
    }

    @Test
    void linkedOutputIsPartOfTheAuthoritativeConsumption() {
        ResourceLocation returnPort = new ResourceLocation("test", "empty_bucket");
        InputConsumption consumption = InputConsumption.containerReturned(returnPort);

        assertEquals(returnPort, consumption.linkedOutputPortId());
        assertEquals(DemandRole.CONTAINER_RETURNING, consumption.demandRole());
    }
}
