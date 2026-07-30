package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftGroupSchedulingTest extends BootstrapTest {

    @Test
    void supplementalContainerIsRepeatedAndMergedIntoEveryOperation() {
        IngredientSpec bowl = new IngredientSpec(Ingredient.of(Items.BOWL), 1);
        assertEquals(6, ParallelCraftGroup.repeatSpecs(List.of(bowl), 6).size());

        List<ItemStack> graph = new ArrayList<>();
        List<ItemStack> supplemental = new ArrayList<>();
        for (int operation = 0; operation < 6; operation++) {
            graph.add(new ItemStack(Items.MILK_BUCKET));
            graph.add(new ItemStack(Items.SUGAR));
            graph.add(new ItemStack(Items.COCOA_BEANS));
            graph.add(new ItemStack(Items.COCOA_BEANS));
            supplemental.add(new ItemStack(Items.BOWL));
        }

        List<ItemStack> merged = ParallelCraftGroup.mergeOperationSlices(
                graph, supplemental, 6, 4, 1, (inputs, containers) -> {
                    List<ItemStack> operation = new ArrayList<>(inputs);
                    operation.addAll(containers);
                    return operation;
                });

        assertEquals(30, merged.size());
        for (int operation = 0; operation < 6; operation++) {
            assertTrue(merged.get(operation * 5 + 4).is(Items.BOWL));
        }
    }
    @Test
    void exclusiveDelegateCanDrainOperationsThroughOneSerialWorker() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(true, 1));
    }

    @Test
    void exclusiveDelegateCannotJoinMultiWorkerGroup() {
        assertFalse(ParallelCraftGroup.operationGroupAcceptsChild(true, 2));
    }

    @Test
    void concurrencySafeDelegateCanJoinMultiWorkerGroup() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(false, 2));
    }
}
