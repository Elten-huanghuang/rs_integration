package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProbabilisticProductionTest extends BootstrapTest {
    @Test
    void missesDoNotReleaseDownstreamDemandAndActualSurplusCompletesIt() {
        ProbabilisticProduction progress = new ProbabilisticProduction(
                ProductionTarget.of(new ItemStack(Items.EGG), 3), 8);
        progress.started();
        progress.settle(List.of());
        progress.started();
        progress.settle(List.of(new ItemStack(Items.FEATHER, 10), new ItemStack(Items.EGG)));
        assertFalse(progress.complete());
        assertEquals(2, progress.remaining());
        progress.started();
        progress.settle(List.of(new ItemStack(Items.EGG, 4)));
        assertTrue(progress.complete());
        assertEquals(5, progress.produced());
        assertEquals(3, progress.attempts());
        assertFalse(progress.canAttempt());
    }

    @Test
    void exhaustedBudgetRetainsRealProgressAndCannotStartAnotherAttempt() {
        ProbabilisticProduction progress = new ProbabilisticProduction(
                ProductionTarget.of(new ItemStack(Items.EGG), 2), 2);
        progress.started();
        progress.settle(List.of(new ItemStack(Items.EGG)));
        progress.started();
        progress.settle(List.of());
        assertFalse(progress.canAttempt());
        assertFalse(progress.complete());
        assertEquals(1, progress.produced());
        assertEquals(1, progress.remaining());
        assertThrows(IllegalStateException.class, progress::started);
    }

    @Test
    void strictTargetRejectsAnotherNbtVariantWhileItemOnlyTargetAcceptsRuntimeTags() {
        ItemStack exact = new ItemStack(Items.PAPER);
        exact.getOrCreateTag().putString("quality", "rare");
        ProbabilisticProduction strict = new ProbabilisticProduction(ProductionTarget.of(exact, 1), 4);
        ItemStack other = new ItemStack(Items.PAPER);
        other.getOrCreateTag().putString("quality", "common");
        strict.started();
        strict.settle(List.of(other, new ItemStack(Items.PAPER)));
        assertEquals(0, strict.produced());
        strict.started();
        strict.settle(List.of(exact));
        assertTrue(strict.complete());
        ProbabilisticProduction itemOnly = new ProbabilisticProduction(
                ProductionTarget.of(new ItemStack(Items.PAPER), 1), 4);
        itemOnly.started();
        itemOnly.settle(List.of(other));
        assertTrue(itemOnly.complete());
    }

    @Test
    void fluidQuantityIsCountedInUnitsWithoutStackSizeTruncation() {
        ProbabilisticProduction progress = new ProbabilisticProduction(
                ProductionTarget.of(new ItemStack(Items.PAPER), 750), 16);
        for (int i = 0; i < 2; i++) {
            progress.started();
            progress.settle(List.of(new ItemStack(Items.PAPER, 250)));
            assertFalse(progress.complete());
        }
        progress.started();
        progress.settle(List.of(new ItemStack(Items.PAPER, 250)));
        assertTrue(progress.complete());
        assertEquals(750, progress.produced());
    }
}
