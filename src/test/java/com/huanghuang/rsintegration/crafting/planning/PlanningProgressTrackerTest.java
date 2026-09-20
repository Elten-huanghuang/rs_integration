package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void timeoutTranslationProducesTimeoutState() {
        assertEquals(PlanningProgressSnapshot.State.TIMED_OUT,
                PlanningProgressServer.failureState(Component.translatable(
                        "rsi.plan.failure.planning_timeout")));
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
