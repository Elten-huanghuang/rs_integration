package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.ExpectedProduction;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftGroupCaptureTest extends BootstrapTest {
    @Test
    void waitsForTheEntireExpectedWorldOutput() {
        ItemStack expected = new ItemStack(Items.DIAMOND, 2);

        assertFalse(ParallelCraftGroup.containsExpectedWorldOutput(
                List.of(new ItemStack(Items.DIAMOND)), expected));
        assertTrue(ParallelCraftGroup.containsExpectedWorldOutput(
                List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.DIAMOND)), expected));
    }

    @Test
    void bufferedWorkerWaitsForEveryIncrementalWorldDrop() {
        ExpectedProduction expected = new ExpectedProduction(
                new ItemStack(Items.DIAMOND), 6);

        assertFalse(ParallelCraftGroup.containsExpectedWorldProduction(
                List.of(new ItemStack(Items.DIAMOND)), expected));
        assertFalse(ParallelCraftGroup.containsExpectedWorldProduction(
                List.of(new ItemStack(Items.DIAMOND, 5)), expected));
        assertTrue(ParallelCraftGroup.containsExpectedWorldProduction(
                List.of(new ItemStack(Items.DIAMOND, 4),
                        new ItemStack(Items.DIAMOND, 2)), expected));
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

    @Test
    void cancellationSettlesOnlyOutputsAlreadyProducedByABatch() {
        ItemStack expectedBatch = new ItemStack(Items.DIAMOND, 128);
        assertEquals(6, ParallelCraftGroup.completedExecutionsFromCapture(
                expectedBatch, 128, List.of(new ItemStack(Items.DIAMOND, 6))));
        assertEquals(0, ParallelCraftGroup.completedExecutionsFromCapture(
                expectedBatch, 128, List.of(new ItemStack(Items.EMERALD, 6))));
    }
}
