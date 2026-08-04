package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarmUpBudgetPolicyTest {
    @Test
    void backgroundWarmUpUsesModerateBudgets() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(false);

        assertEquals(4_000_000L, budgets.recipeIndexNanos());
        assertEquals(2_000_000L, budgets.recipeGraphNanos());
    }

    @Test
    void waitingCraftRequestTemporarilyRaisesBudgets() {
        WarmUpBudgetPolicy.Budgets budgets = WarmUpBudgetPolicy.select(true);

        assertEquals(8_000_000L, budgets.recipeIndexNanos());
        assertEquals(4_000_000L, budgets.recipeGraphNanos());
    }
}
