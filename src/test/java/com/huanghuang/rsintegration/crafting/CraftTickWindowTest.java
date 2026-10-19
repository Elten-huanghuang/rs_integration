package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftTickWindowTest {

    @Test
    void immediateSubmissionsShareTheSameTickBudget() {
        CraftTickWindow window = new CraftTickWindow(
                42L, System.nanoTime(), 1_000_000_000L, 10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        VanillaCraftingTickBudget.ChainAllowance firstAllowance =
                window.allowance(first, 8);
        assertEquals(8, firstAllowance.claimUpTo(8));
        window.record(first, firstAllowance.used());

        assertEquals(2, window.remainingVanillaOperations(second, 8));
        VanillaCraftingTickBudget.ChainAllowance secondAllowance =
                window.allowance(second, 8);
        assertEquals(2, secondAllowance.claimUpTo(8));
        window.record(second, secondAllowance.used());

        assertEquals(10, window.usedVanillaOperations());
        assertEquals(0, window.remainingVanillaOperations(first, 8));
    }

    @Test
    void repeatedPumpCannotReusePerChainAllowance() {
        CraftTickWindow window = new CraftTickWindow(
                7L, System.nanoTime(), 1_000_000_000L, 24);
        UUID craft = UUID.randomUUID();

        VanillaCraftingTickBudget.ChainAllowance first =
                window.allowance(craft, 5);
        assertEquals(5, first.claimUpTo(5));
        window.record(craft, first.used());

        assertEquals(0, window.remainingVanillaOperations(craft, 5));
        VanillaCraftingTickBudget.ChainAllowance second =
                window.allowance(craft, 5);
        assertEquals(0, second.claimUpTo(5));
    }

    @Test
    void eachChainReceivesAtMostOneLogicalAdvancePerTickWindow() {
        CraftTickWindow window = new CraftTickWindow(
                9L, System.nanoTime(), 1_000_000_000L, 24);
        UUID craft = UUID.randomUUID();

        assertFalse(window.alreadyAdvanced(craft));
        window.markAdvanced(craft);
        assertTrue(window.alreadyAdvanced(craft));
    }

    @Test
    void liveWindowReportsRemainingTimeAndExpiredWindowDoesNot() {
        CraftTickWindow live = new CraftTickWindow(
                1L, System.nanoTime(), 1_000_000_000L, 8);
        CraftTickWindow expired = new CraftTickWindow(
                2L, System.nanoTime() - 1_000_000L, 1L, 8);

        assertTrue(live.hasTime());
        assertFalse(expired.hasTime());
    }
}
