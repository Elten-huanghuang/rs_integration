package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningProgressTrackerTest {
    private static final ResourceLocation RECIPE = new ResourceLocation("minecraft", "stick");

    @AfterEach
    void clear() {
        PlanningProgressTracker.clear();
    }

    @Test
    void acceptsOnlyTheCurrentRequest() {
        PlanningProgressTracker.start(10L, RECIPE);
        PlanningProgressTracker.update(snapshot(9L, 1L,
                PlanningProgressSnapshot.Phase.SEARCH));
        assertEquals(10L, PlanningProgressTracker.current().requestId());

        PlanningProgressTracker.update(snapshot(10L, 2L,
                PlanningProgressSnapshot.Phase.DEMAND_TREE));
        assertEquals(PlanningProgressSnapshot.Phase.DEMAND_TREE,
                PlanningProgressTracker.current().phase());
    }

    @Test
    void ignoresAnOlderGeneration() {
        PlanningProgressTracker.start(10L, RECIPE);
        PlanningProgressTracker.update(snapshot(10L, 5L,
                PlanningProgressSnapshot.Phase.SEARCH));
        PlanningProgressTracker.update(snapshot(10L, 4L,
                PlanningProgressSnapshot.Phase.PREPARING));

        PlanningProgressSnapshot current = PlanningProgressTracker.current();
        assertNotNull(current);
        assertEquals(5L, current.requestGeneration());
        assertEquals(PlanningProgressSnapshot.Phase.SEARCH, current.phase());
    }

    @Test
    void matchingPlanResponseClearsOnlyItsOwnCard() {
        PlanningProgressTracker.start(10L, RECIPE);
        PlanningProgressTracker.responseReceived(9L);
        assertNotNull(PlanningProgressTracker.current());

        PlanningProgressTracker.responseReceived(10L);
        assertNull(PlanningProgressTracker.current());
    }

    @Test
    void successFallbackIsBriefButFailuresRemainReadable() {
        assertEquals(400L, PlanningProgressTracker.terminalVisibleMillis(
                PlanningProgressSnapshot.State.SUCCEEDED));
        assertEquals(3_000L, PlanningProgressTracker.terminalVisibleMillis(
                PlanningProgressSnapshot.State.FAILED));
        assertEquals(3_000L, PlanningProgressTracker.terminalVisibleMillis(
                PlanningProgressSnapshot.State.TIMED_OUT));
    }

    @Test
    void finalizingHasAnIndependentClientFallbackTimeout() {
        long started = 1_000L;

        assertEquals(8_000L, PlanningProgressTracker.FINALIZING_VISIBLE_MS);
        assertFalse(PlanningProgressTracker.finalizingTimedOut(
                PlanningProgressSnapshot.State.FINALIZING, started, started + 7_999L));
        assertTrue(PlanningProgressTracker.finalizingTimedOut(
                PlanningProgressSnapshot.State.FINALIZING, started, started + 8_000L));
        assertFalse(PlanningProgressTracker.finalizingTimedOut(
                PlanningProgressSnapshot.State.RUNNING, started, started + 8_000L));
    }

    @Test
    void timeoutTranslationProducesTimeoutState() {
        assertEquals(PlanningProgressSnapshot.State.TIMED_OUT,
                PlanningProgressServer.failureState(Component.translatable(
                        "rsi.plan.failure.planning_timeout")));
        assertEquals(PlanningProgressSnapshot.State.TIMED_OUT,
                PlanningProgressServer.failureState(Component.translatable(
                        "rsi.plan.failure.finalizing_timeout")));
        assertEquals(PlanningProgressSnapshot.State.FAILED,
                PlanningProgressServer.failureState(Component.translatable(
                        "rsi.plan.failure.planner_busy")));
    }

    private static PlanningProgressSnapshot snapshot(long requestId, long generation,
                                                     PlanningProgressSnapshot.Phase phase) {
        return new PlanningProgressSnapshot(requestId, generation, RECIPE,
                PlanningProgressSnapshot.State.RUNNING, phase, 250L, Component.empty());
    }
}
