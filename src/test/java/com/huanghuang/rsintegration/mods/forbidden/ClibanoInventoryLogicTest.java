package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClibanoInventoryLogicTest extends BootstrapTest {

    @Test
    void choosesOnlyIdleInputLane() {
        assertEquals(3, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 0, 0));
        assertEquals(4, ClibanoInventoryLogic.chooseInputSlot(new ItemStack(Items.STONE), ItemStack.EMPTY, 0, 0));
        assertEquals(-1, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 1, 0));
        assertEquals(-1, ClibanoInventoryLogic.chooseInputSlot(
                new ItemStack(Items.STONE), new ItemStack(Items.DIRT), 0, 0));
    }

    @Test
    void countsExpectedOutputAcrossBothSlots() {
        ItemStack expected = new ItemStack(Items.IRON_INGOT);
        assertEquals(5, ClibanoInventoryLogic.countMatching(List.of(
                expected.copyWithCount(2),
                expected.copyWithCount(3),
                new ItemStack(Items.GOLD_INGOT)), expected));
    }

    @Test
    void refundsOnlyOperationOwnedFuelDelta() {
        ItemStack baseline = new ItemStack(Items.COAL, 5);
        assertEquals(2, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.COAL, 7)));
        assertEquals(0, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.COAL, 4)));
        assertEquals(0, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.CHARCOAL, 7)));
    }

    @Test
    void higherFireTierSatisfiesLowerRequirement() {
        assertTrue(ClibanoInventoryLogic.fireSatisfies(2, 1));
        assertTrue(ClibanoInventoryLogic.fireSatisfies(1, 1));
        assertFalse(ClibanoInventoryLogic.fireSatisfies(0, 1));
    }

    @Test
    void bufferedLaneCapacityUsesItsPairedOutputSlot() {
        assertEquals(ClibanoInventoryLogic.FIRST_OUTPUT_SLOT,
                ClibanoInventoryLogic.pairedOutputSlot(ClibanoInventoryLogic.FIRST_INPUT_SLOT));
        assertEquals(ClibanoInventoryLogic.SECOND_OUTPUT_SLOT,
                ClibanoInventoryLogic.pairedOutputSlot(ClibanoInventoryLogic.SECOND_INPUT_SLOT));
        assertEquals(16, ClibanoInventoryLogic.bufferedOperationCapacity(
                64, 1, 64, 4, 64));
    }

    @Test
    void configuredFuelWinsOverStorageIterationOrder() {
        var selection = ClibanoInventoryLogic.selectFuel(
                List.of(new ItemStack(Items.BAMBOO, 64), new ItemStack(Items.COAL, 4)),
                List.of("minecraft:coal", "minecraft:charcoal"), 1600,
                stack -> stack.is(Items.COAL) ? 1600 : stack.is(Items.BAMBOO) ? 50 : 0);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.COAL));
        assertEquals(1, selection.amount());
        assertFalse(selection.partial());
    }

    @Test
    void existingFuelOnlyTopsUpTheMissingAmount() {
        assertEquals(1, ClibanoInventoryLogic.fuelToAdd(3200, 1600, 1));
        assertEquals(0, ClibanoInventoryLogic.fuelToAdd(1600, 1600, 1));
        assertEquals(Integer.MAX_VALUE,
                ClibanoInventoryLogic.fuelToAdd(1600, 0, 1));
    }
}
