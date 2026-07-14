package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FermentationTankProductionTest extends BootstrapTest {

    @Test
    void simpleRecipeUsesFirstResultPerInputWithoutDoubleCountingList() {
        var production = FermentationRecipeOutputs.calculate(true, 2,
                List.of(new ItemStack(Items.SLIME_BALL), new ItemStack(Items.SLIME_BALL)));
        assertEquals(2, production.primary().getCount());
        assertTrue(production.secondary().isEmpty());
    }

    @Test
    void simpleRecipeKeepsFirstResultStackCount() {
        var production = FermentationRecipeOutputs.calculate(true, 2,
                List.of(new ItemStack(Items.SLIME_BALL, 2)));
        assertEquals(4, production.primary().getCount());
    }

    @Test
    void nonSimpleHomogeneousResultsAreSummed() {
        var production = FermentationRecipeOutputs.calculate(false, 1,
                List.of(new ItemStack(Items.SLIME_BALL, 2), new ItemStack(Items.SLIME_BALL, 3)));
        assertEquals(5, production.primary().getCount());
        assertTrue(production.secondary().isEmpty());
    }

    @Test
    void heterogeneousAndDifferentNbtResultsRemainSeparate() {
        ItemStack tagged = new ItemStack(Items.SLIME_BALL, 2);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "tagged");
        tagged.setTag(tag);

        var production = FermentationRecipeOutputs.calculate(false, 1, List.of(
                new ItemStack(Items.SLIME_BALL),
                new ItemStack(Items.CLAY_BALL, 2),
                tagged));
        assertEquals(1, production.primary().getCount());
        assertEquals(2, production.secondary().size());
    }

    @Test
    void productionIsDefensivelyCopied() {
        ItemStack source = new ItemStack(Items.SLIME_BALL, 2);
        var production = FermentationRecipeOutputs.calculate(false, 1, List.of(source));
        production.primary().setCount(1);
        assertEquals(2, source.getCount());
    }

    @Test
    void matchingStartupInputIsNotACompleteOutput() {
        SimpleContainer container = new SimpleContainer(4);
        container.setItem(0, new ItemStack(Items.SLIME_BALL));
        container.setItem(1, new ItemStack(Items.CLAY_BALL));
        var expected = new IBatchDelegate.ExpectedProduction(new ItemStack(Items.SLIME_BALL), 2);

        assertFalse(FermentationTankBatchDelegate.hasCompleteExpectedOutput(container, expected));
        container.setItem(0, new ItemStack(Items.SLIME_BALL, 2));
        container.setItem(1, ItemStack.EMPTY);
        assertTrue(FermentationTankBatchDelegate.hasCompleteExpectedOutput(container, expected));
    }

    @Test
    void completionNeedsLifecycleEvidence() {
        assertFalse(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, false, false, false));
        assertTrue(FermentationTankBatchDelegate.shouldLatchCompletion(
                true, false, false, false));
        assertTrue(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, true, false, false));
        assertTrue(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, false, true, false));
        assertTrue(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, false, false, true));
    }

    @Test
    void placedInputsMustStillExistForWorkingSnapshot() {
        SimpleContainer container = new SimpleContainer(4);
        container.setItem(0, new ItemStack(Items.ROTTEN_FLESH));
        container.setItem(1, new ItemStack(Items.BONE));
        List<ItemStack> placed = List.of(
                new ItemStack(Items.ROTTEN_FLESH), new ItemStack(Items.BONE));

        assertTrue(FermentationTankBatchDelegate.containsPlacedInputs(container, placed));
        container.setItem(1, ItemStack.EMPTY);
        assertFalse(FermentationTankBatchDelegate.containsPlacedInputs(container, placed));
    }
}
