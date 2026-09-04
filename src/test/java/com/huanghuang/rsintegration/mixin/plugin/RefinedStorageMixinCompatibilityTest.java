package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resonanceMalumMixinsRequireRefinedStorageDiskItem() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        boolean hasResonanceBackend = RSIntegrationMixinPlugin.hasResonanceBackend();

        assertEquals(hasResonanceBackend, plugin.shouldApplyMixin(
                "com.sammy.malum.core.handlers.TouchOfDarknessHandler",
                "com.huanghuang.rsintegration.mixin.malum.TouchOfDarknessHandlerMixin"));
        assertEquals(hasResonanceBackend, plugin.shouldApplyMixin(
                "com.sammy.malum.common.block.curiosities.weeping_well.VoidConduitBlockEntity",
                "com.huanghuang.rsintegration.mixin.malum.VoidConduitBlockEntityMixin"));
    }
}
