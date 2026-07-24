package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatedCraftingOutputAccumulatorTest extends BootstrapTest {

    @Test
    void emptyLaterOutputCannotReusePreviousIteration() {
        RepeatedCraftingOutputAccumulator outputs = new RepeatedCraftingOutputAccumulator();

        assertTrue(outputs.add(new ItemStack(Items.DIAMOND, 1)));
        assertFalse(outputs.add(ItemStack.EMPTY));
        assertEquals(1, outputs.result().getCount());
    }

    @Test
    void identicalOutputsAreSummedAcrossIterations() {
        RepeatedCraftingOutputAccumulator outputs = new RepeatedCraftingOutputAccumulator();

        assertTrue(outputs.add(new ItemStack(Items.DIAMOND, 2)));
        assertTrue(outputs.add(new ItemStack(Items.DIAMOND, 3)));

        assertEquals(5, outputs.result().getCount());
    }

    @Test
    void differentNbtVariantsCannotBeFlattenedIntoOneResult() {
        RepeatedCraftingOutputAccumulator outputs = new RepeatedCraftingOutputAccumulator();
        ItemStack first = taggedDiamond("first");
        ItemStack second = taggedDiamond("second");

        assertTrue(outputs.add(first));
        assertFalse(outputs.add(second));
        assertEquals("first", outputs.result().getTag().getString("variant"));
    }

    private static ItemStack taggedDiamond(String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
