package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsertedStackDeltaTest extends BootstrapTest {

    @Test
    void calculatesFullPartialAndRejectedInsertions() {
        ItemStack input = new ItemStack(Items.DIAMOND, 64);

        assertEquals(64, InsertedStackDelta.between(input, ItemStack.EMPTY).getCount());
        assertEquals(64, InsertedStackDelta.between(input, null).getCount());
        assertEquals(44, InsertedStackDelta.between(input,
                new ItemStack(Items.DIAMOND, 20)).getCount());
        assertTrue(InsertedStackDelta.between(input,
                new ItemStack(Items.DIAMOND, 64)).isEmpty());
        assertEquals(64, input.getCount());
    }

    @Test
    void rejectsDifferentIdentityAndImpossibleRemainder() {
        ItemStack input = taggedDiamond("red", 4);

        assertTrue(InsertedStackDelta.between(input, new ItemStack(Items.GOLD_INGOT)).isEmpty());
        assertTrue(InsertedStackDelta.between(input, taggedDiamond("blue", 2)).isEmpty());
        assertTrue(InsertedStackDelta.between(input, taggedDiamond("red", 5)).isEmpty());
        assertEquals(4, input.getCount());
    }

    private static ItemStack taggedDiamond(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
