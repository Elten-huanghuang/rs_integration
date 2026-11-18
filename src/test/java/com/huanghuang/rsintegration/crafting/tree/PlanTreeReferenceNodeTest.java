package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.handler.codec.DecoderException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 引用节点标记：同一批产出在树里只展开一次成本，其他出现位置必须被标成"仅消耗引用"，
 * 否则界面上两个一模一样的节点（同图标、同数量、同机器名）会被当成要生产两批。
 */
class PlanTreeReferenceNodeTest extends BootstrapTest {
    @Test
    void packetRejectsSelfForwardAndOutOfRangeTraceReferences() {
        var item = new ItemStack(Items.IRON_INGOT);
        for (int source : List.of(0, 1, 99)) {
            var step = new PlanStep(ResourceLocation.parse("test:bad"), item, 1, List.of()).withTraceInputs(
                    List.of(new PlanStep.TraceInput(item, 1, DemandRole.CONSUMED, source)));
            var response = new PlanResponse(false, "", item, List.of(step, step), Map.of(), List.of(),
                    step.recipeId().toString());
            var payload = PlanResponsePacket.encodePayload(response, 1);
            assertThrows(DecoderException.class, () -> PlanResponsePacket.decodePayload(payload));
        }
    }

    @Test
    void budgetTraceWithoutSourceDoesNotInventAProducerAfterRoundTrip() {
        var ingot = new ItemStack(Items.IRON_INGOT);
        var producer = new PlanStep(ResourceLocation.parse("test:possible"), ingot, 1, List.of());
        var terminal = new PlanStep(ResourceLocation.parse("test:target"), new ItemStack(Items.DIAMOND),
                1, List.of(ingot));
        var response = new PlanResponse(false, "", terminal.output(), List.of(producer, terminal),
                Map.of(), List.of(), terminal.recipeId().toString(), List.of(), 1, PlanOutcome.BUDGET_EXHAUSTED, List.of());
        var decoded = PlanResponsePacket.decodePayload(PlanResponsePacket.encodePayload(response, 7)).plan();
        assertNull(decoded.graph());
        assertEquals(2, decoded.steps().size());
        assertEquals(PlanOutcome.BUDGET_EXHAUSTED, decoded.outcome());
        assertNull(PlanTreeModel.from(decoded).root.children.get(0).step);
    }

    @Test
    void sharedProducerIsExpandedOnceAndMarkedAsReferenceEverywhereElse() {
        ResourceLocation targetRecipe = new ResourceLocation("test", "target");
        ItemStack target = new ItemStack(Items.DIAMOND);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);

        // 根配方同时吃铁锭和金锭，而金锭又需要铁锭 → 铁锭这个生产步骤被两个消费者引用。
        PlanStep ironStep = new PlanStep(new ResourceLocation("test", "iron_ingot"), iron, 1,
                List.of(new ItemStack(Items.IRON_ORE)));
        PlanStep goldStep = new PlanStep(new ResourceLocation("test", "gold_ingot"), gold, 1,
                List.of(iron)).withTraceInputs(List.of(new PlanStep.TraceInput(iron, 1, DemandRole.CONSUMED, 0)));
        PlanStep targetStep = new PlanStep(targetRecipe, target, 1, List.of(iron, gold)).withTraceInputs(List.of(
                new PlanStep.TraceInput(iron, 1, DemandRole.CONSUMED, 0),
                new PlanStep.TraceInput(gold, 1, DemandRole.CONSUMED, 1)));

        PlanResponse plan = new PlanResponse(true, "", target,
                List.of(ironStep, goldStep, targetStep), Map.of(), List.of(),
                targetRecipe.toString(), null, null, 0, 0, 0, List.of(), 1);

        PlanTreeNode root = PlanTreeModel.from(plan).root;
        List<PlanTreeNode> ironNodes = new ArrayList<>();
        collectByKey(root, IngredientKey.of(iron), ironNodes);

        assertEquals(2, ironNodes.size(), "铁锭应在根下和金锭下各出现一次");
        List<PlanTreeNode> expanded = ironNodes.stream()
                .filter(node -> !node.children.isEmpty())
                .toList();
        assertEquals(1, expanded.size(), "共享产出只允许展开一次成本");
        assertFalse(expanded.get(0).referenceOnly, "展开成本的那一处不是引用节点");

        PlanTreeNode reference = ironNodes.stream()
                .filter(node -> node.children.isEmpty())
                .findFirst()
                .orElseThrow();
        assertTrue(reference.referenceOnly, "没有子节点的同批产出必须标成引用");
        assertNotNull(reference.step, "引用节点仍带步骤，点击可以改配方偏好");
        assertEquals(1, reference.amount, "引用节点上的数量是这条边的消耗量");
    }

    @Test
    void uniqueProducersAndRawLeavesAreNotMarkedAsReferences() {
        ResourceLocation targetRecipe = new ResourceLocation("test", "target");
        ItemStack target = new ItemStack(Items.DIAMOND);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);

        PlanStep ironStep = new PlanStep(new ResourceLocation("test", "iron_ingot"), iron, 1,
                List.of(new ItemStack(Items.IRON_ORE)));
        PlanStep targetStep = new PlanStep(targetRecipe, target, 1, List.of(iron)).withTraceInputs(List.of(
                new PlanStep.TraceInput(iron, 1, DemandRole.CONSUMED, 0)));

        PlanResponse plan = new PlanResponse(true, "", target,
                List.of(ironStep, targetStep), Map.of(), List.of(),
                targetRecipe.toString(), null, null, 0, 0, 0, List.of(), 1);

        PlanTreeNode root = PlanTreeModel.from(plan).root;
        List<PlanTreeNode> all = new ArrayList<>();
        collectAll(root, all);

        assertFalse(all.isEmpty());
        for (PlanTreeNode node : all) {
            assertFalse(node.referenceOnly, "只出现一次的生产步骤与原料叶节点都不是引用节点");
        }
    }

    private static void collectByKey(PlanTreeNode node, IngredientKey key, List<PlanTreeNode> out) {
        if (key.equals(node.key)) out.add(node);
        for (PlanTreeNode child : node.children) {
            collectByKey(child, key, out);
        }
    }

    private static void collectAll(PlanTreeNode node, List<PlanTreeNode> out) {
        out.add(node);
        for (PlanTreeNode child : node.children) {
            collectAll(child, out);
        }
    }
}
