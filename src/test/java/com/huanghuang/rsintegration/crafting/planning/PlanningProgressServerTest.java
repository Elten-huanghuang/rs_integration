package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningProgressServerTest {
    @Test
    void finalizingTimeoutUsesItsOwnFiveSecondBudget() {
        long started = 1_000_000L;
        long budget = PlanningProgressServer.FINALIZING_TIMEOUT_MS * 1_000_000L;

        assertFalse(PlanningProgressServer.isFinalizingTimedOut(
                PlanningProgressSnapshot.State.FINALIZING, started, started + budget - 1));
        assertTrue(PlanningProgressServer.isFinalizingTimedOut(
                PlanningProgressSnapshot.State.FINALIZING, started, started + budget));
    }

    @Test
    void onlyFinalizingStateCanHitTheFinalizingTimeout() {
        long now = 1_000_000L + PlanningProgressServer.FINALIZING_TIMEOUT_MS * 1_000_000L;

        assertFalse(PlanningProgressServer.isFinalizingTimedOut(
                PlanningProgressSnapshot.State.RUNNING, 1_000_000L, now));
        assertFalse(PlanningProgressServer.isFinalizingTimedOut(
                PlanningProgressSnapshot.State.SUCCEEDED, 1_000_000L, now));
    }
}
