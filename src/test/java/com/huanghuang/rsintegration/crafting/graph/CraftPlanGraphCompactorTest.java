package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class CraftPlanGraphCompactorTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = ResourceLocation.parse("test:compressed");
    private static final int LARGE_GRAPH_NODES = 4104;

    @Test
    void mergesIndependentEquivalentNodesAndPreservesTotals() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        List<CraftNode> nodes = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        List<RootDemand> roots = new ArrayList<>();
        List<NodeId> order = new ArrayList<>();
        long totalExecutions = 0L;
        for (int i = 0; i < 20; i++) {
            NodeId nodeId = new NodeId(i);
            InputPortId input = new InputPortId(nodeId, 0);
            OutputPortId output = new OutputPortId(nodeId, 0);
            int executions = i + 1;
            totalExecutions += executions;
            nodes.add(node(nodeId, input, output, executions));
            allocations.add(new MaterialAllocation(new AllocationId(i), input,
                    new MaterialSource.InitialPool(ingot), ingot, executions));
            roots.add(root(output, executions));
            order.add(nodeId);
        }
        CraftPlanGraph graph = new CraftPlanGraph(1, nodes, allocations, roots,
                List.of(), order);

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertEquals(1, compacted.nodes().size());
        assertEquals(totalExecutions, compacted.nodes().get(0).executions());
        assertEquals(totalExecutions, compacted.nodes().get(0).inputs().get(0).quantity());
        assertEquals(totalExecutions, compacted.nodes().get(0).outputs().get(0).quantity());
        assertEquals(totalExecutions, compacted.allocations().get(0).quantity());
        assertEquals(20, compacted.rootDemands().size());
        assertEquals(totalExecutions, compacted.rootDemands().stream()
                .mapToLong(RootDemand::quantity).sum());
        assertEquals(totalExecutions, compacted.rootDemands().stream()
                .flatMap(root -> root.allocations().stream())
                .mapToLong(RootAllocation::quantity).sum());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void compactsLargeIndependentGenericGraph() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        List<CraftNode> nodes = new ArrayList<>(LARGE_GRAPH_NODES);
        List<MaterialAllocation> allocations = new ArrayList<>(LARGE_GRAPH_NODES);
        List<RootDemand> roots = new ArrayList<>(LARGE_GRAPH_NODES);
        List<NodeId> order = new ArrayList<>(LARGE_GRAPH_NODES);
        for (int i = 0; i < LARGE_GRAPH_NODES; i++) {
            NodeId nodeId = new NodeId(i);
            InputPortId input = new InputPortId(nodeId, 0);
            OutputPortId output = new OutputPortId(nodeId, 0);
            nodes.add(node(nodeId, input, output, 1));
            allocations.add(new MaterialAllocation(new AllocationId(i), input,
                    new MaterialSource.InitialPool(ingot), ingot, 1));
            roots.add(root(output, 1));
            order.add(nodeId);
        }
        CraftPlanGraph graph = new CraftPlanGraph(1, nodes, allocations, roots,
                List.of(), order);

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertEquals(1, compacted.nodes().size());
        assertEquals(LARGE_GRAPH_NODES, compacted.nodes().get(0).executions());
        assertEquals(LARGE_GRAPH_NODES, compacted.rootDemands().size());
        assertEquals(LARGE_GRAPH_NODES, compacted.allocations().get(0).quantity());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void doesNotMergeEquivalentNonGenericNodes() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        InputPortId firstInput = new InputPortId(firstId, 0);
        InputPortId secondInput = new InputPortId(secondId, 0);
        OutputPortId firstOutput = new OutputPortId(firstId, 0);
        OutputPortId secondOutput = new OutputPortId(secondId, 0);
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(firstId, firstInput, firstOutput, 1, null,
                                ModType.FLUID_CONTAINER.id()),
                        node(secondId, secondInput, secondOutput, 1, null,
                                ModType.FLUID_CONTAINER.id())),
                List.of(
                        new MaterialAllocation(new AllocationId(0), firstInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1),
                        new MaterialAllocation(new AllocationId(1), secondInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1)),
                List.of(root(firstOutput, 1), root(secondOutput, 1)),
                List.of(), List.of(firstId, secondId));

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertEquals(2, compacted.nodes().size());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void mergesEquivalentNodesWhenExecutionPolicyAuthorizesThem() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        List<CraftNode> nodes = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        List<RootDemand> roots = new ArrayList<>();
        List<NodeId> order = new ArrayList<>();
        for (int i = 0; i < 56; i++) {
            NodeId nodeId = new NodeId(i);
            InputPortId input = new InputPortId(nodeId, 0);
            OutputPortId output = new OutputPortId(nodeId, 0);
            nodes.add(node(nodeId, input, output, 9, null,
                    ModType.FLUID_CONTAINER.id()));
            allocations.add(new MaterialAllocation(new AllocationId(i), input,
                    new MaterialSource.InitialPool(ingot), ingot, 9));
            roots.add(root(output, 9));
            order.add(nodeId);
        }
        CraftPlanGraph graph = new CraftPlanGraph(1, nodes, allocations, roots,
                List.of(), order);

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph, ignored -> true);

        assertEquals(1, compacted.nodes().size());
        assertEquals(56 * 9, compacted.nodes().get(0).executions());
        assertEquals(56 * 9, compacted.allocations().get(0).quantity());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void doesNotMergeNodesConnectedByAnInternalDependency() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        InputPortId firstInput = new InputPortId(firstId, 0);
        InputPortId secondInput = new InputPortId(secondId, 0);
        OutputPortId firstOutput = new OutputPortId(firstId, 0);
        OutputPortId secondOutput = new OutputPortId(secondId, 0);
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(firstId, firstInput, firstOutput, 1),
                        node(secondId, secondInput, secondOutput, 1)),
                List.of(
                        new MaterialAllocation(new AllocationId(0), firstInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1),
                        new MaterialAllocation(new AllocationId(1), secondInput,
                                new MaterialSource.ProducerOutput(firstOutput), ingot, 1)),
                List.of(root(secondOutput, 1)), List.of(), List.of(firstId, secondId));

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertEquals(2, compacted.nodes().size());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void recomputesTopologicalOrderAfterMergingNodes() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        NodeId firstId = new NodeId(0);
        NodeId dependentId = new NodeId(1);
        NodeId producerId = new NodeId(2);
        InputPortId firstInput = new InputPortId(firstId, 0);
        InputPortId dependentInput = new InputPortId(dependentId, 0);
        InputPortId producerInput = new InputPortId(producerId, 0);
        OutputPortId firstOutput = new OutputPortId(firstId, 0);
        OutputPortId dependentOutput = new OutputPortId(dependentId, 0);
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(firstId, firstInput, firstOutput, 1),
                        node(dependentId, dependentInput, dependentOutput, 1),
                        node(producerId, producerInput, producerOutput, 1,
                                null, ModType.GENERIC.id(),
                                ResourceLocation.parse("test:producer"))),
                List.of(
                        new MaterialAllocation(new AllocationId(0), firstInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1),
                        new MaterialAllocation(new AllocationId(1), dependentInput,
                                new MaterialSource.ProducerOutput(producerOutput), ingot, 1),
                        new MaterialAllocation(new AllocationId(2), producerInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1)),
                List.of(root(dependentOutput, 1)), List.of(),
                List.of(firstId, producerId, dependentId));

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertNotSame(graph, compacted);
        assertEquals(2, compacted.nodes().size());
        assertEquals(List.of(producerId, firstId), compacted.topologicalOrder());
        CraftPlanValidator.validate(compacted);
    }

    @Test
    void keepsDifferentNbtOutputsSeparate() {
        MaterialKey ingot = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        InputPortId firstInput = new InputPortId(firstId, 0);
        InputPortId secondInput = new InputPortId(secondId, 0);
        OutputPortId firstOutput = new OutputPortId(firstId, 0);
        OutputPortId secondOutput = new OutputPortId(secondId, 0);
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(firstId, firstInput, firstOutput, 1, "red"),
                        node(secondId, secondInput, secondOutput, 1, "blue")),
                List.of(
                        new MaterialAllocation(new AllocationId(0), firstInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1),
                        new MaterialAllocation(new AllocationId(1), secondInput,
                                new MaterialSource.InitialPool(ingot), ingot, 1)),
                List.of(root(firstOutput, 1, "red"), root(secondOutput, 1, "blue")),
                List.of(), List.of(firstId, secondId));

        CraftPlanGraph compacted = CraftPlanGraphCompactor.compact(graph);

        assertEquals(2, compacted.nodes().size());
        CraftPlanValidator.validate(compacted);
    }

    private static CraftNode node(NodeId nodeId, InputPortId input, OutputPortId output,
                                  int executions) {
        return node(nodeId, input, output, executions, null, ModType.GENERIC.id());
    }

    private static CraftNode node(NodeId nodeId, InputPortId input, OutputPortId output,
                                  int executions, String variant) {
        return node(nodeId, input, output, executions, variant, ModType.GENERIC.id());
    }

    private static CraftNode node(NodeId nodeId, InputPortId input, OutputPortId output,
                                  int executions, String variant, String modTypeId) {
        return node(nodeId, input, output, executions, variant, modTypeId, RECIPE);
    }

    private static CraftNode node(NodeId nodeId, InputPortId input, OutputPortId output,
                                  int executions, String variant, String modTypeId,
                                  ResourceLocation recipeId) {
        ItemStack result = new ItemStack(Items.IRON_INGOT, 1);
        if (variant != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("variant", variant);
            result.setTag(tag);
        }
        return new CraftNode(nodeId, recipeId, modTypeId,
                ResourceLocation.parse("minecraft:crafting"), executions, List.of(), List.of(),
                false, null, null,
                List.of(new InputDemand(input, Ingredient.of(Items.IRON_INGOT), executions,
                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                List.of(new OutputDeclaration(output,
                        MaterialKey.of(result), executions,
                        OutputKind.PRIMARY)));
    }

    private static RootDemand root(OutputPortId output, long quantity) {
        return root(output, quantity, null);
    }

    private static RootDemand root(OutputPortId output, long quantity, String variant) {
        ItemStack display = new ItemStack(Items.IRON_INGOT);
        if (variant != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("variant", variant);
            display.setTag(tag);
        }
        return new RootDemand(Ingredient.of(Items.IRON_INGOT), quantity, 0,
                display,
                List.of(new RootAllocation(new MaterialSource.ProducerOutput(output),
                        MaterialKey.of(display), quantity)));
    }
}
