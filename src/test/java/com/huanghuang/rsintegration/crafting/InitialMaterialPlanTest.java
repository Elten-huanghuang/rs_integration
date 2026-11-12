package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitialMaterialPlanTest extends BootstrapTest {
    @Test
    void sumsInitialEdgesAndRootsWithoutChargingProducerOutputs() {
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        CraftPlanGraph graph = mock(CraftPlanGraph.class);
        when(graph.nodes()).thenReturn(List.of());
        when(graph.allocations()).thenReturn(List.of(
                new MaterialAllocation(new AllocationId(0), new InputPortId(new NodeId(0), 0),
                        new MaterialSource.InitialPool(iron), iron, (long) Integer.MAX_VALUE),
                new MaterialAllocation(new AllocationId(1), new InputPortId(new NodeId(1), 0),
                        new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0)), gold, 100)));
        when(graph.rootDemands()).thenReturn(List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT),
                3, 0, iron.toStack(1), List.of(new RootAllocation(new MaterialSource.InitialPool(iron), iron, 3)))));
        assertEquals(Map.of(iron, (long) Integer.MAX_VALUE + 3), InitialMaterialPlan.from(graph).materials());
    }

    @Test
    void legacyStepsSubtractIntermediateProductsAndReuseCatalysts() {
        IngredientSpec iron = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2);
        IngredientSpec diamond = new IngredientSpec(Ingredient.of(Items.DIAMOND), 1);
        IngredientSpec tool = new IngredientSpec(Ingredient.of(Items.SHEARS), 1,
                InputConsumption.catalyst());
        var plan = InitialMaterialPlan.fromSteps(List.of(
                new InitialMaterialPlan.Step(List.of(iron, tool), new ItemStack(Items.DIAMOND), 2),
                new InitialMaterialPlan.Step(List.of(diamond, tool), new ItemStack(Items.EMERALD), 2)),
                Map.of(new CraftingResolver.StackKey(Items.IRON_INGOT, null), 4L,
                        new CraftingResolver.StackKey(Items.SHEARS, null), 1L));
        assertEquals(Map.of(MaterialKey.of(new ItemStack(Items.IRON_INGOT)), 4L,
                MaterialKey.of(new ItemStack(Items.SHEARS)), 1L), plan.materials());
    }
}
