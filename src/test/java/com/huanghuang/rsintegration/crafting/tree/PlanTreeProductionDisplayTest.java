package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlan;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanTreeProductionDisplayTest extends BootstrapTest {
    @Test
    void eightAndOneBatchesDisplayOneNineItemProducerAcrossFourMachines() {
        ResourceLocation recipe = new ResourceLocation("test", "iron_ingot");
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);
        ItemStack ore = new ItemStack(Items.IRON_ORE);
        var initial = new PlanGraphView.SourceView(true, -1, -1);
        var first = new PlanGraphView.SourceView(false, 0, 0);
        var last = new PlanGraphView.SourceView(false, 1, 0);
        PlanGraphView graph = new PlanGraphView(1, List.of(
                node(0, recipe, ingot, ore, 8, parallel(2, 2, 2, 2)),
                node(1, recipe, ingot, ore, 1, parallel(1))),
                List.of(new PlanGraphView.EdgeView(0, 0, initial, ore, 8),
                        new PlanGraphView.EdgeView(1, 0, initial, ore, 1)),
                List.of(new PlanGraphView.RootView(ingot, 9, 0, List.of(
                        new PlanGraphView.RootEdgeView(first, ingot, 8),
                        new PlanGraphView.RootEdgeView(last, ingot, 1)))),
                List.of(), List.of(0, 1));
        PlanTreeNode root = PlanTreeModel.from(response(graph)).root;

        assertEquals(1, root.children.size());
        PlanTreeNode producer = root.children.get(0);
        assertEquals(9, producer.displayAmount());
        assertEquals(9, producer.step.batches());
        assertEquals(9, producer.children.get(0).amount);
        assertEquals(9, producer.parallelPlan.totalOperations());
        assertEquals(4, producer.parallelPlan.workerCount());
        assertEquals("3+2+2+2", producer.parallelPlan.shareSummary());
        assertEquals(Set.of(0, 1), producer.producerNodeIds);
        assertEquals(2, graph.nodes().size(), "执行图仍保留两批的真实节点");
    }

    @Test
    void sharedProducerReferencesDoNotDoubleItsProduction() {
        ResourceLocation recipe = new ResourceLocation("test", "iron_ingot");
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);
        ItemStack ore = new ItemStack(Items.IRON_ORE);
        var source = new PlanGraphView.SourceView(false, 0, 0);
        PlanGraphView graph = new PlanGraphView(1,
                List.of(node(0, recipe, ingot, ore, 9, parallel(3, 2, 2, 2))),
                List.of(new PlanGraphView.EdgeView(0, 0,
                        new PlanGraphView.SourceView(true, -1, -1), ore, 9)),
                List.of(new PlanGraphView.RootView(ingot, 9, 0, List.of(
                        new PlanGraphView.RootEdgeView(source, ingot, 8),
                        new PlanGraphView.RootEdgeView(source, ingot, 1)))),
                List.of(), List.of(0));

        PlanTreeNode produced = PlanTreeModel.from(response(graph)).root.children.get(0);
        assertEquals(9, produced.amount);
        assertEquals(9, produced.productionAmount);
        assertEquals(9, produced.step.batches());
        assertEquals(9, produced.children.get(0).amount);
    }

    private static PlanGraphView.NodeView node(int id, ResourceLocation recipe, ItemStack output,
                                               ItemStack input, int executions, ParallelPlan parallel) {
        return new PlanGraphView.NodeView(id, recipe, "generic", executions, output,
                List.of(), List.of(), List.of(new PlanGraphView.InputView(0, input, executions, 0)),
                List.of(new PlanGraphView.OutputView(0, output, executions, 0)), parallel);
    }

    private static ParallelPlan parallel(int... shares) {
        List<ParallelPlan.WorkerShare> workers = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < shares.length; i++) {
            workers.add(new ParallelPlan.WorkerShare(new BlockPos(i, 0, 0),
                    "vanilla_furnace", shares[i], 0, 0));
            total += shares[i];
        }
        return new ParallelPlan(workers, total, 0, 0, null);
    }

    private static PlanResponse response(PlanGraphView graph) {
        ItemStack output = new ItemStack(Items.DIAMOND);
        PlanStep terminal = new PlanStep(new ResourceLocation("test", "target"), output, 1,
                List.of(new ItemStack(Items.IRON_INGOT, 9)));
        return new PlanResponse(true, "", output, List.of(terminal), Map.of(), List.of(),
                terminal.recipeId().toString(), null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null, Set.of(), Map.of(), null, graph);
    }

    @Test
    void producedNodeDisplaysRemainingProductionInsteadOfGrossDemand() {
        ResourceLocation targetRecipe = new ResourceLocation("test", "target");
        ResourceLocation ingotRecipe = new ResourceLocation("test", "iron_ingot");
        ItemStack target = new ItemStack(Items.DIAMOND);
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);

        PlanStep producer = new PlanStep(ingotRecipe, ingot, 7,
                List.of(new ItemStack(Items.IRON_ORE)));
        PlanStep terminal = new PlanStep(targetRecipe, target, 84, List.of(ingot));
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(ingot), new PlanResponse.Availability(84, 77, 0));

        PlanResponse plan = new PlanResponse(true, "", target,
                List.of(producer, terminal), materials, List.of(),
                targetRecipe.toString(), null, null, 0, 0, 0, List.of(), 84);
        PlanTreeNode produced = PlanTreeModel.from(plan).findByKey(IngredientKey.of(ingot));

        assertEquals(84, produced.amount);
        assertEquals(7, produced.step.batches());
        assertEquals(7, produced.productionAmount);
        assertEquals(7, produced.displayAmount());
    }
}
