package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressTiming;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressPublisherTest {
    @Test
    void sequenceAloneDoesNotMakePayloadDifferent() {
        UUID id = UUID.randomUUID();
        CraftProgressSnapshot first = snapshot(id, 1, 0);
        CraftProgressSnapshot second = snapshot(id, 2, 0);
        assertTrue(CraftProgressPublisher.samePayload(first, second));
        assertFalse(CraftProgressPublisher.samePayload(first, snapshot(id, 3, 1)));
    }

    @Test
    void changedNodesOnlyContainsNewPayloads() {
        UUID id = UUID.randomUUID();
        CraftProgressSnapshot previous = snapshot(id, 1, 0);
        CraftProgressSnapshot current = snapshot(id, 2, 1);
        assertTrue(CraftProgressPublisher.changedNodes(previous, current).isEmpty());
    }

    @Test
    void newOutputQuantityPublishesEvenWhenStepAndOperationCountsAreUnchanged() {
        UUID id = UUID.randomUUID();
        var previous = new CraftProgressSnapshot(id, 1, CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.NONE, 0, 1, 1, null, List.of(), new CraftOutputProgress(0L, 64L));
        var current = new CraftProgressSnapshot(id, 2, CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.NONE, 0, 1, 1, null, List.of(), new CraftOutputProgress(4L, 64L));

        assertFalse(CraftProgressPublisher.samePayload(previous, current));
        assertTrue(CraftProgressPublisher.changedNodes(previous, current).isEmpty());
    }

    @Test
    void terminalUpdatesPreferDeltaAndPeriodicRefreshKeepsFullPackets() {
        UUID id = UUID.randomUUID();
        CraftProgressSnapshot previous = snapshot(id, 1, 0);
        CraftProgressSnapshot running = snapshot(id, 2, 1);
        CraftProgressSnapshot terminal = new CraftProgressSnapshot(id,
                CraftProgressSnapshot.TERMINAL_SEQUENCE,
                CraftProgressSnapshot.Result.SUCCEEDED, CraftProgressSnapshot.Reason.NONE,
                2, 2, 0, null, List.of());

        assertFalse(CraftProgressPublisher.shouldSendDelta(null, running, 20));
        assertTrue(CraftProgressPublisher.shouldSendDelta(previous, running, 20));
        assertFalse(CraftProgressPublisher.shouldSendDelta(previous, running, 100));
        assertTrue(CraftProgressPublisher.shouldSendDelta(previous, terminal, 100));
    }

    private static CraftProgressSnapshot snapshot(UUID id, int sequence, int completed) {
        return new CraftProgressSnapshot(id, sequence, CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.NONE, completed, 2, 0, null, List.of());
    }

    @Test
    void timeContinuesToPublishWhileAQueueHasNoOtherStateChanges() {
        UUID id = UUID.randomUUID();
        var first = new CraftProgressSnapshot(id, 1, CraftProgressSnapshot.Result.WAITING,
                CraftProgressSnapshot.Reason.MACHINE_BUSY, 0, 1, 0, null, List.of(),
                CraftOutputProgress.UNKNOWN, new CraftProgressTiming(1_000, 1_000));
        var second = new CraftProgressSnapshot(id, 2, first.result(), first.reason(), 0, 1, 0,
                null, List.of(), CraftOutputProgress.UNKNOWN, new CraftProgressTiming(2_000, 2_000));
        assertFalse(CraftProgressPublisher.samePayload(first, second));
        assertTrue(CraftProgressPublisher.changedNodes(first, second).isEmpty());
    }
}
