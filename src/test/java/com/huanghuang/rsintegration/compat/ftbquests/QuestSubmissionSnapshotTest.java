package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestSubmissionSnapshotTest extends BootstrapTest {

    @Test
    void requirementClampsProgressAndCalculatesRemaining() {
        QuestItemRequirement requirement = new QuestItemRequirement(1L,
                new ItemStack(Items.DIAMOND), List.of(new ItemStack(Items.DIAMOND)),
                16L, 20L, true, false, false, false);

        assertEquals(16L, requirement.progress());
        assertEquals(0L, requirement.remaining());
    }

    @Test
    void snapshotEligibilityComesFromExplicitReason() {
        QuestSubmissionSnapshot eligible = new QuestSubmissionSnapshot(1L, "Quest",
                new ItemStack(Items.DIAMOND), false, false,
                QuestSubmissionEligibility.ELIGIBLE, List.of(), List.of(), 1, false);
        QuestSubmissionSnapshot blocked = new QuestSubmissionSnapshot(2L, "Blocked",
                ItemStack.EMPTY, false, true,
                QuestSubmissionEligibility.SEQUENTIAL_TASKS_UNSUPPORTED, List.of(), List.of(), 0, false);

        assertTrue(eligible.eligible());
        assertFalse(blocked.eligible());
    }

    @Test
    void recordsDefensivelyCopyItemStacksAndLists() {
        ItemStack display = new ItemStack(Items.DIAMOND, 4);
        QuestItemRequirement requirement = new QuestItemRequirement(1L, display,
                List.of(display), 4L, 0L, true, false, false, false);
        display.setCount(1);

        assertEquals(4, requirement.displayStack().getCount());
        assertEquals(4, requirement.validDisplayItems().get(0).getCount());
    }
}
