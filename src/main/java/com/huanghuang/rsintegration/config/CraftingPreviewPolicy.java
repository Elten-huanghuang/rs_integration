package com.huanghuang.rsintegration.config;

/** Validated cache and rate-limit policy for craft previews. */
public record CraftingPreviewPolicy(int rateLimitMs, int cacheTtlMs, int cacheMaxEntries) {
    public static final int MIN_RATE_LIMIT_MS = 10;
    public static final int MAX_RATE_LIMIT_MS = 2_000;
    public static final int DEFAULT_RATE_LIMIT_MS = 100;
    public static final int MIN_CACHE_TTL_MS = 50;
    public static final int MAX_CACHE_TTL_MS = 120_000;
    public static final int DEFAULT_CACHE_TTL_MS = 30_000;
    public static final int MIN_CACHE_MAX_ENTRIES = 8;
    public static final int MAX_CACHE_MAX_ENTRIES = 1_024;
    public static final int DEFAULT_CACHE_MAX_ENTRIES = 64;

    public CraftingPreviewPolicy {
        requireRange("rateLimitMs", rateLimitMs, MIN_RATE_LIMIT_MS, MAX_RATE_LIMIT_MS);
        requireRange("cacheTtlMs", cacheTtlMs, MIN_CACHE_TTL_MS, MAX_CACHE_TTL_MS);
        requireRange("cacheMaxEntries", cacheMaxEntries,
                MIN_CACHE_MAX_ENTRIES, MAX_CACHE_MAX_ENTRIES);
    }

    public static CraftingPreviewPolicy defaults() {
        return new CraftingPreviewPolicy(
                DEFAULT_RATE_LIMIT_MS, DEFAULT_CACHE_TTL_MS, DEFAULT_CACHE_MAX_ENTRIES);
    }

    public static CraftingPreviewPolicy load() {
        return new CraftingPreviewPolicy(
                RSIntegrationConfig.CRAFTING_PREVIEW_RATE_LIMIT_MS.get(),
                RSIntegrationConfig.CRAFTING_PLAN_CACHE_TTL_MS.get(),
                RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_ENTRIES.get());
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || maximum < value) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]");
        }
    }
}
