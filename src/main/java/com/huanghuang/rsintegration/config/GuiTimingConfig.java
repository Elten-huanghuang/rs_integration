package com.huanghuang.rsintegration.config;

/** Validated server/client timing policy for remote GUI interactions. */
public record GuiTimingConfig(int openRateLimitMs, int navigationTimeoutMs) {
    public static final int MIN_OPEN_RATE_LIMIT_MS = 50;
    public static final int MAX_OPEN_RATE_LIMIT_MS = 5_000;
    public static final int DEFAULT_OPEN_RATE_LIMIT_MS = 500;
    public static final int MIN_NAVIGATION_TIMEOUT_MS = 1_000;
    public static final int MAX_NAVIGATION_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_NAVIGATION_TIMEOUT_MS = 5_000;

    public GuiTimingConfig {
        requireRange("openRateLimitMs", openRateLimitMs,
                MIN_OPEN_RATE_LIMIT_MS, MAX_OPEN_RATE_LIMIT_MS);
        requireRange("navigationTimeoutMs", navigationTimeoutMs,
                MIN_NAVIGATION_TIMEOUT_MS, MAX_NAVIGATION_TIMEOUT_MS);
    }

    public static GuiTimingConfig defaults() {
        return new GuiTimingConfig(DEFAULT_OPEN_RATE_LIMIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
    }

    public static GuiTimingConfig load() {
        return new GuiTimingConfig(
                RSIntegrationConfig.GUI_OPEN_RATE_LIMIT_MS.get(),
                RSIntegrationConfig.SIDE_PANEL_NAVIGATION_TIMEOUT_MS.get());
    }

    public static int loadOpenRateLimitMs() {
        int value = RSIntegrationConfig.GUI_OPEN_RATE_LIMIT_MS.get();
        requireRange("openRateLimitMs", value, MIN_OPEN_RATE_LIMIT_MS, MAX_OPEN_RATE_LIMIT_MS);
        return value;
    }

    public static int loadNavigationTimeoutMs() {
        int value = RSIntegrationConfig.SIDE_PANEL_NAVIGATION_TIMEOUT_MS.get();
        requireRange("navigationTimeoutMs", value,
                MIN_NAVIGATION_TIMEOUT_MS, MAX_NAVIGATION_TIMEOUT_MS);
        return value;
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || maximum < value) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]");
        }
    }
}
