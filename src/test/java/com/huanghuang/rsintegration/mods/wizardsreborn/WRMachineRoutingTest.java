package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WRMachineRoutingTest {
    private static final ResourceLocation KUBE_JS_ID = new ResourceLocation(
            "wizards_reborn", "kjs/ek20w1bbijtq8puldes6qf4md");

    @Test
    void scriptGeneratedIdUsesActualArcaneWorkbenchRecipeClass() {
        assertEquals(WRBatchDelegate.MachineType.ARCANE_WORKBENCH,
                WRBatchDelegate.expectedMachineType(
                        KUBE_JS_ID, ArcaneWorkbenchRecipe.class));
    }

    @Test
    void actualRecipeClassOverridesMisleadingIdFolder() {
        ResourceLocation misleading = new ResourceLocation(
                "wizards_reborn", "arcane_iterator/custom_workbench_recipe");

        assertEquals(WRBatchDelegate.MachineType.ARCANE_WORKBENCH,
                WRBatchDelegate.expectedMachineType(
                        misleading, ArcaneWorkbenchRecipe.class));
    }

    @Test
    void inheritedRecipeUsesRecognizedBaseClass() {
        assertEquals(WRBatchDelegate.MachineType.ARCANE_WORKBENCH,
                WRBatchDelegate.expectedMachineType(
                        KUBE_JS_ID, AddonWorkbenchRecipe.class));
    }

    @Test
    void unknownRecipeClassFallsBackToNativeIdFolder() {
        ResourceLocation nativeId = new ResourceLocation(
                "wizards_reborn", "arcane_iterator/arcanum_lens");

        assertEquals(WRBatchDelegate.MachineType.ARCANE_ITERATOR,
                WRBatchDelegate.expectedMachineType(nativeId, UnknownRecipe.class));
    }

    @Test
    void everySupportedRecipeFamilyMapsToItsPhysicalMachine() {
        assertEquals(WRBatchDelegate.MachineType.WISSEN_CRYSTALLIZER,
                WRBatchDelegate.expectedMachineTypeFromClassName("WissenCrystallizerRecipe"));
        assertEquals(WRBatchDelegate.MachineType.ARCANE_ITERATOR,
                WRBatchDelegate.expectedMachineTypeFromClassName("ArcaneIteratorRecipe"));
        assertEquals(WRBatchDelegate.MachineType.ARCANE_WORKBENCH,
                WRBatchDelegate.expectedMachineTypeFromClassName("ArcaneWorkbenchRecipe"));
        assertEquals(WRBatchDelegate.MachineType.CRYSTAL_RITUAL,
                WRBatchDelegate.expectedMachineTypeFromClassName("CrystalInfusionRecipe"));
        assertEquals(WRBatchDelegate.MachineType.ARCANE_ITERATOR,
                WRBatchDelegate.expectedMachineTypeFromClassName("CrystalRitualRecipe"));
    }

    @Test
    void crystalInfusionDatapackIdUsesCrystalBlock() {
        ResourceLocation recipeId = new ResourceLocation(
                "wizards_reborn", "crystal_infusion/apotheosis_ancient_material");

        assertEquals(WRBatchDelegate.MachineType.CRYSTAL_RITUAL,
                WRBatchDelegate.expectedMachineType(recipeId, UnknownRecipe.class));
    }

    @Test
    void arcaneWorkbenchDatapackIdsAreRecognizedWithoutNativeRecipeClass() {
        assertTrue(WRRecipeHandler.isArcaneWorkbenchRecipeId(
                new ResourceLocation("wizards_reborn", "arcane_workbench/avaritia_infinity_sword")));
        assertFalse(WRRecipeHandler.isArcaneWorkbenchRecipeId(
                new ResourceLocation("wizards_reborn", "crystal_infusion/irons_spellbooks_gold_crown")));
    }

    private static class ArcaneWorkbenchRecipe {}
    private static final class AddonWorkbenchRecipe extends ArcaneWorkbenchRecipe {}
    private static final class UnknownRecipe {}
}
