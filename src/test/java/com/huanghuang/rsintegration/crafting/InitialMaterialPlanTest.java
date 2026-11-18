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
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitialMaterialPlanTest extends BootstrapTest {
    @Test
    void fluidAdmissionCountsNetExternalAmountNotUpstreamProduction() {
        MaterialKey water = MaterialKey.of(InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 1)));
        CraftPlanGraph graph = mock(CraftPlanGraph.class);
        when(graph.nodes()).thenReturn(List.of());
        when(graph.rootDemands()).thenReturn(List.of());
        when(graph.allocations()).thenReturn(List.of(
                new MaterialAllocation(new AllocationId(0), new InputPortId(new NodeId(1), 0),
                        new MaterialSource.InitialPool(water), water, 300),
                new MaterialAllocation(new AllocationId(1), new InputPortId(new NodeId(1), 0),
                        new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0)), water, 700)));
        assertEquals(Map.of(water, 300L), InitialMaterialPlan.from(graph).materials());
    }

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

}
