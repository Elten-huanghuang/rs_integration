package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEquivalenceTest extends BootstrapTest {
    @Test
    void emptyGraphAndFlatPlanAreEquivalent() {
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(), List.of(), List.of(), List.of(), List.of());
        assertTrue(ExecutionEquivalence.compare(graph, List.of()).equivalent());
        assertFalse(ExecutionEquivalence.compare(graph, List.of(
                new CraftingResolver.ResolutionStep(
                        new net.minecraft.resources.ResourceLocation("test", "extra"),
                        ModType.GENERIC,
                        new net.minecraft.resources.ResourceLocation("minecraft", "crafting")))
        ).equivalent());
    }

    @Test
    void projectedGraphAndFlatStepsPreserveRecipeAndExecutionCount() {
        NodeId nodeId = new NodeId(0);
        CraftNode node = new CraftNode(nodeId, new net.minecraft.resources.ResourceLocation("test", "planks"),
                ModType.GENERIC.id(), new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                3, List.of(), List.of(), false, null, null, List.of(), List.of());
        MaterialKey output = MaterialKey.of(new ItemStack(Items.OAK_PLANKS));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.OAK_PLANKS), 1, 0, new ItemStack(Items.OAK_PLANKS),
                        List.of(new RootAllocation(new MaterialSource.InitialPool(output), output, 1)))),
                List.of(), List.of(nodeId));
        CraftingResolver.ResolutionStep flat = new CraftingResolver.ResolutionStep(node.recipeId(),
                ModType.GENERIC, node.recipeTypeId(), List.of(), List.of(), false, 3);
        assertTrue(ExecutionEquivalence.compare(graph, List.of(flat)).equivalent());
    }

    @Test
    void projectsExecutionMetadataFromTheSelectedNodeRatherThanItsListPosition() {
        CraftNode twoExecutions = new CraftNode(new NodeId(7),
                new net.minecraft.resources.ResourceLocation("test", "two"),
                ModType.GENERIC.id(), new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                2, List.of(), List.of(), false, null, null, List.of(), List.of());
        CraftNode fourExecutions = new CraftNode(new NodeId(2),
                new net.minecraft.resources.ResourceLocation("test", "four"),
                ModType.GENERIC.id(), new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                4, List.of(new net.minecraft.resources.ResourceLocation("test", "alternative")),
                List.of(ModType.GENERIC.id()), true,
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT),
                List.of(), List.of());

        // Mirrors a graph whose node ids and execution counts do not match a
        // compatibility step list's positions.
        List<CraftNode> compatibilityOrder = List.of(twoExecutions, fourExecutions);
        CraftingResolver.ResolutionStep selected = ExecutionEquivalence.projectStep(
                compatibilityOrder.get(1));

        assertEquals(fourExecutions.recipeId(), selected.recipeId());
        assertEquals(4, selected.executions());
        assertEquals(fourExecutions.alternativeIds(), selected.alternativeIds());
        assertTrue(selected.inferMode());
        assertTrue(ItemStack.isSameItemSameTags(fourExecutions.syntheticInput(), selected.syntheticInput()));
        assertTrue(ItemStack.isSameItemSameTags(fourExecutions.syntheticOutput(), selected.syntheticOutput()));
    }

    @Test
    void projectsProducerBeforeSyntheticConsumerWhenConsumerHasLowerNodeId() {
        NodeId taintId = new NodeId(0);
        NodeId producerId = new NodeId(1);
        ItemStack plainHeart = new ItemStack(Items.HEART_OF_THE_SEA);
        MaterialKey heart = MaterialKey.of(plainHeart);
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        InputPortId taintInput = new InputPortId(taintId, 0);
        CraftNode taint = new CraftNode(taintId,
                CraftingResolver.TAINT_EARTH_HEART_STEP, ModType.GENERIC.id(),
                CraftingResolver.TAINT_EARTH_HEART_STEP, 1,
                List.of(), List.of(), false, plainHeart, new ItemStack(Items.NETHER_STAR),
                List.of(new InputDemand(taintInput, Ingredient.of(Items.HEART_OF_THE_SEA),
                        1, DemandRole.TRANSFORMED, plainHeart)), List.of());
        CraftNode producer = new CraftNode(producerId,
                new net.minecraft.resources.ResourceLocation("test", "plain_heart"),
                ModType.GENERIC.id(), new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                1, List.of(), List.of(), false, null, null, List.of(),
                List.of(new OutputDeclaration(producerOutput, heart, 1, OutputKind.PRIMARY)));
        MaterialAllocation allocation = new MaterialAllocation(new AllocationId(0), taintInput,
                new MaterialSource.ProducerOutput(producerOutput), heart, 1);
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(taint, producer),
                List.of(allocation),
                List.of(new RootDemand(Ingredient.of(Items.NETHER_STAR), 1, 0,
                        new ItemStack(Items.NETHER_STAR), List.of())),
                List.of(), List.of(producerId, taintId));

        List<CraftingResolver.ResolutionStep> projected =
                ExecutionEquivalence.projectFlatSteps(graph);

        assertEquals(producer.recipeId(), projected.get(0).recipeId());
        assertEquals(CraftingResolver.TAINT_EARTH_HEART_STEP,
                projected.get(1).recipeId());
    }

    @Test
    void repeatedTerminalGraphMatchesLegacyFlatExecution() {
        MaterialKey input = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CraftPlanGraph inputGraph = new CraftPlanGraph(1, List.of(), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 4, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(input), input, 4)))), List.of(), List.of());
        CraftingResolver.ResolutionStep terminal = new CraftingResolver.ResolutionStep(
                new net.minecraft.resources.ResourceLocation("test", "compressed_iron"),
                ModType.GENERIC, new net.minecraft.resources.ResourceLocation("test", "press"),
                List.of(), List.of(), false, 4);

        CraftPlanGraph complete = TerminalGraphComposer.compose(
                inputGraph, terminal, new ItemStack(Items.IRON_BLOCK));

        assertTrue(ExecutionEquivalence.compare(complete, List.of(terminal)).equivalent());
        assertTrue(ExecutionEquivalence.compare(complete,
                ExecutionEquivalence.projectFlatSteps(complete)).equivalent());
    }

    @Test
    void comparesAllProjectedExecutionMetadata() {
        NodeId nodeId = new NodeId(0);
        ItemStack syntheticInput = new ItemStack(Items.IRON_INGOT);
        ItemStack syntheticOutput = new ItemStack(Items.GOLD_INGOT);
        CraftNode node = new CraftNode(nodeId,
                new net.minecraft.resources.ResourceLocation("test", "metadata"),
                ModType.GENERIC.id(), new net.minecraft.resources.ResourceLocation("test", "type_a"),
                2, List.of(new net.minecraft.resources.ResourceLocation("test", "alternative")),
                List.of("generic"), true, syntheticInput, syntheticOutput, List.of(), List.of());
        MaterialKey rootMaterial = MaterialKey.of(new ItemStack(Items.STICK));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(rootMaterial), rootMaterial, 1)))),
                List.of(), List.of(nodeId));
        CraftingResolver.ResolutionStep differentType = new CraftingResolver.ResolutionStep(
                node.recipeId(), ModType.GENERIC,
                new net.minecraft.resources.ResourceLocation("test", "type_b"),
                node.alternativeIds(), node.alternativeModTypeIds(), true, 2,
                syntheticInput, syntheticOutput);

        assertFalse(ExecutionEquivalence.compare(graph, List.of(differentType)).equivalent());
        assertTrue(ExecutionEquivalence.compare(graph,
                ExecutionEquivalence.projectFlatSteps(graph)).equivalent());
    }

    @Test
    void rejectsUnknownGraphModType() {
        CraftNode node = new CraftNode(new NodeId(0),
                new net.minecraft.resources.ResourceLocation("test", "unknown"), "missing_type",
                new net.minecraft.resources.ResourceLocation("test", "type"), 1,
                List.of(), List.of(), false, null, null, List.of(), List.of());
        MaterialKey rootMaterial = MaterialKey.of(new ItemStack(Items.STICK));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(rootMaterial), rootMaterial, 1)))),
                List.of(), List.of(node.id()));

        assertThrows(IllegalArgumentException.class,
                () -> ExecutionEquivalence.projectFlatSteps(graph));
    }
}
