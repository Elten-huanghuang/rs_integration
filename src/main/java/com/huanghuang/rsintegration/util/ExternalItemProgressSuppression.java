package com.huanghuang.rsintegration.util;

/** Per-call marker for external insertions that intentionally destroy rather than store items. */
public final class ExternalItemProgressSuppression {

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> false);

    private ExternalItemProgressSuppression() {}

    public static void beginOperation() {
        SUPPRESSED.set(false);
    }

    public static void suppress() {
        SUPPRESSED.set(true);
    }

    public static boolean consume() {
        boolean suppressed = SUPPRESSED.get();
        SUPPRESSED.remove();
        return suppressed;
    }
}
