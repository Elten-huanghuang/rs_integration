package com.huanghuang.rsintegration.crafting;

/** Times out only after a continuous period without meaningful progress. */
final class ProgressWatchdog {
    private final int timeoutTicks;
    private int idleTicks;

    ProgressWatchdog(int timeoutTicks) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        this.timeoutTicks = timeoutTicks;
    }

    boolean tick() {
        return ++idleTicks > timeoutTicks;
    }

    void markProgress() {
        idleTicks = 0;
    }

    int timeoutTicks() {
        return timeoutTicks;
    }

    int idleTicks() {
        return idleTicks;
    }
}
