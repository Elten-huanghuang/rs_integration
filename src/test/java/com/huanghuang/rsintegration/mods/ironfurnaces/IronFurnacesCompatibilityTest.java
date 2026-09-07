package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IronFurnacesCompatibilityTest extends BootstrapTest {

    @Test
    void smallAndWindowedOrdersUseBothFactoryWorkersWithinLaneCapacity() {
        assertEquals(1, IronFurnacesBatchDelegate.parallelWorkerBatchSize(2, 2, 6));
        assertEquals(2, IronFurnacesBatchDelegate.parallelWorkerBatchSize(3, 2, 6));
        assertEquals(6, IronFurnacesBatchDelegate.parallelWorkerBatchSize(32, 2, 6));
        assertEquals(16, IronFurnacesBatchDelegate.parallelWorkerBatchSize(32, 2, 384));
        assertEquals(1, IronFurnacesBatchDelegate.parallelWorkerBatchSize(32, 2, 1));
    }

    @Test
    void factoryBatchSplitsAStackAcrossSixLanes() {
        var lanes = IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(new ItemStack(Items.IRON_ORE, 6)));

        assertEquals(6, lanes.size());
        assertTrue(lanes.stream().allMatch(stack -> stack.is(Items.IRON_ORE) && stack.getCount() == 1));
    }

    @Test
    void factoryBatchUsesFiveLanesForFiveOperations() {
        var lanes = IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(new ItemStack(Items.CLAY_BALL, 5)));

        assertEquals(5, lanes.size());
        assertTrue(lanes.stream().allMatch(stack -> stack.is(Items.CLAY_BALL)
                && stack.getCount() == 1));
    }

    @Test
    void rainbowFactoryBatchFillsSixFullLanes() {
        var lanes = IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64)), 64);

        assertEquals(6, lanes.size());
        assertTrue(lanes.stream().allMatch(stack -> stack.is(Items.CLAY_BALL)
                && stack.getCount() == 64));
        assertEquals(6, IronFurnacesBatchDelegate.requiredFactoryLanes(384, 64));
        assertEquals(2, IronFurnacesBatchDelegate.requiredFactoryLanes(65, 64));
        assertEquals(384, IronFurnacesBatchDelegate.plannedBatchSize(true, true, 400, 64));
        assertEquals(6, IronFurnacesBatchDelegate.plannedBatchSize(true, false, 400, 64));
        assertEquals(64, IronFurnacesBatchDelegate.plannedBatchSize(false, true, 400, 64));
    }

    @Test
    void rainbowFactoryBatchUsesFullLaneBeforeRemainderLane() {
        var lanes = IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(new ItemStack(Items.CLAY_BALL, 65)), 64);

        assertEquals(2, lanes.size());
        assertEquals(64, lanes.get(0).getCount());
        assertEquals(1, lanes.get(1).getCount());
    }

    @Test
    void oversizedRainbowBatchesSplitByPhysicalMachineCapacity() {
        assertEquals(java.util.List.of(64, 1),
                IronFurnacesBatchDelegate.physicalBatchSizes(65, 64));
        assertEquals(java.util.List.of(384, 1),
                IronFurnacesBatchDelegate.physicalBatchSizes(385, 384));
        assertEquals(2, IronFurnacesBatchDelegate.physicalCycleCount(65, 64));
        assertEquals(2, IronFurnacesBatchDelegate.physicalCycleCount(385, 384));
    }

    @Test
    void graphBatchesShareOperationsAcrossWorkersWithinPhysicalCapacity() {
        assertEquals(129, IronFurnacesBatchDelegate.parallelWorkerBatchSize(129, 1, 384));
        assertEquals(65, IronFurnacesBatchDelegate.parallelWorkerBatchSize(129, 2, 384));
        assertEquals(64, IronFurnacesBatchDelegate.parallelWorkerBatchSize(129, 1, 64));
        assertEquals(1, IronFurnacesBatchDelegate.parallelWorkerBatchSize(0, 1, 64));
        assertEquals(1, IronFurnacesBatchDelegate.parallelWorkerBatchSize(129, 0, 64));
        assertEquals(1, IronFurnacesBatchDelegate.parallelWorkerBatchSize(129, 1, 0));
    }

    @Test
    void rainbowFactorySplitsA129OperationGraphBatchAcrossPhysicalLanes() {
        var lanes = IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 1)), 64);

        assertEquals(java.util.List.of(64, 64, 1),
                lanes.stream().map(ItemStack::getCount).toList());
    }

    @Test
    void rainbowBatchMergesAFullStackForOneSmeltCycle() {
        ItemStack merged = IronFurnacesBatchDelegate.mergeBatchMaterials(
                java.util.List.of(new ItemStack(Items.CLAY_BALL, 32),
                        new ItemStack(Items.CLAY_BALL, 32)), 64);

        assertTrue(merged.is(Items.CLAY_BALL));
        assertEquals(64, merged.getCount());
        assertTrue(IronFurnacesBatchDelegate.mergeBatchMaterials(
                java.util.List.of(new ItemStack(Items.CLAY_BALL, 64),
                        new ItemStack(Items.CLAY_BALL, 1)), 64).isEmpty());
    }

    @Test
    void factoryBatchRejectsInvalidLaneCounts() {
        assertTrue(IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(ItemStack.EMPTY)).isEmpty());
        assertTrue(IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(new ItemStack(Items.IRON_ORE, 7))).isEmpty());
        assertTrue(IronFurnacesBatchDelegate.splitFactoryMaterials(
                java.util.List.of(new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 64),
                        new ItemStack(Items.IRON_ORE, 1)), 64).isEmpty());
    }

    @Test
    void rainbowFuelUsesIronFurnacesScaledBurnTicks() {
        assertEquals(320, IronFurnacesBatchDelegate.effectiveFuelTicks(1600, 40, 1, 1));
        assertEquals(640, IronFurnacesBatchDelegate.effectiveFuelTicks(1600, 40, 2, 1));
        assertEquals(160, IronFurnacesBatchDelegate.effectiveFuelTicks(1600, 40, 1, 2));
        assertEquals(0, IronFurnacesBatchDelegate.effectiveFuelTicks(100, 1, 1, 1));
        assertEquals(80, IronFurnacesBatchDelegate.requiredFuelTicks(40, 2, 0));
        assertEquals(30, IronFurnacesBatchDelegate.requiredFuelTicks(40, 2, 50));
    }

    @Test
    void bindingModesOnlyMatchTheirCorrespondingCookingType() {
        ModType furnace = register("ironfurnaces_furnace");
        ModType blast = register("ironfurnaces_blast_furnace");
        ModType smoker = register("ironfurnaces_smoker");
        ModType vanillaFurnace = register("vanilla_furnace");
        ModType vanillaBlast = register("vanilla_blast_furnace");
        ModType vanillaSmoker = register("vanilla_smoker");

        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaFurnace, furnace));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(vanillaBlast, furnace));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(vanillaSmoker, furnace));
        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaBlast, blast));
        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaSmoker, smoker));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(register("goety"), furnace));
    }

    @Test
    void concreteCookingRecipesMapToMatchingRecipeTypes() {
        assertEquals(RecipeType.SMELTING, IronFurnacesBatchDelegate.recipeType(
                new SmeltingRecipe(new ResourceLocation("test", "smelting"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 200)));
        assertEquals(RecipeType.BLASTING, IronFurnacesBatchDelegate.recipeType(
                new BlastingRecipe(new ResourceLocation("test", "blasting"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 100)));
        assertEquals(RecipeType.SMOKING, IronFurnacesBatchDelegate.recipeType(
                new SmokingRecipe(new ResourceLocation("test", "smoking"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 100)));
    }

    @Test
    void bindingPrefixMigrationPreservesMachineIdentity() {
        String original = "ironfurnaces_furnace||block.ironfurnaces.diamond_furnace";

        assertEquals("ironfurnaces_blast_furnace||block.ironfurnaces.diamond_furnace",
                BindingStorage.replaceBlockKeyPrefix(original, IronFurnacesRSModule.BLAST_TYPE_ID));
        assertTrue(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(original));
        assertTrue(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(
                "ironfurnaces_smoker||block.ironfurnaces.diamond_furnace"));
        assertFalse(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(
                "vanilla_furnace||block.minecraft.furnace"));
    }

    @Test
    void cookingRecipeTypesMapToBindingPrefixes() {
        assertEquals(IronFurnacesRSModule.TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.SMELTING));
        assertEquals(IronFurnacesRSModule.BLAST_TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.BLASTING));
        assertEquals(IronFurnacesRSModule.SMOKER_TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.SMOKING));
        assertNull(IronFurnaceBindingUpdater.prefixFor(RecipeType.CAMPFIRE_COOKING));
    }

    private static ModType register(String id) {
        return ModType.register(id, new String[0], new String[0], new String[0], () -> null);
    }
}
