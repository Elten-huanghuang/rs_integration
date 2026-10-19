package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftPlanGraphMagicCostTest {

    @Test
    void rejectsOneNodeCostThatExceedsIntRangeAfterScaling() {
        CraftPlanGraph graph = graph(List.of(node(0, 3,
                new MagicResourceCost(MagicResourceKind.MANA, 1_000_000_000))));

        assertThrows(ArithmeticException.class, graph::magicCosts);
    }

    @Test
    void rejectsMergedNodeCostsThatExceedIntRange() {
        CraftPlanGraph graph = graph(List.of(
                node(0, 1, new MagicResourceCost(MagicResourceKind.MANA, 1_500_000_000)),
                node(1, 1, new MagicResourceCost(MagicResourceKind.MANA, 800_000_000))));

        assertThrows(ArithmeticException.class, graph::magicCosts);
    }

    @Test
    void keepsRepresentableScaledCostExact() {
        CraftPlanGraph graph = graph(List.of(node(0, 3,
                new MagicResourceCost(MagicResourceKind.MANA, 100))));

        assertEquals(List.of(new MagicResourceCost(MagicResourceKind.MANA, 300)),
                graph.magicCosts());
    }

    private static CraftPlanGraph graph(List<CraftNode> nodes) {
        return new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION, nodes, List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 1,
                        new ItemStack(Items.STICK), List.of())),
                List.of(), nodes.stream().map(CraftNode::id).toList());
    }

    private static CraftNode node(int id, int executions, MagicResourceCost cost) {
        return new CraftNode(new NodeId(id), new ResourceLocation("test", "cost_" + id),
                "test", null, executions, List.of(), List.of(), false, null, null,
                List.of(), List.of(), List.of(cost));
    }
}
