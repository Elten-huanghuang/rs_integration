package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlanningConfigTest {
    @Test
    void fallbackDefaultsMatchForgeSpecDefaults() {
        assertEquals(RSIntegrationConfig.DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS,
                RSIntegrationConfig.CRAFTING_RESOLVE_TIMEOUT_MS.getDefault());
        assertEquals(RSIntegrationConfig.DEFAULT_CRAFTING_MAX_ENSURE_CALLS,
                RSIntegrationConfig.CRAFTING_MAX_ENSURE_CALLS.getDefault());
        assertEquals(CraftingPlanningConfig.defaults(), new CraftingPlanningConfig(
                RSIntegrationConfig.CRAFTING_PLANNING_WORKERS.getDefault(),
                RSIntegrationConfig.CRAFTING_PLANNING_QUEUE_CAPACITY.getDefault(),
                RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.getDefault(),
                RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES.getDefault(),
                RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.getDefault(),
                RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.getDefault(),
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.getDefault()));
    }

    @Test
    void loadsMinimumValuesFromServerConfig() {
        CommentedConfig config = defaultConfig();
        setPlanningValues(config, CraftingPlanningConfig.MIN_WORKERS,
                CraftingPlanningConfig.MIN_QUEUE_CAPACITY,
                CraftingPlanningConfig.MIN_SEARCH_STATES,
                CraftingPlanningConfig.MIN_MEMOIZED_FAILURES,
                CraftingPlanningConfig.MIN_DEMAND_TREE_NODES,
                CraftingPlanningConfig.MIN_PURE_TIMEOUT_MS,
                CraftingPlanningConfig.MIN_TYPED_PREVIEW_TIMEOUT_MS);
        load(config);

        assertEquals(new CraftingPlanningConfig(
                CraftingPlanningConfig.MIN_WORKERS,
                CraftingPlanningConfig.MIN_QUEUE_CAPACITY,
                CraftingPlanningConfig.MIN_SEARCH_STATES,
                CraftingPlanningConfig.MIN_MEMOIZED_FAILURES,
                CraftingPlanningConfig.MIN_DEMAND_TREE_NODES,
                CraftingPlanningConfig.MIN_PURE_TIMEOUT_MS,
                CraftingPlanningConfig.MIN_TYPED_PREVIEW_TIMEOUT_MS), CraftingPlanningConfig.load());
    }

    @Test
    void loadsMaximumValuesFromServerConfig() {
        CommentedConfig config = defaultConfig();
        setPlanningValues(config, CraftingPlanningConfig.MAX_WORKERS,
                CraftingPlanningConfig.MAX_QUEUE_CAPACITY,
                CraftingPlanningConfig.MAX_SEARCH_STATES,
                CraftingPlanningConfig.MAX_MEMOIZED_FAILURES,
                CraftingPlanningConfig.MAX_DEMAND_TREE_NODES,
                CraftingPlanningConfig.MAX_PURE_TIMEOUT_MS,
                CraftingPlanningConfig.MAX_TYPED_PREVIEW_TIMEOUT_MS);
        load(config);

        assertEquals(new CraftingPlanningConfig(
                CraftingPlanningConfig.MAX_WORKERS,
                CraftingPlanningConfig.MAX_QUEUE_CAPACITY,
                CraftingPlanningConfig.MAX_SEARCH_STATES,
                CraftingPlanningConfig.MAX_MEMOIZED_FAILURES,
                CraftingPlanningConfig.MAX_DEMAND_TREE_NODES,
                CraftingPlanningConfig.MAX_PURE_TIMEOUT_MS,
                CraftingPlanningConfig.MAX_TYPED_PREVIEW_TIMEOUT_MS), CraftingPlanningConfig.load());
    }

    @Test
    void forgeSpecClampsValuesOutsideDeclaredBounds() {
        CommentedConfig config = defaultConfig();
        setPlanningValues(config,
                CraftingPlanningConfig.MIN_WORKERS - 1,
                CraftingPlanningConfig.MAX_QUEUE_CAPACITY + 1,
                CraftingPlanningConfig.MIN_SEARCH_STATES - 1,
                CraftingPlanningConfig.MAX_MEMOIZED_FAILURES + 1,
                CraftingPlanningConfig.MAX_DEMAND_TREE_NODES + 1,
                CraftingPlanningConfig.MIN_PURE_TIMEOUT_MS - 1,
                CraftingPlanningConfig.MAX_TYPED_PREVIEW_TIMEOUT_MS + 1);

        assertTrue(RSIntegrationConfig.SERVER_SPEC.correct(config) != 0);
        load(config);
        assertEquals(new CraftingPlanningConfig(
                CraftingPlanningConfig.MIN_WORKERS,
                CraftingPlanningConfig.MAX_QUEUE_CAPACITY,
                CraftingPlanningConfig.MIN_SEARCH_STATES,
                CraftingPlanningConfig.MAX_MEMOIZED_FAILURES,
                CraftingPlanningConfig.MAX_DEMAND_TREE_NODES,
                CraftingPlanningConfig.MIN_PURE_TIMEOUT_MS,
                CraftingPlanningConfig.MAX_TYPED_PREVIEW_TIMEOUT_MS), CraftingPlanningConfig.load());
    }

    @Test
    void valueObjectRejectsWorkerAndQueueValuesOutsideBounds() {
        CraftingPlanningConfig defaults = CraftingPlanningConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new CraftingPlanningConfig(
                CraftingPlanningConfig.MIN_WORKERS - 1, defaults.queueCapacity(),
                defaults.maxSearchStates(), defaults.maxMemoizedFailures(),
                defaults.maxDemandTreeNodes(), defaults.pureTimeoutMs(),
                defaults.typedPreviewTimeoutMs()));
        assertThrows(IllegalArgumentException.class, () -> new CraftingPlanningConfig(
                defaults.workers(), CraftingPlanningConfig.MAX_QUEUE_CAPACITY + 1,
                defaults.maxSearchStates(), defaults.maxMemoizedFailures(),
                defaults.maxDemandTreeNodes(), defaults.pureTimeoutMs(),
                defaults.typedPreviewTimeoutMs()));
    }

    @Test
    void valueObjectRejectsSearchValuesOutsideBounds() {
        CraftingPlanningConfig defaults = CraftingPlanningConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new CraftingPlanningConfig(
                defaults.workers(), defaults.queueCapacity(),
                CraftingPlanningConfig.MIN_SEARCH_STATES - 1, defaults.maxMemoizedFailures(),
                defaults.maxDemandTreeNodes(), defaults.pureTimeoutMs(),
                defaults.typedPreviewTimeoutMs()));
        assertThrows(IllegalArgumentException.class, () -> new CraftingPlanningConfig(
                defaults.workers(), defaults.queueCapacity(), defaults.maxSearchStates(),
                CraftingPlanningConfig.MAX_MEMOIZED_FAILURES + 1,
                defaults.maxDemandTreeNodes(), defaults.pureTimeoutMs(),
                defaults.typedPreviewTimeoutMs()));
        assertThrows(IllegalArgumentException.class, () -> new CraftingPlanningConfig(
                defaults.workers(), defaults.queueCapacity(), defaults.maxSearchStates(),
                defaults.maxMemoizedFailures(), CraftingPlanningConfig.MIN_DEMAND_TREE_NODES - 1,
                defaults.pureTimeoutMs(), defaults.typedPreviewTimeoutMs()));
    }

    @AfterEach
    void restoreDefaultServerConfig() {
        load(defaultConfig());
    }

    private static CommentedConfig defaultConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        return config;
    }

    private static void load(CommentedConfig config) {
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    private static void setPlanningValues(CommentedConfig config, int workers, int queueCapacity,
                                          int maxSearchStates, int maxMemoizedFailures,
                                          int maxDemandTreeNodes, int pureTimeoutMs,
                                          int typedPreviewTimeoutMs) {
        config.set(RSIntegrationConfig.CRAFTING_PLANNING_WORKERS.getPath(), workers);
        config.set(RSIntegrationConfig.CRAFTING_PLANNING_QUEUE_CAPACITY.getPath(), queueCapacity);
        config.set(RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.getPath(), maxSearchStates);
        config.set(RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES.getPath(),
                maxMemoizedFailures);
        config.set(RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.getPath(),
                maxDemandTreeNodes);
        config.set(RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.getPath(), pureTimeoutMs);
        config.set(RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.getPath(),
                typedPreviewTimeoutMs);
    }
}
