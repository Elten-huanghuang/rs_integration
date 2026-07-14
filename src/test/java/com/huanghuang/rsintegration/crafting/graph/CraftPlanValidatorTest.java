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

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return new CraftNode(id, new ResourceLocation("rsintegration", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(), false,
                null, null, inputs, outputs);
    }
}
