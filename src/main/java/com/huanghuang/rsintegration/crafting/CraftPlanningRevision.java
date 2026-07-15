package com.huanghuang.rsintegration.crafting;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic server-generation revision for recipe and matcher planning semantics. */
public final class CraftPlanningRevision {
    private static final AtomicLong CURRENT = new AtomicLong(1);

    private CraftPlanningRevision() {}

    public static long current() {
        return CURRENT.get();
    }

    public static long bump() {
        return CURRENT.incrementAndGet();
    }

    public static boolean isCurrent(long revision) {
        return revision == current();
    }
}
