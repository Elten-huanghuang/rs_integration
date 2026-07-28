package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronousFallbackReasonTest {
    @Test
    void classifiesUnavailableAsyncInputs() {
        assertEquals(SynchronousFallbackReason.MAIN_THREAD_ONLY,
                SynchronousFallbackReason.whenAsyncUnavailable(true, false).orElseThrow());
        assertEquals(SynchronousFallbackReason.TARGET_NOT_PROJECTED,
                SynchronousFallbackReason.whenAsyncUnavailable(false, false).orElseThrow());
        assertTrue(SynchronousFallbackReason.whenAsyncUnavailable(false, true).isEmpty());
    }

    @Test
    void classifiesEveryNonSuccessfulPureResult() {
        assertEquals(SynchronousFallbackReason.PURE_UNRESOLVABLE,
                SynchronousFallbackReason.fromPureResult(
                        result(PureRecipePlanner.Status.UNRESOLVABLE)).orElseThrow());
        assertEquals(SynchronousFallbackReason.STEP_LIMIT,
                SynchronousFallbackReason.fromPureResult(
                        result(PureRecipePlanner.Status.STEP_LIMIT)).orElseThrow());
        assertEquals(SynchronousFallbackReason.SEARCH_LIMIT,
                SynchronousFallbackReason.fromPureResult(
                        result(PureRecipePlanner.Status.SEARCH_LIMIT)).orElseThrow());
        assertTrue(SynchronousFallbackReason.fromPureResult(
                result(PureRecipePlanner.Status.SUCCESS)).isEmpty());
        assertTrue(SynchronousFallbackReason.fromPureResult(null).isEmpty());
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

    private static PureRecipePlanner.Result result(PureRecipePlanner.Status status) {
        return new PureRecipePlanner.Result(status == PureRecipePlanner.Status.SUCCESS,
                List.of(), List.of(), Map.of(), status, 0, 0, 0);
    }

    private static PlanningSnapshot snapshot() {
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
    }
}
