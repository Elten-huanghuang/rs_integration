package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConcurrencyEligibilityTest {

    @Test
    void knownWorldCaptureAndRitualTypesAreAllowed() {
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                capability("malum_spirit_crucible", "recipe.Any", false).outputOwnership());
        assertEquals(BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                capability("malum", "recipe.SpiritInfusionRecipe", false).sideEffects());
        assertEquals(0,
                capability("malum", "recipe.SpiritInfusionRecipe", false).supportOffsets().size());
        assertEquals(BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                capability("goety", "recipe.Any", false).sideEffects());
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.DELEGATE_RESULT,
                capability("forbidden_arcanus", "recipe.Ritual", false).outputOwnership());
    }

    @Test
    void mixedDelegatesAllowOnlyProvenRecipeSubtypes() {
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "eidolon", "pkg.WorktableRecipe", false)));
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                capability("eidolon", "pkg.ItemRitualRecipe", false).outputOwnership());
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "wizards_reborn", "pkg.CrystalInfusionRecipe", false)));
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "wizards_reborn", "pkg.ArcaneIteratorRecipe", false)));
        assertEquals(BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                capability("wizards_reborn", "pkg.CrystalRitualRecipe", false).sideEffects());
    }

    @Test
    void onlyEmbersInferIsAllowed() {
        assertEquals(BatchConcurrencyCapabilities.SideEffects.INFER,
                capability("embers_alchemy", "pkg.AlchemyRecipe", true).sideEffects());
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "goety", "pkg.RitualRecipe", true)));
    }

    @Test
    void explicitDenyTypesRemainUnknown() {
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "crockpot", "pkg.CrockPotRecipe", false)));
        assertNull(GraphConcurrencyEligibility.capabilities(new GraphConcurrencyEligibility.Context(
                "immortalers_delight", "pkg.CoolerRecipe", false)));
    }

    @Test
    void oneSafeMachineQueuesMultipleOperationsSequentially() {
        assertTrue(GraphConcurrencyEligibility.shouldQueueOperations(3, 1, 1, true));
        assertFalse(GraphConcurrencyEligibility.shouldQueueOperations(1, 1, 1, true));
        assertFalse(GraphConcurrencyEligibility.shouldQueueOperations(3, 1, 1, false));
    }

    private static BatchConcurrencyCapabilities capability(
            String modType, String recipeClass, boolean infer) {
        return GraphConcurrencyEligibility.capabilities(
                new GraphConcurrencyEligibility.Context(modType, recipeClass, infer));
    }
}
