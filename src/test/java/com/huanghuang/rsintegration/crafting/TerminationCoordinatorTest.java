package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminationCoordinatorTest {

    @Test
    void failureInOneStepDoesNotSkipLaterCleanup() {
        TerminationCoordinator coordinator = new TerminationCoordinator(
                UUID.randomUUID(), TerminationCoordinator.Cause.SERVER_STOP, "stopping");
        List<String> executed = new ArrayList<>();

        coordinator.run("stop-dispatch", () -> executed.add("stop"));
        coordinator.run("capture-close", () -> {
            executed.add("capture");
            throw new IllegalStateException("capture failed");
        });
        coordinator.run("ledger-refund", () -> executed.add("refund"));
        coordinator.run("lease-release", () -> executed.add("release"));

        TerminationCoordinator.Report report = coordinator.report();
        assertEquals(List.of("stop", "capture", "refund", "release"), executed);
        assertEquals(1, report.failedSteps());
        assertEquals("capture-close", report.steps().get(1).name());
        assertEquals("capture failed", report.steps().get(1).detail());
        assertFalse(report.clean());
    }

    @Test
    void operationClassificationIsCompleteAndAuditable() {
        TerminationCoordinator coordinator = new TerminationCoordinator(
                UUID.randomUUID(), TerminationCoordinator.Cause.FAILURE, "machine failed");

        coordinator.classify(TerminationCoordinator.OperationState.PRE_START);
        coordinator.classify(TerminationCoordinator.OperationState.IN_FLIGHT);
        coordinator.classify(TerminationCoordinator.OperationState.SETTLED);
        coordinator.classify(TerminationCoordinator.OperationState.UNKNOWN);
        coordinator.run("cleanup", () -> {});

        TerminationCoordinator.Report report = coordinator.report();
        assertEquals(1, report.preStartOperations());
        assertEquals(1, report.inFlightOperations());
        assertEquals(1, report.settledOperations());
        assertEquals(1, report.unknownOperations());
        assertFalse(report.clean());
    }

    @Test
    void cleanReportRequiresNoUnknownOperationsAndNoFailedSteps() {
        UUID craftId = UUID.randomUUID();
        TerminationCoordinator coordinator = new TerminationCoordinator(
                craftId, TerminationCoordinator.Cause.CANCELLED, "cancelled");
        coordinator.classify(TerminationCoordinator.OperationState.PRE_START);
        coordinator.classify(TerminationCoordinator.OperationState.SETTLED);
        coordinator.run("refund", () -> {});
        coordinator.run("deliver", () -> {});

        TerminationCoordinator.Report report = coordinator.report();
        assertEquals(craftId, report.craftId());
        assertEquals(TerminationCoordinator.Cause.CANCELLED, report.cause());
        assertEquals("cancelled", report.reason());
        assertEquals(0, report.failedSteps());
        assertTrue(report.clean());
    }

    @Test
    void reportDefensivelySnapshotsExecutedSteps() {
        TerminationCoordinator coordinator = new TerminationCoordinator(
                UUID.randomUUID(), TerminationCoordinator.Cause.OFFLINE, "offline");
        coordinator.run("first", () -> {});
        TerminationCoordinator.Report first = coordinator.report();
        coordinator.run("second", () -> {});

        assertEquals(1, first.steps().size());
        assertEquals(2, coordinator.report().steps().size());
    }
}
