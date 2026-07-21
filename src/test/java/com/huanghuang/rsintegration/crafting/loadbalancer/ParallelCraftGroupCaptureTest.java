package com.huanghuang.rsintegration.crafting.loadbalancer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftGroupCaptureTest {
    @Test
    void waitsForTheEntireExpectedWorldOutput() {
        ItemStack expected = new ItemStack(Items.DIAMOND, 2);

        assertFalse(ParallelCraftGroup.containsExpectedWorldOutput(
                List.of(new ItemStack(Items.DIAMOND)), expected));
        assertTrue(ParallelCraftGroup.containsExpectedWorldOutput(
                List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.DIAMOND)), expected));
    }

    @Test
    void doesNotCombineDifferentNbtVariants() {
        ItemStack expected = new ItemStack(Items.DIAMOND);
        CompoundTag expectedTag = new CompoundTag();
        expectedTag.putString("owner", "expected");
        expected.setTag(expectedTag);
        ItemStack other = new ItemStack(Items.DIAMOND);
        CompoundTag otherTag = new CompoundTag();
        otherTag.putString("owner", "other");
        other.setTag(otherTag);

        assertFalse(ParallelCraftGroup.containsExpectedWorldOutput(List.of(other), expected));
        assertTrue(ParallelCraftGroup.containsExpectedWorldOutput(List.of(expected.copy()), expected));
    }
}
