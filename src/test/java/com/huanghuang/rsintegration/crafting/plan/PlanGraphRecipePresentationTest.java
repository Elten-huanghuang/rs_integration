package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.SelectedPath;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanGraphRecipePresentationTest extends BootstrapTest {
    @Test
    void nineNuggetsRestoreTheThreeByThreeGridWithoutScalingTheActualBatch() {
        ResourceLocation id = new ResourceLocation("minecraft", "iron_ingot_from_nuggets");
        PlanStep template = new PlanStep(id, new ItemStack(Items.IRON_INGOT), 7,
                Collections.nCopies(9, new ItemStack(Items.IRON_NUGGET)),
                List.of(), null, 0, false, 3, 3);
        PlanGraphView.NodeView node = new PlanGraphView.NodeView(0, id, "generic", 2,
                new ItemStack(Items.IRON_INGOT),
                List.of(new PlanGraphView.InputView(0, new ItemStack(Items.IRON_NUGGET),
                        18, DemandRole.CONSUMED.ordinal())),
                List.of(new PlanGraphView.OutputView(0, new ItemStack(Items.IRON_INGOT), 2, 0)));

        PlanStep displayed = node.asPlanStep(template);

        assertEquals(3, displayed.recipeWidth());
        assertEquals(3, displayed.recipeHeight());
        assertEquals(9, displayed.inputs().size());
        assertTrue(displayed.inputs().stream().allMatch(input -> input.getCount() == 1));
        assertEquals(2, displayed.batches());
        assertEquals(18, displayed.totalInputCount());
    }

    @Test
    void treeReceivesTheSameCuttingBoardAlternativeAsTheFlatPreview() {
        ResourceLocation id = new ResourceLocation("test", "amethyst_shards");
        ResourceLocation cutting = new ResourceLocation("farmersdelight", "cutting/amethyst_block");
        PlanStep template = new PlanStep(id, new ItemStack(Items.AMETHYST_SHARD), 1,
                List.of(), List.of(cutting), null, 0, true, 0, 0,
                List.of("farmersdelight_cutting_board"));
        PlanGraphView.NodeView node = new PlanGraphView.NodeView(0, id, "generic", 1,
                new ItemStack(Items.AMETHYST_SHARD), List.of(), List.of());
        PlanGraphView original = new PlanGraphView(1, List.of(node), List.of(),
                List.of(), List.of(), List.of(0));

        PlanGraphView enriched = original.withRecipeAlternatives(List.of(template));

        assertEquals(List.of(cutting), enriched.nodes().get(0).asPlanStep().alternatives());
        assertEquals(List.of("farmersdelight_cutting_board"),
                enriched.nodes().get(0).alternativeModTypeIds());
        assertTrue(original.nodes().get(0).alternativeIds().isEmpty());
        assertEquals(original.edges(), enriched.edges());
        assertEquals(original.roots(), enriched.roots());
    }

    @Test
    void gridTemplateCannotReplaceTheGraphsExactNbtState() {
        ResourceLocation id = new ResourceLocation("test", "stateful_grid");
        ItemStack actual = new ItemStack(Items.IRON_NUGGET);
        actual.getOrCreateTag().putString("variant", "actual");
        PlanGraphView.NodeView node = new PlanGraphView.NodeView(0, id, "generic", 1,
                new ItemStack(Items.IRON_INGOT),
                List.of(new PlanGraphView.InputView(0, actual, 9, 0)), List.of());
        PlanStep template = new PlanStep(id, new ItemStack(Items.IRON_INGOT), 1,
                Collections.nCopies(9, new ItemStack(Items.IRON_NUGGET)),
                List.of(), null, 0, false, 3, 3);

        PlanStep displayed = node.asPlanStep(template);

        assertEquals(actual.getTag(), displayed.inputs().get(0).getTag());
        assertEquals(9, displayed.inputs().get(0).getCount());
        assertEquals(0, displayed.recipeWidth());
    }

    @Test
    void stockedMaterialKeepsSelectableCuttingBoardRecipeAcrossTheWireWithoutAProductionNode() {
        ItemStack shards = new ItemStack(Items.AMETHYST_SHARD);
        IngredientKey key = IngredientKey.of(shards);
        ResourceLocation cutting = new ResourceLocation("farmersdelight", "cutting/amethyst_block");
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(),
                List.of(new PlanGraphView.RootView(shards, 4, 0, List.of(
                        new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(true, -1, -1), shards, 4)))),
                List.of(), List.of()).withRecipeOptions(Map.of(key, List.of(
                        new PlanGraphView.RecipeOption(cutting, "farmersdelight_cutting_board"))));
        PlanStep terminal = new PlanStep(new ResourceLocation("test", "target"),
                new ItemStack(Items.DIAMOND), 1, List.of(shards.copyWithCount(4)));
        PlanResponse plan = response(graph, List.of(terminal), terminal);

        PlanResponse decoded = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(plan, 7)).plan();
        PlanTreeModel tree = PlanTreeModel.from(decoded);
        PlanTreeNode stock = tree.findByKey(key);

        assertNull(stock.step, "候选元数据不应成为额外生产步骤");
        assertEquals(4, stock.amount);
        assertEquals("farmersdelight_cutting_board", RecipeCandidateRules.optionsFor(stock).get(cutting));
        assertTrue(RecipeCandidateRules.canSelect(stock, cutting));
        assertFalse(RecipeCandidateRules.canSelect(stock, new ResourceLocation("test", "unlisted")));
        assertTrue(decoded.graph().nodes().isEmpty());
        SelectedPath selected = new SelectedPath();
        selected.bindTree(tree);
        selected.selectBranch(key, 0, cutting);
        assertEquals(Map.of(key, cutting), selected.exportSelections());
    }

    @Test
    void missingRootToolsRetainTheirRoleInsteadOfMergingWithConsumedMaterials() {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(),
                List.of(new PlanGraphView.RootView(pickaxe, 1, 1, List.of(),
                                DemandRole.CONSUMED.ordinal()),
                        new PlanGraphView.RootView(pickaxe, 2, 2, List.of(),
                                DemandRole.DURABILITY.ordinal())),
                List.of(), List.of());
        PlanStep terminal = new PlanStep(new ResourceLocation("test", "target"),
                new ItemStack(Items.DIAMOND), 1, List.of(pickaxe));
        PlanResponse decoded = PlanResponsePacket.decodePayload(PlanResponsePacket.encodePayload(
                response(graph, List.of(terminal), terminal), 9)).plan();

        List<PlanTreeNode> missing = PlanTreeModel.from(decoded).root.children;

        assertEquals(2, missing.size());
        assertEquals(DemandRole.CONSUMED, missing.get(0).demandRole);
        assertEquals(1, missing.get(0).unresolved);
        assertEquals(DemandRole.DURABILITY, missing.get(1).demandRole);
        assertEquals(2, missing.get(1).unresolved);
    }

    @Test
    void recursiveTreeRestoresNineSlotsAndRetainsTheCuttingAlternativeAfterPacketRoundTrip() {
        ResourceLocation id = new ResourceLocation("minecraft", "iron_ingot_from_nuggets");
        ResourceLocation alternative = new ResourceLocation("test", "smelt_iron");
        ItemStack nugget = new ItemStack(Items.IRON_NUGGET);
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);
        PlanStep compression = new PlanStep(id, ingot, 2,
                Collections.nCopies(9, nugget), List.of(alternative), null,
                1, true, 3, 3, List.of("vanilla_furnace"));
        PlanStep terminal = new PlanStep(new ResourceLocation("test", "target"),
                new ItemStack(Items.DIAMOND), 1, List.of(ingot.copyWithCount(2)));
        PlanGraphView graph = new PlanGraphView(1,
                List.of(new PlanGraphView.NodeView(0, id, "generic", 2, ingot,
                        List.of(new PlanGraphView.InputView(0, nugget, 18, 0)),
                        List.of(new PlanGraphView.OutputView(0, ingot, 2, 0)))),
                List.of(new PlanGraphView.EdgeView(0, 0,
                        new PlanGraphView.SourceView(true, -1, -1), nugget, 18)),
                List.of(new PlanGraphView.RootView(ingot, 2, 0, List.of(
                        new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(false, 0, 0), ingot, 2)))),
                List.of(), List.of(0)).withRecipeAlternatives(List.of(compression));
        PlanResponse decoded = PlanResponsePacket.decodePayload(PlanResponsePacket.encodePayload(
                response(graph, List.of(compression, terminal), terminal), 8)).plan();

        PlanTreeNode producer = PlanTreeModel.from(decoded).findByKey(IngredientKey.of(ingot));

        assertEquals(3, producer.step.recipeWidth());
        assertEquals(3, producer.step.recipeHeight());
        assertEquals(9, producer.step.inputs().size());
        assertEquals(18, producer.children.get(0).amount);
        assertEquals(2, producer.step.batches());
        assertEquals(List.of(alternative), producer.step.alternatives());
        assertTrue(RecipeCandidateRules.canSelect(producer, alternative));
    }

    private static PlanResponse response(PlanGraphView graph, List<PlanStep> steps,
                                         PlanStep terminal) {
        return new PlanResponse(true, "", terminal.output(), steps, Map.of(), List.of(),
                terminal.recipeId().toString(), null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null, Set.of(), Map.of(), null, graph);
    }
}
