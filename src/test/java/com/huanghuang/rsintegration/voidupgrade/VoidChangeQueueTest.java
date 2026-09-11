package com.huanghuang.rsintegration.voidupgrade;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidChangeQueueTest extends BootstrapTest {
    @Test
    void queuesOnlyMatchingPositiveChangesAndMergesThem() {
        VoidChangeQueue queue = new VoidChangeQueue();
        ItemStack stack = new ItemStack(Items.COBBLESTONE);
        queue.record(stack, 3, false);
        assertTrue(queue.isEmpty());
        queue.record(stack, 3, true);
        queue.record(stack, 4, true);
        VoidChangeQueue.Entry entry = queue.poll(64);
        assertEquals(7, entry.amount());
        assertTrue(queue.isEmpty());
    }

    @Test
    void negativeChangesCancelOnlyTheSamePendingVariant() {
        VoidChangeQueue queue = new VoidChangeQueue();
        ItemStack red = tagged("red");
        ItemStack blue = tagged("blue");
        queue.record(red, 5, true);
        queue.record(blue, -5, false);
        assertEquals(5, queue.poll(64).amount());
        queue.record(red, 5, true);
        queue.record(red, -5, false);
        assertTrue(queue.isEmpty());
    }

    @Test
    void pollSplitsLargeChangesByItemBudget() {
        VoidChangeQueue queue = new VoidChangeQueue();
        queue.record(new ItemStack(Items.STONE), 5000, true);
        assertEquals(4096, queue.poll(4096).amount());
        assertFalse(queue.isEmpty());
        assertEquals(904, queue.poll(4096).amount());
        assertNull(queue.poll(4096));
    }

    private static ItemStack tagged(String value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        stack.setTag(tag);
        return stack;
    }
}
