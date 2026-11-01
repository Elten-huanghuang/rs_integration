package com.huanghuang.rsintegration.crafting.planning;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningProgressTest {
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

    @Test
    void roundTripsCorrelatedProgress() {
        PlanningProgressSnapshot expected = new PlanningProgressSnapshot(
                42L, 7L, new ResourceLocation("minecraft", "crafting_table"),
                PlanningProgressSnapshot.State.RUNNING,
                PlanningProgressSnapshot.Phase.DEMAND_TREE,
                1_825L, Component.translatable("rsi.planning.phase.demand_tree"));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        PlanningProgressPacket.encode(new PlanningProgressPacket(expected), buffer);
        PlanningProgressSnapshot actual = PlanningProgressPacket.decode(buffer).snapshot();

        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.requestGeneration(), actual.requestGeneration());
        assertEquals(expected.recipeId(), actual.recipeId());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.elapsedMillis(), actual.elapsedMillis());
        assertEquals(expected.detail(), actual.detail());
    }

    @Test
    void rejectsZeroRequestId() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(0L);
        buffer.writeVarLong(1L);

        assertThrows(DecoderException.class, () -> PlanningProgressPacket.decode(buffer));
    }

    @Test
    void rejectsUnknownStateOrdinal() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(1L);
        buffer.writeVarLong(1L);
        buffer.writeResourceLocation(new ResourceLocation("minecraft", "stone"));
        buffer.writeVarInt(999);
        buffer.writeVarInt(0);

        assertThrows(DecoderException.class, () -> PlanningProgressPacket.decode(buffer));
    }

    private static PlanningProgressSnapshot snapshot(long requestId, long generation,
                                                     PlanningProgressSnapshot.Phase phase) {
        return new PlanningProgressSnapshot(requestId, generation, RECIPE,
                PlanningProgressSnapshot.State.RUNNING, phase, 250L, Component.empty());
    }
}
