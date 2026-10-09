package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbersStamperBatchDelegateTest extends BootstrapTest {
    @Test
    void replacesWrongStampWithAvailableCorrectStamp() {
        ItemStack original = new ItemStack(Items.IRON_INGOT);
        ItemStackHandler slot = new ItemStackHandler(1);
        slot.setStackInSlot(0, original.copy());

        assertTrue(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.GOLD_INGOT), Ingredient.of(Items.GOLD_INGOT)));
        assertTrue(Ingredient.of(Items.GOLD_INGOT).test(slot.getStackInSlot(0)));
    }

    @Test
    void rejectsWrongReplacementAndConcurrentSlotChange() {
        ItemStack original = new ItemStack(Items.IRON_INGOT);
        ItemStackHandler slot = new ItemStackHandler(1);
        slot.setStackInSlot(0, original.copy());
        Ingredient required = Ingredient.of(Items.GOLD_INGOT);

        assertFalse(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.COPPER_INGOT), required));
        assertTrue(ItemStack.isSameItemSameTags(original, slot.getStackInSlot(0)));

        slot.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        assertFalse(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.GOLD_INGOT), required));
        assertTrue(Ingredient.of(Items.DIAMOND).test(slot.getStackInSlot(0)));
    }
}
