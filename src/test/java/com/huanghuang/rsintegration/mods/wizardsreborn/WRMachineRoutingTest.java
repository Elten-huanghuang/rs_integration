package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRRecipeHandler;
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

    @Test
    void playerWissenTransferOnlyFillsTheMachineDeficitAndCapacity() {
        assertEquals(100, WRWissenSupport.transferAmount(0, 100, 150, 10_000));
        assertEquals(60, WRWissenSupport.transferAmount(40, 100, 80, 10_000));
        assertEquals(10, WRWissenSupport.transferAmount(9_990, 10_000, 100, 10_000));
        assertEquals(0, WRWissenSupport.transferAmount(100, 100, 500, 10_000));
    }

    @Test
    void insufficientWissenWaitsInsteadOfFailingPermanently() {
        IBatchDelegate.PreparationResult result =
                WRBatchDelegate.waitingForWissen(615, 1_000);

        assertEquals(IBatchDelegate.PreparationState.RETRY, result.state());
        assertTrue(result.detail().contains("615"));
        assertTrue(result.detail().contains("1,000"));
        assertTrue(result.userMessage() != null);
    }

    @Test
    void insufficientExperienceAlsoUsesGenericWaitingSemantics() {
        IBatchDelegate.PreparationResult result =
                WRBatchDelegate.waitingForExperience(3, 5);

        assertEquals(IBatchDelegate.PreparationState.RETRY, result.state());
        assertTrue(result.detail().contains("3"));
        assertTrue(result.detail().contains("5"));
        assertTrue(result.userMessage() != null);
    }

    private static class ArcaneWorkbenchRecipe {}
    private static final class AddonWorkbenchRecipe extends ArcaneWorkbenchRecipe {}
    private static final class UnknownRecipe {}
}
