package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphVanillaBatchBudgetTest {

    @Test
    void stableGraphBatchCostsOneOperationRegardlessOfExpandedExecutions() {
        VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(8);
        assertEquals(114, AsyncCraftChain.claimGraphVanillaExecutions(
                budget.allowance(8), 114, true));
        assertEquals(1, budget.used());
    }

    @Test
    void nonStableGraphNodeSlicesAcrossTicksInsteadOfWaitingForFullBudget() {
        int remaining = 114;
        int ticks = 0;
        int totalGranted = 0;
        while (remaining > 0 && ticks < 20) {
            VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(8);
            int granted = AsyncCraftChain.claimGraphVanillaExecutions(
                    budget.allowance(8), remaining, false);
            assertTrue(granted > 0 && granted <= 8);
            remaining -= granted;
            totalGranted += granted;
            ticks++;
        }
        assertEquals(114, totalGranted);
        assertEquals(0, remaining);
        assertEquals(15, ticks);
    }
}
