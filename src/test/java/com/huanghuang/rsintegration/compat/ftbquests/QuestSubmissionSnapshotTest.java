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

    @Test
    void contentComparisonAcceptsEquivalentCopiedStacks() {
        ItemStack firstStack = new ItemStack(Items.DIAMOND, 4);
        firstStack.getOrCreateTag().putInt("quality", 3);
        QuestItemRequirement firstRequirement = new QuestItemRequirement(10L, firstStack,
                List.of(firstStack), 8L, 2L, true, false, false, false);
        QuestSubmissionSnapshot first = new QuestSubmissionSnapshot(1L, "Quest", firstStack,
                false, false, QuestSubmissionEligibility.ELIGIBLE,
                List.of(firstRequirement), List.of(new QuestItemRewardPreview(20L, firstStack)),
                1, false);

        ItemStack copiedStack = firstStack.copy();
        QuestSubmissionSnapshot copied = new QuestSubmissionSnapshot(1L, "Quest", copiedStack,
                false, false, QuestSubmissionEligibility.ELIGIBLE,
                List.of(new QuestItemRequirement(10L, copiedStack, List.of(copiedStack),
                        8L, 2L, true, false, false, false)),
                List.of(new QuestItemRewardPreview(20L, copiedStack)), 1, false);

        assertTrue(first.contentEquals(copied));
    }

    @Test
    void contentComparisonDetectsProgressAndNbtChanges() {
        ItemStack original = new ItemStack(Items.DIAMOND);
        original.getOrCreateTag().putInt("quality", 1);
        QuestSubmissionSnapshot first = snapshotWithRequirement(original, 2L);

        ItemStack changedNbt = original.copy();
        changedNbt.getOrCreateTag().putInt("quality", 2);

        assertFalse(first.contentEquals(snapshotWithRequirement(original, 3L)));
        assertFalse(first.contentEquals(snapshotWithRequirement(changedNbt, 2L)));
    }

    private static QuestSubmissionSnapshot snapshotWithRequirement(ItemStack stack, long progress) {
        return new QuestSubmissionSnapshot(1L, "Quest", stack, false, false,
                QuestSubmissionEligibility.ELIGIBLE,
                List.of(new QuestItemRequirement(10L, stack, List.of(stack),
                        8L, progress, true, false, false, false)),
                List.of(), 0, false);
    }
}
