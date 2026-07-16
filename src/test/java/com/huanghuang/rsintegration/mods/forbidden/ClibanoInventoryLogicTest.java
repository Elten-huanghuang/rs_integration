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
}
