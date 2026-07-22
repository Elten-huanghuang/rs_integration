package com.huanghuang.rsintegration.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSamplerTest {
    @Test
    void samplesEachKeyIndependently() {
        LogSampler sampler = new LogSampler(60_000);
        assertTrue(sampler.allow("recipe-a"));
        assertFalse(sampler.allow("recipe-a"));
        assertTrue(sampler.allow("recipe-b"));
    }

    @Test
    void concurrentCallsOnlyAllowOneEvent() throws Exception {
        LogSampler sampler = new LogSampler(60_000);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sampler.allow("shared-key");
                }));
            }
            ready.await();
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) allowed++;
            }
            assertEquals(1, allowed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evictsLeastRecentlyUsedKeyAtCapacity() {
        LogSampler sampler = new LogSampler(60_000, 2);
        assertTrue(sampler.allow("recipe-a"));
        assertTrue(sampler.allow("recipe-b"));
        assertFalse(sampler.allow("recipe-a"));
        assertTrue(sampler.allow("recipe-c"));

        assertFalse(sampler.allow("recipe-a"));
        assertTrue(sampler.allow("recipe-b"));
    }
}
