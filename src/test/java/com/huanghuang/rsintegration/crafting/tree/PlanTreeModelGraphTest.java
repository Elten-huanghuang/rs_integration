package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanTreeModelGraphTest extends BootstrapTest {

    @Test
    void separateNodesOfSameRecipeFoldIntoOneVisualBranch() {
        ResourceLocation unpack = new ResourceLocation("minecraft", "iron_block");
        PlanGraphView.NodeView first = new PlanGraphView.NodeView(20, unpack, "generic", 1,
                new ItemStack(Items.IRON_INGOT, 9), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.IRON_INGOT), 9, 0)));
        PlanGraphView.NodeView second = new PlanGraphView.NodeView(21, unpack, "generic", 1,
                new ItemStack(Items.IRON_INGOT, 9), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.IRON_INGOT), 9, 0)));
        PlanGraphView.NodeView gun = new PlanGraphView.NodeView(22,
                new ResourceLocation("tacz", "gun/test"), "tacz", 1,
                new ItemStack(Items.DIAMOND), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.DIAMOND), 1, 0)));
        PlanGraphView graph = new PlanGraphView(1, List.of(first, second, gun), List.of(
                new PlanGraphView.EdgeView(22, 0, new PlanGraphView.SourceView(false, 20, 0),
                        new ItemStack(Items.IRON_INGOT), 9),
                new PlanGraphView.EdgeView(22, 0, new PlanGraphView.SourceView(false, 21, 0),
                        new ItemStack(Items.IRON_INGOT), 1)),
                List.of(new PlanGraphView.RootView(new ItemStack(Items.DIAMOND), 1, 0,
                        List.of(new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(false, 22, 0),
                                new ItemStack(Items.DIAMOND), 1)))), List.of(), List.of(20, 21, 22));
        PlanResponse plan = new PlanResponse(true, "root", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(), List.of(), "test:root", null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, graph);

        PlanTreeNode gunTree = PlanTreeModel.from(plan).root.children.get(0);
        assertEquals(1, gunTree.children.size());
        assertEquals(10, gunTree.children.get(0).amount);
        assertEquals(10, gunTree.children.get(0).edgeQuantity);
    }

    @Test
    void repeatedProducerReferencesShareLogicalNodeId() {
        PlanGraphView.NodeView producer = new PlanGraphView.NodeView(0,
                new ResourceLocation("test", "iron"), "generic", 1,
                new ItemStack(Items.IRON_INGOT),
                List.of(new ResourceLocation("test", "iron_alternate")), List.of("generic"),
                List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.IRON_INGOT), 3, 0)));
        PlanGraphView.NodeView left = new PlanGraphView.NodeView(1,
                new ResourceLocation("test", "left"), "generic", 1,
                new ItemStack(Items.DIAMOND), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.DIAMOND), 1, 0)));
        PlanGraphView.NodeView right = new PlanGraphView.NodeView(2,
                new ResourceLocation("test", "right"), "generic", 1,
                new ItemStack(Items.EMERALD), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.EMERALD), 1, 0)));
        PlanGraphView.SourceView ironSource = new PlanGraphView.SourceView(false, 0, 0);
        PlanGraphView graph = new PlanGraphView(1, List.of(producer, left, right), List.of(
                new PlanGraphView.EdgeView(1, 0, ironSource, new ItemStack(Items.IRON_INGOT), 1),
                new PlanGraphView.EdgeView(2, 0, ironSource, new ItemStack(Items.IRON_INGOT), 2)),
                List.of(
                        new PlanGraphView.RootView(new ItemStack(Items.DIAMOND), 1, 0, List.of(
                                new PlanGraphView.RootEdgeView(new PlanGraphView.SourceView(false, 1, 0),
                                        new ItemStack(Items.DIAMOND), 1))),
                        new PlanGraphView.RootView(new ItemStack(Items.EMERALD), 1, 0, List.of(
                                new PlanGraphView.RootEdgeView(new PlanGraphView.SourceView(false, 2, 0),
                                        new ItemStack(Items.EMERALD), 1)))),
                List.of(), List.of(0, 1, 2));
        PlanResponse plan = new PlanResponse(true, "root", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(), List.of(), "test:root",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, graph);

        PlanTreeModel tree = PlanTreeModel.from(plan);
        assertEquals(2, tree.graphReferenceCounts().get(0));
        assertEquals(1, tree.root.children.get(0).children.get(0).edgeQuantity);
        assertEquals(2, tree.root.children.get(1).children.get(0).edgeQuantity);
        assertTrue(tree.root.children.get(0).children.get(0).graphNodeId == 0);
        assertTrue(tree.root.children.get(0).children.get(0).hasAlternatives());
    }

    @Test
    void repeatedSlotsFromOneBatchRenderAsOneQuantity() {
        PlanGraphView.NodeView producer = new PlanGraphView.NodeView(3,
                new ResourceLocation("malum", "spirit_infusion/alchemy_glass"), "malum", 1,
                new ItemStack(Items.GLASS, 4), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.GLASS), 4, 0)));
        PlanGraphView.NodeView consumer = new PlanGraphView.NodeView(4,
                new ResourceLocation("test", "consumer"), "generic", 1,
                new ItemStack(Items.DIAMOND), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.DIAMOND), 1, 0)));
        PlanGraphView.SourceView glassSource = new PlanGraphView.SourceView(false, 3, 0);
        PlanGraphView graph = new PlanGraphView(1, List.of(producer, consumer), List.of(
                new PlanGraphView.EdgeView(4, 0, glassSource, new ItemStack(Items.GLASS), 1),
                new PlanGraphView.EdgeView(4, 1, glassSource, new ItemStack(Items.GLASS), 1),
                new PlanGraphView.EdgeView(4, 2, glassSource, new ItemStack(Items.GLASS), 1),
                new PlanGraphView.EdgeView(4, 3, glassSource, new ItemStack(Items.GLASS), 1)),
                List.of(new PlanGraphView.RootView(new ItemStack(Items.DIAMOND), 1, 0,
                        List.of(new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(false, 4, 0),
                                new ItemStack(Items.DIAMOND), 1)))),
                List.of(), List.of(3, 4));
        PlanResponse plan = new PlanResponse(true, "root", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(), List.of(), "test:root", null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, graph);

        PlanTreeNode consumerTree = PlanTreeModel.from(plan).root.children.get(0);
        assertEquals(1, consumerTree.children.size());
        assertEquals(4, consumerTree.children.get(0).amount);
        assertEquals(4, consumerTree.children.get(0).edgeQuantity);
        assertEquals(3, consumerTree.children.get(0).graphNodeId);
    }

    @Test
    void grossDemandUsesGraphQuantitiesWithoutRescalingReusableRoots() {
        PlanGraphView.SourceView initial = new PlanGraphView.SourceView(true, -1, -1);
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(), List.of(
                new PlanGraphView.RootView(new ItemStack(Items.IRON_BLOCK), 1, 0,
                        List.of(new PlanGraphView.RootEdgeView(
                                initial, new ItemStack(Items.IRON_BLOCK), 1))),
                new PlanGraphView.RootView(new ItemStack(Items.WHEAT_SEEDS), 3, 0,
                        List.of(new PlanGraphView.RootEdgeView(
                                initial, new ItemStack(Items.WHEAT_SEEDS), 3)))),
                List.of(), List.of());
        PlanResponse plan = new PlanResponse(true, "root", new ItemStack(Items.IRON_NUGGET, 9),
                List.of(), Map.of(), List.of(), "test:root", null, null, 0, 0, 0,
                List.of(), 3, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, graph);

        Map<IngredientKey, Integer> gross = PlanTreeModel.grossDemandByKey(
                PlanTreeModel.from(plan));

        assertEquals(1, gross.get(IngredientKey.of(new ItemStack(Items.IRON_BLOCK))));
        assertEquals(3, gross.get(IngredientKey.of(new ItemStack(Items.WHEAT_SEEDS))));
    }

    @Test
    void collapseStateUsesNodeIdForSameItemRecipes() {
        ResourceLocation sharedRecipe = new ResourceLocation("test", "same_recipe");
        PlanTreeNode first = new PlanTreeNode(IngredientKey.of(new ItemStack(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_INGOT), 1, 1,
                new com.huanghuang.rsintegration.crafting.plan.PlanStep(sharedRecipe,
                        new ItemStack(Items.IRON_INGOT), 1, List.of(), List.of(), null), 11);
        PlanTreeNode second = new PlanTreeNode(IngredientKey.of(new ItemStack(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_INGOT), 1, 1,
                new com.huanghuang.rsintegration.crafting.plan.PlanStep(sharedRecipe,
                        new ItemStack(Items.IRON_INGOT), 1, List.of(), List.of(), null), 12);
        first.children.add(new PlanTreeNode(IngredientKey.of(new ItemStack(Items.COAL)),
                new ItemStack(Items.COAL), 1, 2, null));
        second.children.add(new PlanTreeNode(IngredientKey.of(new ItemStack(Items.COAL)),
                new ItemStack(Items.COAL), 1, 2, null));
        first.expanded = false;

        Set<PlanTreeModel.CollapseKey> collapsed = new java.util.LinkedHashSet<>();
        PlanTreeModel.collectCollapsedNodes(first, collapsed);
        PlanTreeModel.applyCollapsedNodes(second, collapsed);

        assertFalse(second.expanded == false, "different NodeId must not share collapse state");
    }

    @Test
    void partiallyUnresolvedPortKeepsAllocatedAndMissingReferencesSeparate() {
        PlanGraphView.NodeView producer = new PlanGraphView.NodeView(7,
                new ResourceLocation("test", "output"), "generic", 1,
                new ItemStack(Items.DIAMOND), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.DIAMOND), 1, 0)));
        PlanGraphView.NodeView consumer = new PlanGraphView.NodeView(8,
                new ResourceLocation("test", "consumer"), "generic", 1,
                new ItemStack(Items.EMERALD), List.of(), List.of(
                new PlanGraphView.OutputView(0, new ItemStack(Items.EMERALD), 1, 0)));
        PlanGraphView graph = new PlanGraphView(1, List.of(producer, consumer), List.of(
                new PlanGraphView.EdgeView(8, 0,
                        new PlanGraphView.SourceView(false, 7, 0),
                        new ItemStack(Items.DIAMOND), 1)),
                List.of(new PlanGraphView.RootView(new ItemStack(Items.EMERALD), 1, 0,
                        List.of(new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(false, 8, 0),
                                new ItemStack(Items.EMERALD), 1)))),
                List.of(new PlanGraphView.UnresolvedView(8, 0,
                        new ItemStack(Items.IRON_INGOT), 2)), List.of(7, 8));
        PlanResponse plan = new PlanResponse(true, "root", new ItemStack(Items.EMERALD),
                List.of(), Map.of(), List.of(), "test:root", null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, graph);

        PlanTreeNode consumerTree = PlanTreeModel.from(plan).root.children.get(0);
        assertEquals(2, consumerTree.children.size());
        assertFalse(consumerTree.children.get(0).unresolved > 0
                && consumerTree.children.get(1).unresolved == 0);
        assertEquals(2, consumerTree.children.get(1).unresolved);
    }
}
