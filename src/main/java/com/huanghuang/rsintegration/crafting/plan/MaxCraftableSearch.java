package com.huanghuang.rsintegration.crafting.plan;

import java.util.OptionalInt;

/** Monotonic search for the greatest repeat count accepted by plan validation. */
public final class MaxCraftableSearch {
    public enum Verdict { FEASIBLE, INFEASIBLE, UNKNOWN }

    private final int limit;
    private int feasible;
    private int infeasible;
    private int pendingProbe = -1;
    private boolean unknown;

    public MaxCraftableSearch(int limit) {
        this.limit = Math.max(1, limit);
        this.feasible = 0;
        this.infeasible = this.limit + 1;
    }

    public MaxCraftableSearch(int limit, int currentCount, boolean currentFeasible) {
        this.limit = Math.max(1, limit);
        int current = Math.max(1, Math.min(currentCount, this.limit));
        this.feasible = currentFeasible ? current : 0;
        this.infeasible = currentFeasible ? this.limit + 1 : current;
    }

    public OptionalInt nextProbe() {
        if (unknown) return OptionalInt.empty();
        if (pendingProbe >= 0) return OptionalInt.of(pendingProbe);
        if (infeasible - feasible <= 1) return OptionalInt.empty();
        pendingProbe = feasible + (infeasible - feasible) / 2;
        return OptionalInt.of(pendingProbe);
    }

    public void accept(int repeatCount, boolean accepted) {
        accept(repeatCount, accepted ? Verdict.FEASIBLE : Verdict.INFEASIBLE);
    }

    public void accept(int repeatCount, Verdict verdict) {
        if (repeatCount != pendingProbe) return;
        if (verdict == Verdict.UNKNOWN) unknown = true;
        else if (verdict == Verdict.FEASIBLE) feasible = repeatCount;
        else infeasible = repeatCount;
        pendingProbe = -1;
    }

    public boolean isUnknown() {
        return unknown;
    }

    public int result() {
        if (unknown) throw new IllegalStateException("maximum search is unknown");
        if (infeasible - feasible > 1 || pendingProbe >= 0) {
            throw new IllegalStateException("maximum search is not complete");
        }
        return feasible;
    }
}
