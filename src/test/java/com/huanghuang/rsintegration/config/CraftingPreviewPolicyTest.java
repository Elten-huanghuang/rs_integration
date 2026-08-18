package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPreviewPolicyTest {
    @Test
    void recipeTreeNetworkValidationIsOptIn() {
        assertFalse(RSIntegrationConfig.REQUIRE_RS_NETWORK_FOR_RECIPE_TREE.getDefault());
    }

    @Test
    void legacyDefaultCacheTtlMigratesWithoutOverwritingCustomValues() {
        assertEquals(CraftingPreviewPolicy.DEFAULT_CACHE_TTL_MS,
                RSIntegrationConfig.migratePlanCacheTtlMs(4, 500));
        assertEquals(500, RSIntegrationConfig.migratePlanCacheTtlMs(5, 500));
        assertEquals(4_000, RSIntegrationConfig.migratePlanCacheTtlMs(4, 4_000));
    }

    @Test
    void loadsMinimumAndMaximumValues() {
        CommentedConfig config = defaultConfig();
        setPolicy(config, CraftingPreviewPolicy.MIN_RATE_LIMIT_MS,
                CraftingPreviewPolicy.MIN_CACHE_TTL_MS,
                CraftingPreviewPolicy.MIN_CACHE_MAX_ENTRIES);
        load(config);
        assertEquals(new CraftingPreviewPolicy(
                CraftingPreviewPolicy.MIN_RATE_LIMIT_MS,
                CraftingPreviewPolicy.MIN_CACHE_TTL_MS,
                CraftingPreviewPolicy.MIN_CACHE_MAX_ENTRIES), CraftingPreviewPolicy.load());

        config = defaultConfig();
        setPolicy(config, CraftingPreviewPolicy.MAX_RATE_LIMIT_MS,
                CraftingPreviewPolicy.MAX_CACHE_TTL_MS,
                CraftingPreviewPolicy.MAX_CACHE_MAX_ENTRIES);
        load(config);
        assertEquals(new CraftingPreviewPolicy(
                CraftingPreviewPolicy.MAX_RATE_LIMIT_MS,
                CraftingPreviewPolicy.MAX_CACHE_TTL_MS,
                CraftingPreviewPolicy.MAX_CACHE_MAX_ENTRIES), CraftingPreviewPolicy.load());
    }

    @Test
    void forgeSpecClampsValuesOutsideBounds() {
        CommentedConfig config = defaultConfig();
        setPolicy(config, CraftingPreviewPolicy.MIN_RATE_LIMIT_MS - 1,
                CraftingPreviewPolicy.MAX_CACHE_TTL_MS + 1,
                CraftingPreviewPolicy.MIN_CACHE_MAX_ENTRIES - 1);

        assertTrue(RSIntegrationConfig.SERVER_SPEC.correct(config) != 0);
        load(config);
        assertEquals(new CraftingPreviewPolicy(
                CraftingPreviewPolicy.MIN_RATE_LIMIT_MS,
                CraftingPreviewPolicy.MAX_CACHE_TTL_MS,
                CraftingPreviewPolicy.MIN_CACHE_MAX_ENTRIES), CraftingPreviewPolicy.load());
    }

    @Test
    void rejectsInvalidPolicyValues() {
        CraftingPreviewPolicy defaults = CraftingPreviewPolicy.defaults();
        assertThrows(IllegalArgumentException.class, () -> new CraftingPreviewPolicy(
                CraftingPreviewPolicy.MIN_RATE_LIMIT_MS - 1,
                defaults.cacheTtlMs(), defaults.cacheMaxEntries()));
        assertThrows(IllegalArgumentException.class, () -> new CraftingPreviewPolicy(
                defaults.rateLimitMs(), CraftingPreviewPolicy.MAX_CACHE_TTL_MS + 1,
                defaults.cacheMaxEntries()));
        assertThrows(IllegalArgumentException.class, () -> new CraftingPreviewPolicy(
                defaults.rateLimitMs(), defaults.cacheTtlMs(),
                CraftingPreviewPolicy.MAX_CACHE_MAX_ENTRIES + 1));
    }
    @AfterEach
    void restoreDefaults() {
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

    private static void setPolicy(CommentedConfig config, int rateLimitMs,
                                  int cacheTtlMs, int cacheMaxEntries) {
        config.set(RSIntegrationConfig.CRAFTING_PREVIEW_RATE_LIMIT_MS.getPath(), rateLimitMs);
        config.set(RSIntegrationConfig.CRAFTING_PLAN_CACHE_TTL_MS.getPath(), cacheTtlMs);
        config.set(RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_ENTRIES.getPath(), cacheMaxEntries);
    }
}
