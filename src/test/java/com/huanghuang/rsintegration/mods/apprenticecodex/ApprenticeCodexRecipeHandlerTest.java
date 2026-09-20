package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprenticeCodexRecipeHandlerTest {

    @Test
    void smokerSharesSmallOrdersInsteadOfFirstWorkerClaimingEverything() {
        var delegate = new ApprenticeCodexEssenceSmokerBatchDelegate();
        assertEquals(1, delegate.preferredParallelBatchSize(2, 2));
        assertEquals(3, delegate.preferredParallelBatchSize(8, 3));
        assertEquals(8, delegate.preferredParallelBatchSize(100, 2));
        assertEquals(1, delegate.preferredParallelBatchSize(1, 4));
    }

    @Test
    void catalystDemandRoundsUpAtEightMaterialSlots() {
        assertEquals(0, ApprenticeCodexRecipeHandler.requiredCatalystCount(0));
        assertEquals(1, ApprenticeCodexRecipeHandler.requiredCatalystCount(1));
        assertEquals(1, ApprenticeCodexRecipeHandler.requiredCatalystCount(8));
        assertEquals(2, ApprenticeCodexRecipeHandler.requiredCatalystCount(9));
        assertEquals(2, ApprenticeCodexRecipeHandler.requiredCatalystCount(16));
        assertEquals(3, ApprenticeCodexRecipeHandler.requiredCatalystCount(17));
    }

    @Test
    void recipeDemandHookScalesOnlyTheCatalystByPhysicalCycles() {
        ApprenticeCodexRecipeHandler handler = ApprenticeCodexRecipeHandler.essenceSmoker();
        Ingredient ingredient = Ingredient.of(Items.STONE);

        assertEquals(2, handler.requiredIngredientCount(null,
                new IngredientSpec(ingredient, 1, DemandRole.CATALYST), 0, 9));
        assertEquals(9, handler.requiredIngredientCount(null,
                new IngredientSpec(ingredient, 1), 1, 9));
    }

    @Test
    void bufferedSmokerPlanUsesOneReusableCatalystAndEightMaterials() {
        InputBufferContract contract = new InputBufferContract(8,
                java.util.List.of(
                        new InputBufferContract.InputSlot(
                                "catalyst", 0, new net.minecraft.world.item.ItemStack(Items.STONE),
                                1, true, 1),
                        new InputBufferContract.InputSlot(
                                "material", 1, new net.minecraft.world.item.ItemStack(Items.DIRT),
                                1, false, 8)),
                java.util.List.of(new OutputContract.Port(
                        "primary", null, new net.minecraft.world.item.ItemStack(Items.DIAMOND), 1,
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
        InputBufferPlan plan = contract.plan(8);
        assertEquals(8, plan.operations());
        assertEquals(1, plan.inputs().get(0).stack().getCount());
        assertEquals(8, plan.inputs().get(1).stack().getCount());
        assertEquals(OutputContract.Source.VIRTUAL, plan.outputs().get(0).source());
    }

    @Test
    void bufferedSmokerEntryIdsMatchLegacyMaterialPlan() {
        var specs = java.util.List.of(
                new IngredientSpec(Ingredient.of(Items.STONE), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.DIRT), 1));
        MaterialPlan materialPlan = MaterialPlan.fromLegacy(specs,
                java.util.List.of(
                        com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE,
                        com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.MaterialReservationScope.PER_OPERATION));

        assertEquals(ApprenticeCodexEssenceSmokerBatchDelegate.CATALYST_ENTRY_ID,
                materialPlan.entries().get(0).id());
        assertEquals(ApprenticeCodexEssenceSmokerBatchDelegate.MATERIAL_ENTRY_ID,
                materialPlan.entries().get(1).id());
    }
}
