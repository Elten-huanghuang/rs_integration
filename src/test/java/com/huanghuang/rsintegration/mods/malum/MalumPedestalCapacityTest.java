package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                        1, 64, InputConsumption.consumed()),
                new InputBufferContract.InputSlot(
                        "malum:altar:pedestal:0", 1, new ItemStack(Items.REDSTONE),
                        2, 64, InputConsumption.consumed()),
                new InputBufferContract.InputSlot(
                        "malum:altar:spirit:0", 16, new ItemStack(Items.BLAZE_POWDER),
                        4, 64, InputConsumption.consumed())), List.of());

        InputBufferPlan plan = contract.plan(64);

        assertEquals(16, plan.operations());
        assertEquals(List.of(16, 32, 64),
                plan.inputs().stream().map(input -> input.stack().getCount()).toList());
    }

    @Test
    void altarNormalizesGraphMaterialFragmentsIntoSingleSlotStacks() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.GLASS), 4, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.REDSTONE), 4, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.GUNPOWDER), 4, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.BLAZE_POWDER), 4, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.SOUL_SAND), 4, DemandRole.CONSUMED));
        List<ItemStack> fragments = new ArrayList<>();
        addFragments(fragments, Items.GLASS, 4, 4, 4, 4);
        addFragments(fragments, Items.REDSTONE, 4, 4, 4, 4);
        addFragments(fragments, Items.GUNPOWDER, 4, 4, 4, 4);
        addFragments(fragments, Items.BLAZE_POWDER, 5, 5, 6);
        addFragments(fragments, Items.SOUL_SAND, 5, 5, 6);

        List<ItemStack> normalized = MalumBatchDelegate.normalizePlacementMaterials(
                specs, 2, 2, 4, fragments);

        assertNotNull(normalized);
        assertEquals(5, normalized.size());
        assertEquals(List.of(16, 16, 16, 16, 16),
                normalized.stream().map(ItemStack::getCount).toList());
    }

    private static void addFragments(List<ItemStack> stacks, Item item,
                                     int... counts) {
        for (int count : counts) stacks.add(new ItemStack(item, count));
    }
}
