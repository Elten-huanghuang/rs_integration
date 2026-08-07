package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedNetworkInsertionTest extends BootstrapTest {
    @Test
    void recordsBeforePerformAndReturnsRealRemainder() {
        List<String> events = new ArrayList<>();
        ItemStack input = new ItemStack(Items.DIAMOND, 8);

        ItemStack remainder = TrackedInsertionSequence.insert(input, (stack, phase) -> {
            events.add(phase.name());
            return phase == TrackedInsertionSequence.Phase.SIMULATE
                    ? ItemStack.EMPTY : new ItemStack(Items.DIAMOND, 2);
        }, accepted -> events.add("TRACK:" + accepted.getCount()));

        assertEquals(List.of("SIMULATE", "TRACK:8", "PERFORM"), events);
        assertEquals(2, remainder.getCount());
        assertEquals(8, input.getCount());
    }

    @Test
    void partialAcceptanceRecordsOnlyAcceptedCount() {
        List<Integer> tracked = new ArrayList<>();
        ItemStack input = new ItemStack(Items.DIAMOND, 8);

        ItemStack remainder = TrackedInsertionSequence.insert(input,
                (stack, phase) -> new ItemStack(Items.DIAMOND,
                        phase == TrackedInsertionSequence.Phase.SIMULATE ? 5 : 4),
                accepted -> tracked.add(accepted.getCount()));

        assertEquals(List.of(3), tracked);
        assertEquals(4, remainder.getCount());
        assertEquals(8, input.getCount());
    }

    @Test
    void rejectionDoesNotRecord() {
        List<ItemStack> tracked = new ArrayList<>();
        ItemStack input = new ItemStack(Items.DIAMOND, 8);

        ItemStack remainder = TrackedInsertionSequence.insert(input,
                (stack, action) -> stack.copy(), tracked::add);

        assertTrue(tracked.isEmpty());
        assertEquals(8, remainder.getCount());
        assertEquals(8, input.getCount());
    }
}
