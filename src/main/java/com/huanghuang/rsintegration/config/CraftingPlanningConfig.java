package com.huanghuang.rsintegration.config;

/** Validated server-side resource limits for asynchronous craft planning. */
public record CraftingPlanningConfig(int workers, int queueCapacity,
                                     int maxSearchStates, int maxMemoizedFailures,
                                     int maxDemandTreeNodes, int pureTimeoutMs,
                                     int typedPreviewTimeoutMs) {
    public static final int MIN_WORKERS = 1;
    public static final int MAX_WORKERS = 8;
    public static final int DEFAULT_WORKERS = Math.max(MIN_WORKERS, Math.min(4,
            Runtime.getRuntime().availableProcessors() / 2));
    public static final int MIN_QUEUE_CAPACITY = 8;
    public static final int MAX_QUEUE_CAPACITY = 1_024;
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    public static final int MIN_SEARCH_STATES = 256;
    public static final int MAX_SEARCH_STATES = 262_144;
    public static final int DEFAULT_SEARCH_STATES = 65_536;
    public static final int MIN_MEMOIZED_FAILURES = 0;
    public static final int MAX_MEMOIZED_FAILURES = 65_536;
    public static final int DEFAULT_MEMOIZED_FAILURES = 8_192;
    public static final int MIN_DEMAND_TREE_NODES = 64;
    public static final int MAX_DEMAND_TREE_NODES = 4_096;
    public static final int DEFAULT_DEMAND_TREE_NODES = 512;
    public static final int MIN_PURE_TIMEOUT_MS = 50;
    public static final int MAX_PURE_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_PURE_TIMEOUT_MS = 500;
    public static final int MIN_TYPED_PREVIEW_TIMEOUT_MS = 10;
    public static final int MAX_TYPED_PREVIEW_TIMEOUT_MS = 500;
    public static final int DEFAULT_TYPED_PREVIEW_TIMEOUT_MS = 400;

    public CraftingPlanningConfig {
        requireRange("workers", workers, MIN_WORKERS, MAX_WORKERS);
        requireRange("queueCapacity", queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY);
        requireRange("maxSearchStates", maxSearchStates, MIN_SEARCH_STATES, MAX_SEARCH_STATES);
        requireRange("maxMemoizedFailures", maxMemoizedFailures,
                MIN_MEMOIZED_FAILURES, MAX_MEMOIZED_FAILURES);
        requireRange("maxDemandTreeNodes", maxDemandTreeNodes,
                MIN_DEMAND_TREE_NODES, MAX_DEMAND_TREE_NODES);
        requireRange("pureTimeoutMs", pureTimeoutMs,
                MIN_PURE_TIMEOUT_MS, MAX_PURE_TIMEOUT_MS);
        requireRange("typedPreviewTimeoutMs", typedPreviewTimeoutMs,
                MIN_TYPED_PREVIEW_TIMEOUT_MS, MAX_TYPED_PREVIEW_TIMEOUT_MS);
    }

    public static CraftingPlanningConfig defaults() {
        return new CraftingPlanningConfig(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY,
                DEFAULT_SEARCH_STATES, DEFAULT_MEMOIZED_FAILURES,
                DEFAULT_DEMAND_TREE_NODES, DEFAULT_PURE_TIMEOUT_MS,
                DEFAULT_TYPED_PREVIEW_TIMEOUT_MS);
    }

    /** Snapshot the currently loaded Forge server config. */
    public static CraftingPlanningConfig load() {
        return new CraftingPlanningConfig(
                RSIntegrationConfig.CRAFTING_PLANNING_WORKERS.get(),
                RSIntegrationConfig.CRAFTING_PLANNING_QUEUE_CAPACITY.get(),
                RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.get(),
                RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES.get(),
                RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.get(),
                RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.get(),
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get());
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || maximum < value) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]");
        }
    }
}
