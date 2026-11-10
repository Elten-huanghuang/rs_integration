package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CraftOutputProgressTest extends BootstrapTest {
    private final OutputPortId targetPort = new OutputPortId(new NodeId(1), 1);
    private final OutputPortId intermediatePort = new OutputPortId(new NodeId(0), 0);

    @Test
    void rootOutputUsesItsRequestedQuantityInsteadOfTheGhostStackOrRecipeExecutionCount() {
        ItemStack target = new ItemStack(Items.DIAMOND);
        CraftPlanGraph graph = graph(target, 64L);

        assertEquals(new CraftOutputProgress(0L, 64L), CraftOutputProgress.fromGraph(graph, target, Map.of()));
        assertEquals(new CraftOutputProgress(4L, 64L), CraftOutputProgress.fromGraph(graph, target,
                Map.of(targetPort, 4L, intermediatePort, 500L)));
        assertEquals(new CraftOutputProgress(64L, 64L), CraftOutputProgress.fromGraph(graph, target,
                Map.of(targetPort, 68L)));
    }

    @Test
    void repeatedAllocationsOfTheSamePortDoNotCountOnePublicationTwice() {
        ItemStack target = new ItemStack(Items.DIAMOND);
        MaterialKey material = MaterialKey.of(target);
        var source = new MaterialSource.ProducerOutput(targetPort);
        var root = new RootDemand(Ingredient.of(target), 64L, 0L, target,
                List.of(new RootAllocation(source, material, 32L), new RootAllocation(source, material, 32L)));
        var graph = new CraftPlanGraph(1, List.of(), List.of(), List.of(root), List.of(), List.of());

        assertEquals(new CraftOutputProgress(4L, 64L), CraftOutputProgress.fromGraph(graph, target, Map.of(targetPort, 4L)));
    }

    @Test
    void fluidProgressUsesMillibucketsAndDoesNotIncludeAnotherFluid() {
        ItemStack water = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 1));
        ItemStack lava = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.LAVA, 1));

        assertEquals(new CraftOutputProgress(250L, 1000L), CraftOutputProgress.fromGraph(graph(water, 1000L),
                water, Map.of(targetPort, 250L)));
        assertFalse(CraftOutputProgress.fromGraph(graph(lava, 1000L), water, Map.of(targetPort, 1000L)).known());
    }

    @Test
    void outputProgressNeverBorrowsAnUnrelatedTargetsQuantityOrOverflowsLong() {
        ItemStack target = new ItemStack(Items.DIAMOND);
        assertFalse(CraftOutputProgress.fromGraph(graph(target, 64L), new ItemStack(Items.GOLD_INGOT),
                Map.of(targetPort, 64L)).known());
        assertEquals(new CraftOutputProgress(Long.MAX_VALUE - 1L, Long.MAX_VALUE),
                CraftOutputProgress.fromGraph(graph(target, Long.MAX_VALUE), target,
                        Map.of(targetPort, Long.MAX_VALUE - 1L)));
    }

    private CraftPlanGraph graph(ItemStack target, long quantity) {
        MaterialKey material = MaterialKey.of(target);
        var root = new RootDemand(Ingredient.of(target), quantity, 0L, target,
                List.of(new RootAllocation(new MaterialSource.ProducerOutput(targetPort), material, quantity)));
        return new CraftPlanGraph(1, List.of(), List.of(), List.of(root), List.of(), List.of());
    }
}
