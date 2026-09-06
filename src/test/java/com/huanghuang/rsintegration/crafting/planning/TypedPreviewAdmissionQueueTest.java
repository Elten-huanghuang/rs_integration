package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedPreviewAdmissionQueueTest {

    @Test
    void keepsOnlyLatestRequestForEachPlayer() {
        TypedPreviewAdmissionQueue queue = new TypedPreviewAdmissionQueue(2);
        UUID player = UUID.randomUUID();
        List<String> ran = new ArrayList<>();

        assertEquals(TypedPreviewAdmissionQueue.OfferResult.QUEUED,
                queue.offer(request(player, 1, 0, () -> ran.add("old"))));
        assertEquals(TypedPreviewAdmissionQueue.OfferResult.REPLACED,
                queue.offer(request(player, 2, 1, () -> ran.add("new"))));

        assertEquals(1, queue.run(1, 2, 10, ignored -> true));
        assertEquals(List.of("new"), ran);
    }

    @Test
    void admissionIsVisibleOnlyWhileTaskRuns() {
        TypedPreviewAdmissionQueue queue = new TypedPreviewAdmissionQueue(1);
        UUID player = UUID.randomUUID();
        List<Boolean> admitted = new ArrayList<>();
        queue.offer(request(player, 7, 0,
                () -> admitted.add(queue.isAdmitted(player, 7))));

        queue.run(1, 1, 10, ignored -> true);

        assertEquals(List.of(true), admitted);
        assertFalse(queue.isAdmitted(player, 7));
    }

    @Test
    void expiredRequestDoesNotConsumeAdmission() {
        TypedPreviewAdmissionQueue queue = new TypedPreviewAdmissionQueue(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        queue.offer(new TypedPreviewAdmissionQueue.Request(first, 1, 0,
                () -> events.add("stale-run"), () -> events.add("expired"),
                ignored -> events.add("failed")));
        queue.offer(request(second, 1, 9, () -> events.add("fresh")));

        assertEquals(1, queue.run(1, 10, 5, ignored -> true));
        assertEquals(List.of("expired", "fresh"), events);
    }

    @Test
    void capacityCountsPlayersRatherThanReplacements() {
        TypedPreviewAdmissionQueue queue = new TypedPreviewAdmissionQueue(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertEquals(TypedPreviewAdmissionQueue.OfferResult.QUEUED,
                queue.offer(request(first, 1, 0, () -> {})));
        assertEquals(TypedPreviewAdmissionQueue.OfferResult.REPLACED,
                queue.offer(request(first, 2, 0, () -> {})));
        assertEquals(TypedPreviewAdmissionQueue.OfferResult.FULL,
                queue.offer(request(second, 1, 0, () -> {})));
        assertEquals(1, queue.size());
    }

    @Test
    void taskFailureIsReportedWithoutEscapingTheTick() {
        TypedPreviewAdmissionQueue queue = new TypedPreviewAdmissionQueue(1);
        UUID player = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        queue.offer(new TypedPreviewAdmissionQueue.Request(player, 1, 0,
                () -> { throw new IllegalArgumentException("invalid graph"); },
                () -> events.add("expired"),
                failure -> events.add(failure.getMessage())));

        assertEquals(1, queue.run(1, 1, 10, ignored -> true));
        assertEquals(List.of("invalid graph"), events);
        assertFalse(queue.isAdmitted(player, 1));
    }

    private static TypedPreviewAdmissionQueue.Request request(
            UUID player, long generation, long queuedNanos, Runnable task) {
        return new TypedPreviewAdmissionQueue.Request(
                player, generation, queuedNanos, task, () -> {}, ignored -> {});
    }

    @Test
    void expandedGraceDoesNotDelayAnImmediatelyAvailableRequest() {
        var queue = new TypedPreviewAdmissionQueue(32);
        var events = new ArrayList<String>();
        queue.offer(request(UUID.randomUUID(), 1, 0, () -> events.add("ran")));
        long grace = com.huanghuang.rsintegration.config.RSIntegrationConfig
                .DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS * 1_000_000L;
        assertEquals(1, queue.run(1, 0, grace, ignored -> true));
        assertEquals(List.of("ran"), events);
    }

    @Test
    void defaultGraceCanAdmitFullQueueAtOneRequestPerFiftyMillisecondTick() {
        assertEquals(List.of(20, 12), runBurst(1_000));
        assertEquals(List.of(32, 0), runBurst(com.huanghuang.rsintegration.config.RSIntegrationConfig
                .DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS));
    }

    private static List<Integer> runBurst(int graceMillis) {
        var queue = new TypedPreviewAdmissionQueue(32);
        var ran = new java.util.concurrent.atomic.AtomicInteger();
        var expired = new java.util.concurrent.atomic.AtomicInteger();
        for (int index = 0; index < 32; index++) {
            queue.offer(new TypedPreviewAdmissionQueue.Request(UUID.randomUUID(), 1, 0,
                    ran::incrementAndGet, expired::incrementAndGet,
                    failure -> { throw new AssertionError(failure); }));
        }
        for (int tick = 1; tick <= 32; tick++) {
            queue.run(1, tick * 50_000_000L, graceMillis * 1_000_000L, ignored -> true);
        }
        return List.of(ran.get(), expired.get());
    }
}
