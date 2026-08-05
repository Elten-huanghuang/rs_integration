package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarmUpBudgetPolicyTest {
    @Test
    void backgroundWarmUpUsesModerateBudgets() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(false);

        assertEquals(16_000_000L, budgets.recipeIndexNanos());
        assertEquals(8_000_000L, budgets.recipeGraphNanos());
    }

    @Test
    void waitingCraftRequestTemporarilyRaisesBudgets() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(true);

        assertEquals(24_000_000L, budgets.recipeIndexNanos());
        assertEquals(12_000_000L, budgets.recipeGraphNanos());
    }

    @Test
    void busyTickShrinksWarmUpToAvailableHeadroom() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(true, 43_000_000L);

        assertEquals(2_000_000L, budgets.recipeIndexNanos());
        assertEquals(2_000_000L, budgets.recipeGraphNanos());
    }

    @Test
    void overloadedTickStillMakesBoundedProgress() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(false, 60_000_000L);

        assertEquals(250_000L, budgets.recipeIndexNanos());
        assertEquals(250_000L, budgets.recipeGraphNanos());
    }
}
