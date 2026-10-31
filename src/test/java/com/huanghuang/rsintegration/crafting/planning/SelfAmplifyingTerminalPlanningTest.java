package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAmplifyingTerminalPlanningTest extends BootstrapTest {
    private static final MaterialRef SEED = new MaterialRef(
            new ResourceLocation("minecraft", "slime_ball"), "");
    private static final MaterialRef COST = new MaterialRef(
            new ResourceLocation("minecraft", "dirt"), "");

    @ParameterizedTest
    @CsvSource({"1, 1, 2, 1", "4, 1, 2, 1", "59, 1, 2, 1",
            "1, 1, 2, 6", "4, 1, 2, 6", "59, 1, 2, 6",
            "2, 2, 5, 1", "2, 2, 5, 6", "59, 2, 5, 6"})
    void requestedExecutionsAddProductionRegardlessOfExistingStock(
            long seeds, long consumedPerBatch, long producedPerBatch, int executions) {
        RecipeNode target = recipe("duplicate", consumedPerBatch, producedPerBatch);
        Map<StackKey, Long> stock = stock(seeds, executions);
        var result = plan(target, List.of(target), stock, executions);

        assertTrue(result.feasible(), result.toString());
        assertEquals(executions, result.steps().stream()
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(0L, result.remaining().getOrDefault(COST, 0L));

        var graph = PurePlanGraphAdapter.toOutputGraph(result, stock);
        CraftPlanValidator.validate(graph);
        assertFalse(graph.nodes().isEmpty());
        assertEquals(executions, graph.nodes().stream()
                .mapToLong(node -> node.executions()).sum());
        assertTrue(graph.rootDemands().stream().flatMap(root -> root.allocations().stream())
                .allMatch(allocation -> allocation.source() instanceof MaterialSource.ProducerOutput));
        long usedSeeds = graph.allocations().stream()
                .filter(allocation -> allocation.source() instanceof MaterialSource.InitialPool
                        && allocation.material().item() == Items.SLIME_BALL)
                .mapToLong(allocation -> allocation.quantity()).sum();
        long finalSeeds = graph.rootDemands().stream().mapToLong(root -> root.quantity()).sum();
        assertEquals(seeds + (producedPerBatch - consumedPerBatch) * executions,
                seeds - usedSeeds + finalSeeds);
    }

    @Test
    void existingSeedsDoNotHideMissingDuplicationCost() {
        RecipeNode target = recipe("duplicate", 1, 2);

        var result = plan(target, List.of(target), stock(59, 0), 1);

        assertFalse(result.feasible());
        assertFalse(result.missing().isEmpty());
    }

    @Test
    void duplicationStillRequiresItsInitialSeed() {
        RecipeNode target = recipe("duplicate", 1, 2);

        assertFalse(plan(target, List.of(target), stock(0, 6), 6).feasible());
    }

    @Test
    void selectedTerminalRecipeRemainsTheProducerWhenAnotherRecipeIsAvailable() {
        RecipeNode target = recipe("selected_duplicate", 1, 2);
        RecipeNode alternative = new RecipeNode(new ResourceLocation("test", "alternative"),
                SEED, 64, List.of(new IngredientRef(List.of(COST), 1)));

        var result = plan(target, List.of(alternative, target), stock(1, 6), 6);

        assertTrue(result.feasible(), result.toString());
        assertTrue(result.steps().stream().allMatch(step -> step.recipeId().equals(target.recipeId())));
        assertEquals(6L, result.steps().stream().mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "4, 1", "59, 1", "1, 9", "4, 9", "59, 9"})
    void copyStagesSettleAndDeliverTheirRootsWithoutWaitingForUnusedStock(
            int seeds, int executions) {
        RecipeNode target = recipe("fermentation_copy", 1, 2);
        Map<StackKey, Long> stock = stock(seeds, executions);
        var graph = PurePlanGraphAdapter.toOutputGraph(
                plan(target, List.of(target), stock, executions), stock);
        DagScheduler scheduler = new DagScheduler(graph);
        MaterialBroker broker = new MaterialBroker();
        stock.forEach((key, count) -> {
            MaterialKey material = new MaterialKey(key.item(), key.tag());
            broker.publish(new MaterialSource.InitialPool(material), material, count);
        });
        int completedOperations = 0;
        for (int stage = 0; stage < graph.nodes().size(); stage++) {
            List<NodeId> ready = scheduler.claimReady(1);
            assertEquals(1, ready.size(), "复制阶段必须在前序种子回流后就绪");
            NodeId id = ready.get(0);
            var node = graph.nodesById().get(id);
            var requests = graph.allocations().stream()
                    .filter(allocation -> allocation.consumer().nodeId().equals(id))
                    .map(allocation -> new MaterialBroker.Request(allocation.source(),
                            allocation.material(), allocation.quantity())).toList();
            var token = broker.reserve(id, requests);
            assertNotNull(token, "计划中的复制原料必须能实际预留");
            broker.commit(token);
            broker.settle(token);
            node.outputs().forEach(output -> broker.publish(
                    new MaterialSource.ProducerOutput(new OutputPortId(id, output.id().index())),
                    output.material(), output.quantity()));
            scheduler.succeed(id);
            completedOperations += node.executions();
        }
        assertTrue(scheduler.allSucceeded());
        assertTrue(scheduler.runningNodes().isEmpty());
        assertEquals(executions, completedOperations);
        var delivery = graph.rootDemands().stream().flatMap(root -> root.allocations().stream())
                .map(allocation -> new MaterialBroker.Request(allocation.source(),
                        allocation.material(), allocation.quantity())).toList();
        assertTrue(broker.canReserve(delivery), "任务完成时必须能交付全部根需求");

        PlanStep terminal = new PlanStep(target.recipeId(), new ItemStack(Items.SLIME_BALL, 2),
                executions, List.of(new ItemStack(Items.SLIME_BALL), new ItemStack(Items.DIRT)),
                List.of(), ModType.GENERIC);
        var response = new PlanResponse(true, "", terminal.output(), List.of(terminal), Map.of(),
                List.of(), target.recipeId().toString(), null, null, 0, 0, 0, List.of(), executions,
                null, null, null, 0, false, false, false, null, Set.of(), Map.of(), null,
                PlanGraphView.from(graph));
        PlanTreeNode display = PlanTreeModel.from(response).root;
        assertEquals(executions, display.step.batches());
        assertTrue(display.children.stream().allMatch(child -> child.step == null),
                "复制阶段应合并成一个展示配方，原料成本仍完整保留");
        assertEquals(executions, display.children.stream()
                .filter(child -> child.displayStack.is(Items.DIRT)).mapToLong(child -> child.amount).sum());
    }

    private static PureRecipePlanner.Result plan(RecipeNode target, List<RecipeNode> producers,
                                                  Map<StackKey, Long> stock, int executions) {
        return AsyncPurePlanningService.resolveInScope(new ImmutableRecipeGraph(Map.of(SEED, producers)),
                ImmutableRecipeGraphProjector.projectAvailability(stock), target, executions,
                30, 20_000, 8192, Long.MAX_VALUE, Map.of(), PlanningPreferences.empty());
    }

    private static RecipeNode recipe(String id, long consumed, long produced) {
        return new RecipeNode(new ResourceLocation("test", id), SEED, produced,
                List.of(new IngredientRef(List.of(SEED), consumed), new IngredientRef(List.of(COST), 1)));
    }

    private static Map<StackKey, Long> stock(long seeds, long cost) {
        return Map.of(new StackKey(Items.SLIME_BALL, null), seeds,
                new StackKey(Items.DIRT, null), cost);
    }
}
