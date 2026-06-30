package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.crafting.AsyncCraftManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight performance instrumentation.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Tick timing statistics (avg/max execution time per tick)</li>
 *   <li>Resolution timeout count ({@code MAX_RESOLVE_NANOS} exceeded)</li>
 *   <li>Active {@code AsyncCraftChain} count snapshot</li>
 * </ul>
 *
 * <p>All counters are atomic — safe to call from any thread.</p>
 */
public final class PerformanceMonitor {

    private static final AtomicLong resolveTimeouts = new AtomicLong();
    private static final AtomicLong tickCount = new AtomicLong();
    private static final AtomicLong totalTickNanos = new AtomicLong();
    private static final AtomicLong maxTickNanos = new AtomicLong();

    private PerformanceMonitor() {}

    /** Record a resolution that hit the deadline. */
    public static void recordResolveTimeout() {
        resolveTimeouts.incrementAndGet();
    }

    /** Record one tick's execution time. Called from server tick handler. */
    public static void recordTick(long nanosElapsed) {
        tickCount.incrementAndGet();
        totalTickNanos.addAndGet(nanosElapsed);
        maxTickNanos.updateAndGet(prev -> Math.max(prev, nanosElapsed));
    }

    // ── Queries ────────────────────────────────────────────────────

    public static long getResolveTimeouts() {
        return resolveTimeouts.get();
    }

    public static long getTickCount() {
        return tickCount.get();
    }

    public static long getAvgTickMicros() {
        long n = tickCount.get();
        return n > 0 ? totalTickNanos.get() / n / 1000 : 0;
    }

    public static long getMaxTickMicros() {
        return maxTickNanos.get() / 1000;
    }

    public static int getActiveChainCount() {
        return AsyncCraftManager.getInstance().getActiveChainCount();
    }

    /** Snapshot for debug commands / logs. */
    public static String snapshot() {
        return String.format(
            "PerformanceMonitor: ticks=%d avg=%dμs max=%dμs timeout=%d chains=%d",
            getTickCount(), getAvgTickMicros(), getMaxTickMicros(),
            getResolveTimeouts(), getActiveChainCount()
        );
    }
}
