package com.huanghuang.rsintegration.mods.rs;

/** Retains a populated snapshot while a replacement view is briefly empty. */
final class TransientEmptySnapshotGuard {
    private Object source;
    private long emptySinceNanos = Long.MIN_VALUE;

    boolean shouldRetain(Object source, boolean incomingEmpty, boolean hasRetained,
                         long nowNanos, long graceNanos) {
        if (!incomingEmpty || !hasRetained || graceNanos <= 0L) {
            reset();
            return false;
        }
        if (this.source != source || emptySinceNanos == Long.MIN_VALUE) {
            this.source = source;
            emptySinceNanos = nowNanos;
            return true;
        }
        long elapsed = nowNanos - emptySinceNanos;
        return elapsed < 0L || elapsed < graceNanos;
    }

    void reset() {
        source = null;
        emptySinceNanos = Long.MIN_VALUE;
    }
}
