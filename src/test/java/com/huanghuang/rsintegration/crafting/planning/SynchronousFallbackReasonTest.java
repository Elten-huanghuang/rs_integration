package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronousFallbackReasonTest {
    @Test
    void classifiesPureRouteInputs() {
        assertEquals(SynchronousFallbackReason.MAIN_THREAD_ONLY,
                SynchronousFallbackReason.whenPureRouteUnavailable(true, true, false)
                        .orElseThrow());
        assertEquals(SynchronousFallbackReason.RECIPE_OVERRIDES,
                SynchronousFallbackReason.whenPureRouteUnavailable(false, true, false)
                        .orElseThrow());
        assertEquals(SynchronousFallbackReason.INCOMPLETE_DEMAND_TREE,
                SynchronousFallbackReason.whenPureRouteUnavailable(false, false, false)
                        .orElseThrow());
        assertEquals(SynchronousFallbackReason.CATALYST_ROUTE,
                SynchronousFallbackReason.whenPureRouteUnavailable(
                        false, false, true, true).orElseThrow());
        assertTrue(SynchronousFallbackReason.whenPureRouteUnavailable(false, false, true)
                .isEmpty());
    }

    @Test
    void excludesNonFallbackTerminalOutcomes() {
        assertTrue(SynchronousFallbackReason.fromAsyncFailure(
                new CancellationException("cancelled")).isEmpty());
        assertTrue(SynchronousFallbackReason.fromAsyncFailure(
                new CompletionException(new CancellationException("cancelled"))).isEmpty());
        assertTrue(SynchronousFallbackReason.fromAsyncFailure(
                new RejectedExecutionException("busy")).isEmpty());
        assertTrue(SynchronousFallbackReason.fromAsyncFailure(
                new AsyncPlanningCoordinator.StalePlanningResultException(snapshot())).isEmpty());
    }

    @Test
    void classifiesMainThreadBoundaryAndUnexpectedFailure() {
        assertEquals(SynchronousFallbackReason.MAIN_THREAD_ONLY,
                SynchronousFallbackReason.fromAsyncFailure(
                        new PlanningThreadContext.MainThreadPlanningFallbackException("handler"))
                        .orElseThrow());
        assertEquals(SynchronousFallbackReason.ASYNC_FAILURE,
                SynchronousFallbackReason.fromAsyncFailure(
                        new IllegalStateException("failed")).orElseThrow());
    }

    private static PlanningSnapshot snapshot() {
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
    }
}
