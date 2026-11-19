package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.util.PerformanceMonitor.PlanningLatencyPhase;
import com.huanghuang.rsintegration.crafting.CompatibilityProfile.Phase;

import java.util.Locale;

/** 逐切片取生产计数器的差，只统计该请求实际执行过的阶段。 */
final class ReplaySliceTimes {
    enum CompatMode {
        BASE(false, false), GROUP(true, false), SORT(false, true), BOTH(true, true);
        final boolean reuseGroups;
        final boolean skipSort;

        CompatMode(boolean reuseGroups, boolean skipSort) {
            this.reuseGroups = reuseGroups;
            this.skipSort = skipSort;
        }
    }
    private static final PlanningLatencyPhase[] PHASES = {
            PlanningLatencyPhase.HYBRID_CAPTURE, PlanningLatencyPhase.DYNAMIC_ROUTE,
            PlanningLatencyPhase.HYBRID_VALIDATE, PlanningLatencyPhase.HYBRID_MERGE,
            PlanningLatencyPhase.GRAPH_VALIDATE
    };
    private final long[] before = new long[PHASES.length];
    private final long[] longest = new long[PHASES.length];
    private final long[] total = new long[PHASES.length];
    private static final Phase[] COMPAT = Phase.values();
    private final CompatibilityProfile profile;
    private final long[] prior = new long[COMPAT.length];
    private final long[] peak = new long[COMPAT.length];
    long graph;
    long estimate;
    long projection;
    long assembly;

    ReplaySliceTimes(CraftingResolver.TypedPreview job, CompatMode mode) {
        this.profile = mode == null ? null : job.profileCompatibility(mode.reuseGroups, mode.skipSort);
    }

    void begin() {
        for (int i = 0; i < PHASES.length; i++) before[i] = PerformanceMonitor.planningLatency(PHASES[i]).totalNanos();
        if (profile != null) for (Phase phase : COMPAT) prior[phase.ordinal()] = profile.total(phase);
    }

    void end() {
        for (int i = 0; i < PHASES.length; i++) {
            long elapsed = PerformanceMonitor.planningLatency(PHASES[i]).totalNanos() - before[i];
            longest[i] = Math.max(longest[i], elapsed);
            total[i] += elapsed;
        }
        if (profile != null) for (Phase phase : COMPAT) {
            int i = phase.ordinal();
            peak[i] = Math.max(peak[i], profile.total(phase) - prior[i]);
        }
    }

    void print(String label, int run, long callbackMax) {
        if (profile != null) {
            var detail = new StringBuilder();
            for (Phase phase : COMPAT) detail.append(String.format(Locale.ROOT, " %sMs=%.3f %sPeakMs=%.3f",
                    phase, profile.total(phase) / 1e6, phase, peak[phase.ordinal()] / 1e6));
            System.out.printf(Locale.ROOT, "compat_detail_%s run=%d groupHits=%d skippedSorts=%d%s%n",
                    label, run, profile.groupHits, profile.skippedSorts, detail);
        }
        var line = new StringBuilder("slice_" + label + " run=" + run);
        for (int i = 0; i < PHASES.length; i++) line.append(String.format(Locale.ROOT,
                " %s=%.3f", PHASES[i], longest[i] / 1e6));
        System.out.printf(Locale.ROOT, "%s graph=%.3f estimate=%.3f projection=%.3f assembly=%.3f callbackMax=%.3f measuredCallbackTotal=%.3f%n",
                line, graph / 1e6, estimate / 1e6, projection / 1e6, assembly / 1e6,
                callbackMax / 1e6, total[1] / 1e6);
    }
}
