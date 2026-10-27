package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用节点标记：同一批产出在树里只展开一次成本，其他出现位置必须被标成"仅消耗引用"，
 * 否则界面上两个一模一样的节点（同图标、同数量、同机器名）会被当成要生产两批。
 */
class PlanTreeReferenceNodeTest extends BootstrapTest {

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
                List.of(iron));
        PlanStep targetStep = new PlanStep(targetRecipe, target, 1, List.of(iron, gold));

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
        PlanStep targetStep = new PlanStep(targetRecipe, target, 1, List.of(iron));

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
