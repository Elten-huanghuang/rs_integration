package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.vanilla.VanillaFurnaceFuelPolicy;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IronFurnacesCompatibilityTest extends BootstrapTest {

    @Test
    void nativePhysicalBatchCanExceedTheGenericDispatchWindow() {
        assertTrue(new IronFurnacesBatchDelegate().expandsFlatBatchOperationLimit());
        assertEquals(32, IronFurnacesBatchDelegate.expandedFlatBatchOperationLimit(32, 1));
        assertEquals(32, IronFurnacesBatchDelegate.expandedFlatBatchOperationLimit(32, 6));
        assertEquals(64, IronFurnacesBatchDelegate.expandedFlatBatchOperationLimit(32, 64));
        assertEquals(384, IronFurnacesBatchDelegate.expandedFlatBatchOperationLimit(32, 384));
        assertEquals(512, IronFurnacesBatchDelegate.expandedFlatBatchOperationLimit(512, 384));
    }

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
        assertEquals(384, IronFurnacesBatchDelegate.plannedBatchSize(true, false, 400, 64));
        assertEquals(64, IronFurnacesBatchDelegate.plannedBatchSize(false, true, 400, 64));
        assertEquals(64, IronFurnacesBatchDelegate.plannedBatchSize(false, false, 400, 64));
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
    void factoryLayoutMapsOneReservationOntoOwnedInputOutputPairs() {
        boolean[] leased = {false, true, false, true, true, false};
        boolean[] free = {true, true, true, true, true, true};
        IronFactoryLanePlan plan = IronFactoryLanePlan.plan(
                new ItemStack(Items.CLAY_BALL), 9, 4, false, leased, free);

        assertNotNull(plan);
        assertEquals(9, plan.operations());
        assertEquals(java.util.List.of(8, 10, 11),
                plan.lanes().stream().map(IronFactoryLanePlan.Lane::inputSlot).toList());
        assertEquals(java.util.List.of(14, 16, 17),
                plan.lanes().stream().map(IronFactoryLanePlan.Lane::outputSlot).toList());
        assertEquals(java.util.List.of(4, 4, 1),
                plan.lanes().stream().map(lane -> lane.input().getCount()).toList());
        assertNull(IronFactoryLanePlan.plan(new ItemStack(Items.CLAY_BALL),
                10, 4, false, leased, new boolean[]{true, true, true, false, true, true}));
    }

    @Test
    void ordinaryFactorySpreadsQueuedWorkAcrossAllSixLanes() {
        boolean[] all = {true, true, true, true, true, true};
        IronFactoryLanePlan plan = IronFactoryLanePlan.plan(
                new ItemStack(Items.IRON_ORE), 64, 64, true, all, all);

        assertNotNull(plan);
        assertEquals(java.util.List.of(11, 11, 11, 11, 10, 10),
                plan.lanes().stream().map(lane -> lane.input().getCount()).toList());
        assertEquals(6, IronFurnacesBatchDelegate.requiredFactoryLanes(64, 64, false));
        assertEquals(1, IronFurnacesBatchDelegate.requiredFactoryLanes(64, 64, true));
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
    void ordinaryFurnaceProfilePreloadsButKeepsSerialCycleSemantics() {
        IronFurnaceBatchProfile profile = IronFurnaceBatchProfile.of(false, false, 64);

        assertEquals(64, profile.physicalCapacity());
        assertEquals(64, profile.plannedBatchSize(400));
        assertEquals(64, profile.processingCycles(64));
        assertTrue(profile.preloadsInput());
    }

    @Test
    void ordinaryFurnaceFuelChecksEveryLogicalInputCycle() {
        IronFurnaceBatchProfile profile = IronFurnaceBatchProfile.of(false, false, 64);

        assertEquals(64, profile.processingCycles(64));
        assertEquals(65, profile.processingCycles(65));
    }

    @Test
    void ordinaryFactoryProfilePreloadsEachLaneAndRunsSixLanesInParallel() {
        IronFurnaceBatchProfile profile = IronFurnaceBatchProfile.of(true, false, 64);

        assertEquals(384, profile.physicalCapacity());
        assertEquals(384, profile.plannedBatchSize(400));
        assertEquals(11, profile.processingCycles(64));
        assertTrue(profile.preloadsInput());
    }

    @Test
    void rainbowProfileUsesOneMultipliedCyclePerPhysicalBatch() {
        IronFurnaceBatchProfile profile = IronFurnaceBatchProfile.of(true, true, 64);

        assertEquals(384, profile.physicalCapacity());
        assertEquals(1, profile.processingCycles(384));
        assertEquals(2, profile.processingCycles(385));
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
    void speedAugmentDoublesCoalNeededForTheReported125GlassOperations() {
        for (int tierCookTicks : List.of(200, 80, 40, 20)) {
            assertEquals(16, selectCoal(tierCookTicks, false, false, 0).amount());
            assertEquals(32, selectCoal(tierCookTicks, true, false, 0).amount());
            assertEquals(8, selectCoal(tierCookTicks, false, true, 0).amount());
        }
    }

    @Test
    void speedAugmentFuelBudgetCreditsAlreadyBurningFuel() {
        assertEquals(31, selectCoal(40, true, false, 80).amount());
        assertEquals(0, IronFurnacesBatchDelegate.requiredFuelTicks(20, 125, 2500));
    }

    @Test
    void plannedRecipeTimeKeepsShortLivedFuelsUsableBeforeInputsArePlaced() {
        int cookTicks = IronFurnacesBatchDelegate.cookingTicks(200, 100, false, false);
        int required = IronFurnacesBatchDelegate.requiredFuelTicks(cookTicks, 32, 0);
        var selection = VanillaFurnaceFuelPolicy.select(
                List.of(new ItemStack(Items.STICK, 64)), List.of(), required,
                stack -> IronFurnacesBatchDelegate.effectiveFuelTicks(100, cookTicks, false, false));

        assertEquals(100, cookTicks);
        assertNotNull(selection);
        assertFalse(selection.partial());
        assertEquals(64, selection.amount());
    }

    @Test
    void cookingAndBurnTimeRoundLikeIronFurnacesWithAugments() {
        assertEquals(28, IronFurnacesBatchDelegate.cookingTicks(40, 145, false, false));
        assertEquals(61, IronFurnacesBatchDelegate.cookingTicks(41, 300, false, false));
        assertEquals(30, IronFurnacesBatchDelegate.cookingTicks(41, 300, true, false));
        assertEquals(77, IronFurnacesBatchDelegate.cookingTicks(41, 300, false, true));
        assertEquals(1, IronFurnacesBatchDelegate.cookingTicks(1, 100, true, false));
        assertEquals(0, IronFurnacesBatchDelegate.effectiveFuelTicks(300, 1, true, false));
        assertEquals(2, IronFurnacesBatchDelegate.effectiveFuelTicks(300, 1, false, true));
    }

    private static VanillaFurnaceFuelPolicy.Selection selectCoal(int tierCookTicks,
                                                                 boolean speedAugment,
                                                                 boolean fuelAugment,
                                                                 int currentBurnTime) {
        int cookTicks = IronFurnacesBatchDelegate.cookingTicks(
                tierCookTicks, 200, speedAugment, fuelAugment);
        int required = IronFurnacesBatchDelegate.requiredFuelTicks(cookTicks, 125, currentBurnTime);
        var selection = VanillaFurnaceFuelPolicy.select(
                List.of(new ItemStack(Items.COAL, 64)), List.of("minecraft:coal"), required,
                stack -> IronFurnacesBatchDelegate.effectiveFuelTicks(
                        1600, cookTicks, speedAugment, fuelAugment));
        assertNotNull(selection);
        assertFalse(selection.partial());
        return selection;
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
    void compatibleBindingPreservesConcreteDelegateType() {
        ModType requested = register("vanilla_furnace");
        ModType concrete = register("ironfurnaces_furnace");
        ModType unrelated = register("pmmo_salvage_owner");

        assertSame(concrete,
                AltarBindingRegistry.executionTypeForBinding(requested, concrete));
        assertSame(requested,
                AltarBindingRegistry.executionTypeForBinding(requested, unrelated));
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
    void allIronFurnaceCookingPrefixesRemainGuiVisible() {
        IronFurnacesRSModule.INSTANCE.registerModType();
        IronFurnacesRSModule.INSTANCE.registerBindingTargets();

        assertTrue(BindingEventHandler.supportsGuiByBlockKey(
                "ironfurnaces_furnace||block.ironfurnaces.diamond_furnace"));
        assertTrue(BindingEventHandler.supportsGuiByBlockKey(
                "ironfurnaces_blast_furnace||block.ironfurnaces.diamond_furnace"));
        assertTrue(BindingEventHandler.supportsGuiByBlockKey(
                "ironfurnaces_smoker||block.ironfurnaces.diamond_furnace"));
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
