package com.huanghuang.rsintegration.mods.distantworlds;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LithumFuelInventoryLogicTest {
    @Test
    void rejectsDifferentFuelIdentity() {
        assertEquals(0, LithumFuelInventoryLogic.insertionRoom(
                new ItemStack(Items.COAL, 2), new ItemStack(Items.CHARCOAL), 64));
    }

    @Test
    void calculatesInsertionRoom() {
        assertEquals(6, LithumFuelInventoryLogic.insertionRoom(
                new ItemStack(Items.COAL, 2), new ItemStack(Items.COAL), 8));
    }

    @Test
    void refundsOnlyOperationOwnedDelta() {
        assertEquals(2, LithumFuelInventoryLogic.refundableAddedCount(
                new ItemStack(Items.COAL, 3), 4, new ItemStack(Items.COAL, 5)));
        assertEquals(0, LithumFuelInventoryLogic.refundableAddedCount(
                new ItemStack(Items.COAL, 3), 4, new ItemStack(Items.CHARCOAL, 5)));
    }
}
