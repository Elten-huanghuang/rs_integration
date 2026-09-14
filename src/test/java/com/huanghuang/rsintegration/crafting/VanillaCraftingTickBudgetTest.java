package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaCraftingTickBudgetTest {

    @Test
    void allChainsShareOneGlobalLimit() {
        VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(10);
        var first = budget.allowance(8);
        var second = budget.allowance(8);

        assertEquals(8, first.claimUpTo(8));
        assertEquals(2, second.claimUpTo(8));
        assertEquals(10, budget.used());
        assertEquals(0, second.remaining());
    }

    @Test
    void exactClaimsDoNotPartiallyConsumeGraphNodeBudget() {
        VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(5);
        var chain = budget.allowance(5);

        assertFalse(chain.tryClaimExact(6));
        assertEquals(0, budget.used());
        assertTrue(chain.tryClaimExact(5));
        assertEquals(5, budget.used());
    }

    @Test
    void roundRobinStartRotatesWithoutDroppingChains() {
        List<String> chains = List.of("a", "b", "c");

        assertEquals(List.of("a", "b", "c"),
                AsyncCraftManager.roundRobinOrder(chains, 0));
        assertEquals(List.of("b", "c", "a"),
                AsyncCraftManager.roundRobinOrder(chains, 1));
        assertEquals(List.of("c", "a", "b"),
                AsyncCraftManager.roundRobinOrder(chains, 2));
    }

    @Test
    void partialRoundRobinPassResumesAtFirstDeferredChain() {
        assertEquals(2, AsyncCraftManager.nextRoundRobinCursor(0, 2, 5));
        assertEquals(0, AsyncCraftManager.nextRoundRobinCursor(3, 2, 5));
        assertEquals(4, AsyncCraftManager.nextRoundRobinCursor(3, 0, 5));
    }

    @Test
    void completeRoundRobinPassRotatesTheStartingChain() {
        assertEquals(2, AsyncCraftManager.nextRoundRobinCursor(1, 5, 5));
        assertEquals(0, AsyncCraftManager.nextRoundRobinCursor(7, 3, 0));
    }
}
