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
    private static final AtomicLong planBuildCount = new AtomicLong();
    private static final AtomicLong totalPlanBuildNanos = new AtomicLong();
    private static final AtomicLong maxPlanBuildNanos = new AtomicLong();
    private static final AtomicLong totalPlanNodes = new AtomicLong();
    private static final AtomicLong maxPlanNodes = new AtomicLong();
    private static final AtomicLong planPacketBytes = new AtomicLong();
    private static final AtomicLong maxPlanPacketBytes = new AtomicLong();
    private static final AtomicLong progressPacketBytes = new AtomicLong();
    private static final AtomicLong maxProgressPacketBytes = new AtomicLong();
    private static final AtomicLong resonanceScannedStacks = new AtomicLong();
    private static final AtomicLong resonanceMatchedStacks = new AtomicLong();

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

    public static void recordPlanBuild(long nanosElapsed, int nodes) {
        planBuildCount.incrementAndGet();
        totalPlanBuildNanos.addAndGet(nanosElapsed);
        maxPlanBuildNanos.updateAndGet(prev -> Math.max(prev, nanosElapsed));
        totalPlanNodes.addAndGet(nodes);
        maxPlanNodes.updateAndGet(prev -> Math.max(prev, nodes));
    }
    public static void recordPlanPacketBytes(int bytes) {
        planPacketBytes.addAndGet(bytes);
        maxPlanPacketBytes.updateAndGet(prev -> Math.max(prev, bytes));
    }
    public static void recordProgressPacketBytes(int bytes) {
        progressPacketBytes.addAndGet(bytes);
        maxProgressPacketBytes.updateAndGet(prev -> Math.max(prev, bytes));
    }
    public static void recordResonanceScan(int scanned, int matched) {
        resonanceScannedStacks.addAndGet(scanned);
        resonanceMatchedStacks.addAndGet(matched);
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
        return "PerformanceMonitor: ticks=" + getTickCount()
             + " avg=" + getAvgTickMicros() + "μs"
             + " max=" + getMaxTickMicros() + "μs"
             + " timeout=" + getResolveTimeouts()
             + " plans=" + planBuildCount.get()
             + " planAvg=" + (planBuildCount.get() > 0 ? totalPlanBuildNanos.get() / planBuildCount.get() / 1000 : 0) + "us"
             + " planMax=" + maxPlanBuildNanos.get() / 1000 + "us"
             + " planNodes=" + (planBuildCount.get() > 0 ? totalPlanNodes.get() / planBuildCount.get() : 0)
             + " planBytes=" + planPacketBytes.get() + "/" + maxPlanPacketBytes.get()
             + " progressBytes=" + progressPacketBytes.get() + "/" + maxProgressPacketBytes.get()
             + " resonanceStacks=" + resonanceScannedStacks.get() + "/" + resonanceMatchedStacks.get()
             + " chains=" + getActiveChainCount();
    }
}
