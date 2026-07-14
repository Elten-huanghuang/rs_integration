package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftPlanValidatorTest extends BootstrapTest {

    @Test
    void acceptsDiamondWithQuantifiedSharedOutput() {
        NodeId producerId = new NodeId(0);
        NodeId leftId = new NodeId(1);
        NodeId rightId = new NodeId(2);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey stick = MaterialKey.of(new ItemStack(Items.STICK));
        MaterialKey leftOut = MaterialKey.of(new ItemStack(Items.IRON_SWORD));
        MaterialKey rightOut = MaterialKey.of(new ItemStack(Items.IRON_PICKAXE));

        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        InputPortId leftInput = new InputPortId(leftId, 0);
        InputPortId rightInput = new InputPortId(rightId, 0);
        OutputPortId leftOutput = new OutputPortId(leftId, 0);
        OutputPortId rightOutput = new OutputPortId(rightId, 0);

        CraftNode producer = node(producerId, "producer", List.of(),
                List.of(new OutputDeclaration(producerOutput, iron, 3, OutputKind.PRIMARY)));
        CraftNode left = node(leftId, "left",
                List.of(new InputDemand(leftInput, Ingredient.of(Items.IRON_INGOT), 1,
                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                List.of(new OutputDeclaration(leftOutput, leftOut, 1, OutputKind.PRIMARY)));
        CraftNode right = node(rightId, "right",
                List.of(new InputDemand(rightInput, Ingredient.of(Items.IRON_INGOT), 2,
                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                List.of(new OutputDeclaration(rightOutput, rightOut, 1, OutputKind.PRIMARY)));

        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(producer, left, right),
                List.of(
                        new MaterialAllocation(new AllocationId(0), leftInput,
                                new MaterialSource.ProducerOutput(producerOutput), iron, 1),
                        new MaterialAllocation(new AllocationId(1), rightInput,
                                new MaterialSource.ProducerOutput(producerOutput), iron, 2)),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0, new ItemStack(Items.STICK), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(stick), stick, 1)))),
                List.of(), List.of(producerId, leftId, rightId));

        CraftPlanValidator.validate(graph);
        assertEquals(List.of(producerId), graph.dependenciesOf(leftId).stream().toList());
        assertEquals(List.of(producerId), graph.dependenciesOf(rightId).stream().toList());
    }

    @Test
    void rejectsOverallocatedProducerOutput() {
        NodeId producerId = new NodeId(0);
        NodeId consumerId = new NodeId(1);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        InputPortId consumerInput = new InputPortId(consumerId, 0);

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(
                        node(producerId, "producer", List.of(),
                                List.of(new OutputDeclaration(producerOutput, iron, 1, OutputKind.PRIMARY))),
                        node(consumerId, "consumer",
                                List.of(new InputDemand(consumerInput, Ingredient.of(Items.IRON_INGOT), 2,
                                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                                List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), consumerInput,
                        new MaterialSource.ProducerOutput(producerOutput), iron, 2)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0, new ItemStack(Items.IRON_INGOT), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(iron), iron, 1)))),
                List.of(), List.of(producerId, consumerId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void rejectsConsumerBeforeProducer() {
        NodeId producerId = new NodeId(0);
        NodeId consumerId = new NodeId(1);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        InputPortId consumerInput = new InputPortId(consumerId, 0);

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(
                        node(producerId, "producer", List.of(),
                                List.of(new OutputDeclaration(producerOutput, iron, 1, OutputKind.PRIMARY))),
                        node(consumerId, "consumer",
                                List.of(new InputDemand(consumerInput, Ingredient.of(Items.IRON_INGOT), 1,
                                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), consumerInput,
                        new MaterialSource.ProducerOutput(producerOutput), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0, new ItemStack(Items.IRON_INGOT), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(iron), iron, 1)))),
                List.of(), List.of(consumerId, producerId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void unresolvedQuantityCanCloseAnInputDemand() {
        NodeId nodeId = new NodeId(0);
        InputPortId inputId = new InputPortId(nodeId, 0);
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        CraftNode node = node(nodeId, "node",
                List.of(new InputDemand(inputId, Ingredient.of(Items.DIAMOND), 3,
                        DemandRole.CONSUMED, new ItemStack(Items.DIAMOND))), List.of());
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0, new ItemStack(Items.STICK), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(target), target, 1)))),
                List.of(new UnresolvedDemand(inputId, Ingredient.of(Items.DIAMOND), 3,
                        new ItemStack(Items.DIAMOND))), List.of(nodeId));

        CraftPlanValidator.validate(graph);
    }

    @Test
    void rejectsCyclicDependency() {
        // A consumes B's output; B consumes A's output — no topological order exists.
        NodeId a = new NodeId(0);
        NodeId b = new NodeId(1);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        InputPortId aIn = new InputPortId(a, 0);
        OutputPortId aOut = new OutputPortId(a, 0);
        InputPortId bIn = new InputPortId(b, 0);
        OutputPortId bOut = new OutputPortId(b, 0);
        MaterialKey stick = MaterialKey.of(new ItemStack(Items.STICK));

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(
                        node(a, "a",
                                List.of(new InputDemand(aIn, Ingredient.of(Items.IRON_INGOT), 1,
                                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                                List.of(new OutputDeclaration(aOut, iron, 1, OutputKind.PRIMARY))),
                        node(b, "b",
                                List.of(new InputDemand(bIn, Ingredient.of(Items.IRON_INGOT), 1,
                                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))),
                                List.of(new OutputDeclaration(bOut, iron, 1, OutputKind.PRIMARY)))),
                List.of(
                        new MaterialAllocation(new AllocationId(0), aIn,
                                new MaterialSource.ProducerOutput(bOut), iron, 1),
                        new MaterialAllocation(new AllocationId(1), bIn,
                                new MaterialSource.ProducerOutput(aOut), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0, new ItemStack(Items.STICK), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(stick), stick, 1)))),
                List.of(), List.of(a, b));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void rejectsAllocationWithMissingConsumer() {
        NodeId nodeId = new NodeId(0);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        // Allocation targets an input port that no node declares.
        InputPortId danglingInput = new InputPortId(new NodeId(99), 0);

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(nodeId, "node", List.of(), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), danglingInput,
                        new MaterialSource.InitialPool(iron), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0, new ItemStack(Items.IRON_INGOT), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(iron), iron, 1)))),
                List.of(), List.of(nodeId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void rejectsAllocationReferencingMissingProducerOutput() {
        NodeId nodeId = new NodeId(0);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        InputPortId inputId = new InputPortId(nodeId, 0);
        // Producer output port that no node declares.
        OutputPortId danglingOutput = new OutputPortId(new NodeId(99), 0);

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(nodeId, "node",
                        List.of(new InputDemand(inputId, Ingredient.of(Items.IRON_INGOT), 1,
                                DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), inputId,
                        new MaterialSource.ProducerOutput(danglingOutput), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0, new ItemStack(Items.IRON_INGOT), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(iron), iron, 1)))),
                List.of(), List.of(nodeId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void rejectsRootQuantityShortfall() {
        MaterialKey stick = MaterialKey.of(new ItemStack(Items.STICK));
        // Root wants 5 but is supplied only 1 with no unresolved remainder.
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 5, 0, new ItemStack(Items.STICK), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(stick), stick, 1)))),
                List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void rejectsInputQuantityShortfall() {
        NodeId nodeId = new NodeId(0);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        InputPortId inputId = new InputPortId(nodeId, 0);
        // Input needs 3, only 1 allocated, nothing unresolved.
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(nodeId, "node",
                        List.of(new InputDemand(inputId, Ingredient.of(Items.IRON_INGOT), 3,
                                DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), inputId,
                        new MaterialSource.InitialPool(iron), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0, new ItemStack(Items.IRON_INGOT), List.of(
                        new RootAllocation(new MaterialSource.InitialPool(iron), iron, 1)))),
                List.of(), List.of(nodeId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return new CraftNode(id, new ResourceLocation("rsintegration", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(), false,
                null, null, inputs, outputs);
    }
}
