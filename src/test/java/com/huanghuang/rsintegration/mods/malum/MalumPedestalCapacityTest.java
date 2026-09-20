package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MalumPedestalCapacityTest extends BootstrapTest {
    @Test
    void nonStackableIngredientsUseOnePedestalPerItem() {
        assertEquals(2, MalumBatchDelegate.pedestalSlotsForCount(2, 1));
    }

    @Test
    void stackableIngredientsAreSplitOnlyAtTheirRealLimit() {
        assertEquals(1, MalumBatchDelegate.pedestalSlotsForCount(64, 64));
        assertEquals(2, MalumBatchDelegate.pedestalSlotsForCount(65, 64));
    }

    @Test
    void emptyRequirementsUseNoPedestals() {
        assertEquals(0, MalumBatchDelegate.pedestalSlotsForCount(0, 1));
    }

    @Test
    void altarMaterialPlanPreservesCenterPedestalAndSpiritIdentity() {
        MaterialPlan plan = MalumBatchDelegate.materialPlanForSpecs(List.of(
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.REDSTONE), 2, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.BLAZE_POWDER), 1, DemandRole.CONSUMED)),
                1, 1);

        assertEquals(List.of("malum:altar:center", "malum:altar:pedestal:0",
                        "malum:altar:spirit:0"),
                plan.entries().stream().map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(0, 1, 16),
                plan.entries().stream().map(MaterialPlan.Entry::inputSlot).toList());
    }

    @Test
    void altarBufferCapacityMeansQueuedCyclesNotOneParallelCraft() {
        InputBufferContract contract = new InputBufferContract(64, List.of(
                new InputBufferContract.InputSlot(
                        "malum:altar:center", 0, new ItemStack(Items.IRON_INGOT),
                        1, false, 64),
                new InputBufferContract.InputSlot(
                        "malum:altar:pedestal:0", 1, new ItemStack(Items.REDSTONE),
                        2, false, 64),
                new InputBufferContract.InputSlot(
                        "malum:altar:spirit:0", 16, new ItemStack(Items.BLAZE_POWDER),
                        4, false, 64)), List.of());

        InputBufferPlan plan = contract.plan(64);

        assertEquals(16, plan.operations());
        assertEquals(List.of(16, 32, 64),
                plan.inputs().stream().map(input -> input.stack().getCount()).toList());
    }
}
