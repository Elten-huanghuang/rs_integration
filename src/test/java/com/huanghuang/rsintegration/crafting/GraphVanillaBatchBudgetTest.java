package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphVanillaBatchBudgetTest extends BootstrapTest {

    @Test
    void stableGraphBatchCostsOneOperationRegardlessOfExpandedExecutions() {
        VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(8);
        assertEquals(114, AsyncCraftChain.claimGraphVanillaExecutions(
                budget.allowance(8), 114, true));
        assertEquals(1, budget.used());
    }

    @Test
    void nonStableGraphNodeSlicesAcrossTicksInsteadOfWaitingForFullBudget() {
        int remaining = 114;
        int ticks = 0;
        int totalGranted = 0;
        while (remaining > 0 && ticks < 20) {
            VanillaCraftingTickBudget budget = new VanillaCraftingTickBudget(8);
            int granted = AsyncCraftChain.claimGraphVanillaExecutions(
                    budget.allowance(8), remaining, false);
            assertTrue(granted > 0 && granted <= 8);
            remaining -= granted;
            totalGranted += granted;
            ticks++;
        }
        assertEquals(114, totalGranted);
        assertEquals(0, remaining);
        assertEquals(15, ticks);
    }

    @Test
    void durabilityGraphInputsKeepTheirPlannedPhysicalItemCount() {
        List<InputDemand> inputs = List.of(
                new InputDemand(
                        new InputPortId(new NodeId(0), 0),
                        Ingredient.of(Items.IRON_PICKAXE),
                        2L,
                        InputConsumption.durability(1L),
                        new ItemStack(Items.IRON_PICKAXE)),
                new InputDemand(
                        new InputPortId(new NodeId(0), 1),
                        Ingredient.of(Items.REDSTONE),
                        3L,
                        InputConsumption.consumed(),
                        new ItemStack(Items.REDSTONE)));

        List<IngredientSpec> specs =
                AsyncCraftChain.plannedDurabilityGraphSpecs(inputs);

        assertEquals(2, specs.get(0).count());
        assertEquals(InputConsumption.Kind.DURABILITY,
                specs.get(0).consumption().kind());
        assertEquals(3, specs.get(1).count());
    }

    @Test
    void durabilityGraphSpecsFollowPortIndexNotResolutionOrder() {
        // 规划期先解析窄条件（工具=单物品），再解析宽的标签输入，
        // 于是 inputs 的解析顺序是 [工具, 原料]；派发必须还原成槽位顺序。
        List<InputDemand> inputs = List.of(
                new InputDemand(
                        new InputPortId(new NodeId(0), 1),
                        Ingredient.of(Items.IRON_PICKAXE),
                        1L,
                        InputConsumption.durability(1L),
                        new ItemStack(Items.IRON_PICKAXE)),
                new InputDemand(
                        new InputPortId(new NodeId(0), 0),
                        Ingredient.of(Items.OAK_PLANKS),
                        1L,
                        InputConsumption.consumed(),
                        new ItemStack(Items.OAK_PLANKS)));

        List<IngredientSpec> specs = AsyncCraftChain.plannedDurabilityGraphSpecs(inputs);

        assertEquals(InputConsumption.Kind.CONSUMED, specs.get(0).consumption().kind());
        assertEquals(InputConsumption.Kind.DURABILITY, specs.get(1).consumption().kind());
    }
}
