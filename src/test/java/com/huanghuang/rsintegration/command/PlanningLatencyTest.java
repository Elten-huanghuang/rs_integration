package com.huanghuang.rsintegration.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningLatencyTest {
    @Test
    void timingsAreObservationOnlyAndRemainSeparatedByPhase() {
        var preparation = PerformanceMonitor.PlanningLatencyPhase.PREPARATION;
        var queue = PerformanceMonitor.PlanningLatencyPhase.QUEUE_WAIT;
        var handoff = PerformanceMonitor.PlanningLatencyPhase.HANDOFF_WAIT;
        var before = PerformanceMonitor.planningLatency(preparation);
        var queueBefore = PerformanceMonitor.planningLatency(queue);
        var handoffBefore = PerformanceMonitor.planningLatency(handoff);
        PerformanceMonitor.recordPlanningLatency(preparation, -1);
        PerformanceMonitor.recordPlanningLatency(preparation, 10_000_000_000L);
        var after = PerformanceMonitor.planningLatency(preparation);
        assertEquals(before.calls() + 2, after.calls());
        assertEquals(before.totalNanos() + 10_000_000_000L, after.totalNanos());
        assertTrue(after.maxNanos() >= 10_000_000_000L);
        assertEquals(queueBefore, PerformanceMonitor.planningLatency(queue));
        assertEquals(handoffBefore, PerformanceMonitor.planningLatency(handoff));
    }
}
