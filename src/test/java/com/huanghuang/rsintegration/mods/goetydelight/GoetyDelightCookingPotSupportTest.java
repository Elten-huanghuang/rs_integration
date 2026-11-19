package com.huanghuang.rsintegration.mods.goetydelight;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyDelightCookingPotSupportTest extends BootstrapTest {
    @Test
    void modifiedFoodNbtDoesNotPreventOutputCollection() {
        ItemStack actual = new ItemStack(Items.MUSHROOM_STEW, 2);
        CompoundTag changedData = new CompoundTag();
        changedData.putBoolean("SoulInfused", true);
        actual.setTag(changedData);

        assertTrue(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                actual, new ItemStack(Items.MUSHROOM_STEW, 2)));
    }

    @Test
    void ignoringNbtStillRequiresTheExpectedItemAndCount() {
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                new ItemStack(Items.RABBIT_STEW, 1),
                new ItemStack(Items.MUSHROOM_STEW, 1)));
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                new ItemStack(Items.MUSHROOM_STEW, 1),
                new ItemStack(Items.MUSHROOM_STEW, 2)));
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                ItemStack.EMPTY, new ItemStack(Items.MUSHROOM_STEW)));
    }

    @Test
    void drainingCookingSlotsLeavesSoulSourceInThePot() {
        ItemStackHandler inventory = new ItemStackHandler(10);
        inventory.setStackInSlot(0, new ItemStack(Items.POTATO));
        inventory.setStackInSlot(7, new ItemStack(Items.BOWL));
        inventory.setStackInSlot(8, new ItemStack(Items.MUSHROOM_STEW));
        inventory.setStackInSlot(9, new ItemStack(Items.TOTEM_OF_UNDYING));

        var cookingSlots = GoetyDelightCookingPotSupport.cookingSlots(inventory);
        assertEquals(9, cookingSlots.getSlots());
        for (int slot = 0; slot < cookingSlots.getSlots(); slot++) {
            cookingSlots.extractItem(slot, 64, false);
        }

        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(inventory.getStackInSlot(7).isEmpty());
        assertTrue(inventory.getStackInSlot(8).isEmpty());
        assertTrue(inventory.getStackInSlot(9).is(Items.TOTEM_OF_UNDYING));
        assertNull(GoetyDelightCookingPotSupport.cookingSlots(new ItemStackHandler(8)));
    }
}
