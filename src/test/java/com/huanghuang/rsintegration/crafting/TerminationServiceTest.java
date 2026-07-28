package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerminationServiceTest {
    @Test
    void runsAllStepsAndProducesStableAuditReport() {
        TerminationService.Session session = new TerminationService().begin(UUID.randomUUID(),
                TerminationCoordinator.Cause.CANCELLED, "cancelled");
        AtomicInteger calls = new AtomicInteger();
        session.classify(TerminationCoordinator.OperationState.PRE_START);
        session.step("refund", calls::incrementAndGet);
        session.step("failing-cleanup", () -> { throw new IllegalStateException("failed"); });
        TerminationCoordinator.Report first = session.finish();
        TerminationCoordinator.Report second = session.finish();
        assertEquals(1, calls.get());
        assertEquals(1, first.failedSteps());
        assertEquals(first, second);
        assertThrows(IllegalStateException.class,
                () -> session.step("late", calls::incrementAndGet));
    }

    @Test
    void policyControlsRefundCaptureAndNotificationWhileKeepingOrder() {
        List<String> calls = new ArrayList<>();
        TerminationCoordinator.Report report = new TerminationService().terminate(UUID.randomUUID(),
                TerminationCoordinator.Cause.FAILURE, "escaped",
                TerminationService.Policy.NO_REFUND, new RecordingActions(calls));
        assertEquals(List.of("classify", "capture:false", "scope", "graph", "surplus",
                "assets", "ledger-close", "notify"), calls);
        assertEquals(0, report.failedSteps());
    }

    private record RecordingActions(List<String> calls) implements TerminationService.Actions {
        @Override public void classify(TerminationService.Session session) { calls.add("classify"); }
        @Override public void settleCaptured(boolean deliver) { calls.add("capture:" + deliver); }
        @Override public void closeOperationScope() { calls.add("scope"); }
        @Override public void cleanupGraph() { calls.add("graph"); }
        @Override public void recoverGraphSurplus() { calls.add("surplus"); }
        @Override public void refundLedger() { calls.add("refund"); }
        @Override public void deliverSettledAssets() { calls.add("assets"); }
        @Override public void closeLedger() { calls.add("ledger-close"); }
        @Override public void notifyOwner() { calls.add("notify"); }
    }
}
