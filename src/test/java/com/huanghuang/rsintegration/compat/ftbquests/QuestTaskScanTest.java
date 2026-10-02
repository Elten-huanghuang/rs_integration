package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestTaskScanTest {

    @Test
    void largeTaskListIsSpreadAcrossTicksWithoutSkippingOrRepeatingTasks() {
        List<Long> taskIds = LongStream.rangeClosed(1L, 2_000L).boxed().toList();
        QuestTaskScan scan = new QuestTaskScan(taskIds);
        List<Long> visited = new ArrayList<>();
        int ticks = 0;

        while (!scan.finished()) {
            assertEquals(8, scan.processBatch(8, () -> true, visited::add));
            ticks++;
        }

        assertEquals(250, ticks);
        assertEquals(taskIds, visited);
        assertEquals(0, scan.processBatch(8, () -> true, visited::add));
    }

    @Test
    void exhaustedTimeBudgetKeepsTheNextTaskForTheFollowingTick() {
        QuestTaskScan scan = new QuestTaskScan(List.of(1L, 2L, 3L));
        List<Long> visited = new ArrayList<>();
        AtomicBoolean hasTime = new AtomicBoolean(true);

        assertEquals(1, scan.processBatch(8, hasTime::get, task -> {
            visited.add(task);
            hasTime.set(false);
        }));
        assertFalse(scan.finished());
        assertEquals(List.of(1L), visited);
        assertEquals(0, scan.processBatch(8, hasTime::get, visited::add));

        assertEquals(2, scan.processBatch(8, () -> true, visited::add));
        assertTrue(scan.finished());
        assertEquals(List.of(1L, 2L, 3L), visited);
    }

    @Test
    void multipleJobsShareTheRemainingServerTaskBudget() {
        QuestTaskScan first = new QuestTaskScan(List.of(1L, 2L, 3L));
        QuestTaskScan second = new QuestTaskScan(List.of(4L, 5L, 6L));
        List<Long> visited = new ArrayList<>();
        int remaining = 4;

        remaining -= first.processBatch(Math.min(8, remaining), () -> true, visited::add);
        remaining -= second.processBatch(Math.min(8, remaining), () -> true, visited::add);

        assertEquals(0, remaining);
        assertEquals(List.of(1L, 2L, 3L, 4L), visited);
        assertTrue(first.finished());
        assertFalse(second.finished());
        assertEquals(2, second.processBatch(8, () -> true, visited::add));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), visited);
    }

    @Test
    void cancelledScanDoesNotUpdateFurtherTasks() {
        QuestTaskScan scan = new QuestTaskScan(List.of(1L, 2L));
        List<Long> visited = new ArrayList<>();
        assertEquals(1, scan.processBatch(1, () -> true, visited::add));

        scan.cancel();

        assertTrue(scan.finished());
        assertEquals(0, scan.processBatch(8, () -> true, visited::add));
        assertEquals(List.of(1L), visited);
    }
}
