package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import com.huanghuang.rsintegration.crafting.planning.SynchronousFallbackReason;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceMonitorPlanningFallbackTest extends BootstrapTest {
    @BeforeEach
    void resetCounters() {
        PerformanceMonitor.resetSynchronousPlanningFallbacksForTest();
        Diagnostics.clear();
        Diagnostics.setEnabled(true);
    }

    @AfterEach
    void clearDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setEnabled(false);
        PerformanceMonitor.resetSynchronousPlanningFallbacksForTest();
    }

    @Test
    void countsFixedReasonsAndIncludesThemInSnapshot() {
        ResourceLocation recipeId = new ResourceLocation("test", "fallback");
        for (SynchronousFallbackReason reason : SynchronousFallbackReason.values()) {
            PerformanceMonitor.recordSynchronousPlanningFallback(reason, recipeId);
        }

        for (SynchronousFallbackReason reason : SynchronousFallbackReason.values()) {
            assertEquals(1L, PerformanceMonitor.getSynchronousPlanningFallbackCount(reason));
        }
        assertTrue(PerformanceMonitor.snapshot().contains(
                "syncFallback=[main_thread_only:1,incomplete_demand_tree:1,recipe_overrides:1,"
                        + "pure_unresolvable:1,step_limit:1,search_limit:1,async_failure:1]"));
        assertEquals(SynchronousFallbackReason.values().length,
                Diagnostics.recentEvents().size());
        assertTrue(Diagnostics.recentEvents().stream().allMatch(event ->
                event.category() == Diagnostics.Category.PLANNING_FALLBACK
                        && recipeId.equals(event.recipeId())));
    }

    @Test
    void rejectionCancellationAndStaleResultsDoNotIncrementFallbacks() {
        ResourceLocation recipeId = new ResourceLocation("test", "excluded");
        assertFalse(recordAsyncFallbackIfEligible(
                new RejectedExecutionException("busy"), recipeId));
        assertFalse(recordAsyncFallbackIfEligible(
                new CancellationException("cancelled"), recipeId));
        assertFalse(recordAsyncFallbackIfEligible(
                new AsyncPlanningCoordinator.StalePlanningResultException(snapshot(recipeId)),
                recipeId));

        assertEquals(0L, Arrays.stream(SynchronousFallbackReason.values())
                .mapToLong(PerformanceMonitor::getSynchronousPlanningFallbackCount).sum());
        assertTrue(Diagnostics.recentEvents().isEmpty());
    }

    @Test
    void eligibleAsyncFailuresRecordOneClassifiedFallback() {
        ResourceLocation recipeId = new ResourceLocation("test", "async_failure");
        assertTrue(recordAsyncFallbackIfEligible(
                new PlanningThreadContext.MainThreadPlanningFallbackException("handler"), recipeId));
        assertTrue(recordAsyncFallbackIfEligible(
                new IllegalStateException("failed"), recipeId));

        assertEquals(1L, PerformanceMonitor.getSynchronousPlanningFallbackCount(
                SynchronousFallbackReason.MAIN_THREAD_ONLY));
        assertEquals(1L, PerformanceMonitor.getSynchronousPlanningFallbackCount(
                SynchronousFallbackReason.ASYNC_FAILURE));
        assertEquals(2, Diagnostics.recentEvents().size());
    }

    private static PlanningSnapshot snapshot(ResourceLocation recipeId) {
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1, recipeId,
                Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()),
                "network", "binding", false);
    }

    private static boolean recordAsyncFallbackIfEligible(
            Throwable failure, ResourceLocation recipeId) {
        var reason = SynchronousFallbackReason.fromAsyncFailure(failure);
        reason.ifPresent(value ->
                PerformanceMonitor.recordSynchronousPlanningFallback(value, recipeId));
        return reason.isPresent();
    }
}
