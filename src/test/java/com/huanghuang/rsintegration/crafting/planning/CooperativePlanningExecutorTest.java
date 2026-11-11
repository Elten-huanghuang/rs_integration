package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CooperativePlanningExecutorTest {
    @Test
    void singleLaneLetsShortJobFinishWhileLongSearchRetainsItsStack() throws Exception {
        var executor = executor(1, 4, 1, 20, 0);
        try {
            AtomicBoolean finish = new AtomicBoolean();
            AtomicInteger starts = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            Future<Long> longJob = executor.submit(() -> {
                starts.incrementAndGet();
                long localSteps = 0L;
                ThreadLocal<String> context = ThreadLocal.withInitial(() -> "preserved");
                started.countDown();
                while (!finish.get()) {
                    localSteps++;
                    PlanningThreadContext.throwIfCancelled();
                    assertEquals("preserved", context.get());
                }
                return localSteps;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("short", executor.submit(() -> "short").get(2, TimeUnit.SECONDS));
            assertFalse(longJob.isDone());
            finish.set(true);
            assertTrue(longJob.get(2, TimeUnit.SECONDS) > 0L);
            assertEquals(1, starts.get(), "续算不得重新进入搜索函数");
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test
    void cancellingPausedJobReleasesItsThreadAndCapacity() throws Exception {
        var executor = executor(1, 1, 1, 500, 0);
        try {
            Future<?> task = executor.submit(() -> {
                while (true) PlanningThreadContext.throwIfCancelled();
            });
            await(() -> executor.queuedCount() == 1 && executor.getActiveCount() == 0);
            assertTrue(task.cancel(true));
            assertThrows(CancellationException.class, task::get);
            await(() -> executor.queuedCount() == 0);
            assertEquals(42, executor.submit(() -> 42).get(2, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test
    void backgroundDeadlineIsSeparateFromLegacySynchronousTimeout() throws Exception {
        var executor = executor(1, 1, 1, 20, 0);
        try {
            assertEquals(Long.MAX_VALUE, executor.submit(() -> new PlanningSession(1, 1).deadlineNanos())
                    .get(2, TimeUnit.SECONDS));
            assertNotEquals(Long.MAX_VALUE, new PlanningSession(1, 1).deadlineNanos());
        } finally { executor.shutdownNow(); }
        var bounded = executor(1, 1, 1, 20, 50);
        try {
            assertNotEquals(Long.MAX_VALUE, bounded.submit(() -> new PlanningSession(1, 1).deadlineNanos())
                    .get(2, TimeUnit.SECONDS));
        } finally { bounded.shutdownNow(); }
    }

    @Test
    void activeComputationsNeverExceedConfiguredLanes() throws Exception {
        var executor = executor(2, 6, 1, 10, 0);
        AtomicBoolean finish = new AtomicBoolean();
        try {
            CountDownLatch allStarted = new CountDownLatch(8);
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                allStarted.countDown();
                while (!finish.get()) {
                    assertTrue(executor.getActiveCount() <= 2);
                    PlanningThreadContext.throwIfCancelled();
                }
            }));
            assertTrue(allStarted.await(2, TimeUnit.SECONDS));
            finish.set(true);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            for (Future<?> task : tasks) task.get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    private static CooperativePlanningExecutor executor(int workers, int queue, int slice, int period, int timeout) {
        return new CooperativePlanningExecutor(workers, queue, slice, period, timeout, work -> {
            Thread thread = new Thread(work, "planning-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
}
