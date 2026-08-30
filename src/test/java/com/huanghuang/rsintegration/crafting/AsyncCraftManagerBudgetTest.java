package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncCraftManagerBudgetTest {
    @Test
    void defaultServerTickBudgetLeavesHeadroomForOtherServerWork() {
        assertEquals(8, com.huanghuang.rsintegration.config.RSIntegrationConfig
                .DEFAULT_CRAFTING_SERVER_TICK_BUDGET_MS);
    }
}
