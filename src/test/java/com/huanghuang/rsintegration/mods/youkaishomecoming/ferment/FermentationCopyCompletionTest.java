package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.ExpectedProduction;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FermentationCopyCompletionTest extends BootstrapTest {
    private final List<ItemStack> inputs = List.of(
            new ItemStack(Items.SLIME_BALL), new ItemStack(Items.DIRT));
    private final ExpectedProduction expected = new ExpectedProduction(
            new ItemStack(Items.SLIME_BALL), 2);

    @Test
    void seedIsNotMistakenForCompletedDuplication() {
        SimpleContainer machine = new SimpleContainer(inputs.get(0).copy(), inputs.get(1).copy());

        assertTrue(FermentationTankBatchDelegate.containsPlacedInputs(machine, inputs));
        assertFalse(FermentationTankBatchDelegate.hasCompleteExpectedOutput(machine, expected));
        assertFalse(FermentationTankBatchDelegate.shouldLatchCompletion(false, false, false, false));
    }

    @Test
    void copiedSeedAndConsumedCostPermitCompletionWhenProgressResets() {
        SimpleContainer machine = new SimpleContainer(
                new ItemStack(Items.SLIME_BALL), new ItemStack(Items.SLIME_BALL));

        boolean inputsRemain = FermentationTankBatchDelegate.containsPlacedInputs(machine, inputs);
        boolean outputPresent = FermentationTankBatchDelegate.hasCompleteExpectedOutput(machine, expected);
        assertFalse(inputsRemain);
        assertTrue(outputPresent);
        assertTrue(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, false, false, !inputsRemain && outputPresent));
    }

    @Test
    void outputsAcrossSlotsAreCountedTogether() {
        SimpleContainer machine = new SimpleContainer(
                new ItemStack(Items.SLIME_BALL, 2), ItemStack.EMPTY);

        assertFalse(FermentationTankBatchDelegate.containsPlacedInputs(machine, inputs));
        assertTrue(FermentationTankBatchDelegate.hasCompleteExpectedOutput(machine, expected));
    }

    @Test
    void partialOutputCannotCompleteAnUnobservedTransform() {
        SimpleContainer machine = new SimpleContainer(new ItemStack(Items.SLIME_BALL), ItemStack.EMPTY);
        boolean outputPresent = FermentationTankBatchDelegate.hasCompleteExpectedOutput(machine, expected);

        assertFalse(outputPresent);
        assertFalse(FermentationTankBatchDelegate.shouldLatchCompletion(
                false, false, false, outputPresent));
    }
}
