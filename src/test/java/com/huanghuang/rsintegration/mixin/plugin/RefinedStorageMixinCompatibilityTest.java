package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedStorageMixinCompatibilityTest {
    @Test
    void identifiesOnlyTheTwoMixinsAlsoOwnedByYzzzFix() {
        assertTrue(RSIntegrationMixinPlugin.isYzzzOwnedRefinedStorageMixin(
                "com.huanghuang.rsintegration.mixin.refinedstorage.CraftingGridBehaviorMixin"));
        assertTrue(RSIntegrationMixinPlugin.isYzzzOwnedRefinedStorageMixin(
                "com.huanghuang.rsintegration.mixin.refinedstorage.IngredientTrackerMixin"));
        assertFalse(RSIntegrationMixinPlugin.isYzzzOwnedRefinedStorageMixin(
                "com.huanghuang.rsintegration.mixin.refinedstorage.CraftingTaskAccessor"));
    }
}
