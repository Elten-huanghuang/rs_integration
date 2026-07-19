package com.huanghuang.rsintegration.crafting.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void acceptsMixedInitialAndProducerSupplyWithSecondaryAndRemainderPorts() {
        NodeId producerId = new NodeId(0);
        NodeId consumerId = new NodeId(1);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey bucket = MaterialKey.of(new ItemStack(Items.BUCKET));
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        OutputPortId secondary = new OutputPortId(producerId, 0);
        OutputPortId remainder = new OutputPortId(producerId, 1);
        InputPortId ironInput = new InputPortId(consumerId, 0);
        InputPortId bucketInput = new InputPortId(consumerId, 1);
        CraftNode producer = node(producerId, "same_recipe", List.of(), List.of(
                new OutputDeclaration(secondary, iron, 2, OutputKind.SECONDARY),
                new OutputDeclaration(remainder, bucket, 1, OutputKind.REMAINDER)));
        CraftNode consumer = node(consumerId, "same_recipe", List.of(
                new InputDemand(ironInput, Ingredient.of(Items.IRON_INGOT), 3,
                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT)),
                new InputDemand(bucketInput, Ingredient.of(Items.BUCKET), 1,
                        DemandRole.CATALYST, new ItemStack(Items.BUCKET))), List.of());
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(producer, consumer), List.of(
                new MaterialAllocation(new AllocationId(0), ironInput,
                        new MaterialSource.InitialPool(iron), iron, 1),
                new MaterialAllocation(new AllocationId(1), ironInput,
                        new MaterialSource.ProducerOutput(secondary), iron, 2),
                new MaterialAllocation(new AllocationId(2), bucketInput,
                        new MaterialSource.ProducerOutput(remainder), bucket, 1)),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(), List.of(producerId, consumerId));

        CraftPlanValidator.validate(graph);
        assertEquals(2, graph.nodesById().size());
        assertEquals(List.of(producerId, consumerId), graph.topologicalOrder());
    }

    @Test
    void acceptsPartialAllocationClosedByUnresolvedQuantity() {
        NodeId nodeId = new NodeId(0);
        InputPortId input = new InputPortId(nodeId, 0);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(nodeId, "partial", List.of(new InputDemand(input,
                        Ingredient.of(Items.IRON_INGOT), 3, DemandRole.CONSUMED,
                        new ItemStack(Items.IRON_INGOT))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), input,
                        new MaterialSource.InitialPool(iron), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 2, 1,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(new UnresolvedDemand(input, Ingredient.of(Items.IRON_INGOT), 2,
                        new ItemStack(Items.IRON_INGOT))), List.of(nodeId));

        CraftPlanValidator.validate(graph);
    }

    @Test
    void rejectsProducerOverallocatedAcrossInputAndRoot() {
        NodeId producerId = new NodeId(0);
        NodeId consumerId = new NodeId(1);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        OutputPortId output = new OutputPortId(producerId, 0);
        InputPortId input = new InputPortId(consumerId, 0);
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(
                node(producerId, "producer", List.of(), List.of(
                        new OutputDeclaration(output, iron, 2, OutputKind.PRIMARY))),
                node(consumerId, "consumer", List.of(new InputDemand(input,
                        Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED,
                        new ItemStack(Items.IRON_INGOT))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), input,
                        new MaterialSource.ProducerOutput(output), iron, 1)),
                List.of(new RootDemand(Ingredient.of(Items.IRON_INGOT), 2, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(output), iron, 2)))),
                List.of(), List.of(producerId, consumerId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void nbtDistinctMaterialsCannotCrossAllocations() {
        NodeId nodeId = new NodeId(0);
        InputPortId input = new InputPortId(nodeId, 0);
        MaterialKey red = taggedDiamond("red");
        MaterialKey blue = taggedDiamond("blue");
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(node(nodeId, "nbt", List.of(new InputDemand(input,
                        Ingredient.of(red.toStack(1)), 1, DemandRole.CONSUMED, red.toStack(1))), List.of())),
                List.of(new MaterialAllocation(new AllocationId(0), input,
                        new MaterialSource.InitialPool(red), blue, 1)),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(), List.of(nodeId));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(graph));
    }

    @Test
    void acceptsSerializedSlashBladeMaterialForInputAndRootAllocations() {
        NodeId nodeId = new NodeId(0);
        InputPortId inputId = new InputPortId(nodeId, 0);
        Ingredient bladeIngredient = slashBladeIngredient(Items.DIAMOND);

        CompoundTag bladeState = new CompoundTag();
        bladeState.putString("translationKey", "item.minecraft.diamond");
        bladeState.putInt("killCount", 400);
        bladeState.putInt("RepairCounter", 4);
        CompoundTag fullTag = new CompoundTag();
        fullTag.put("bladeState", bladeState);
        ItemStack bladeStack = new ItemStack(Items.DIAMOND);
        bladeStack.setTag(fullTag);
        MaterialKey blade = MaterialKey.of(bladeStack);

        assertFalse(IngredientMatcher.test(bladeIngredient, blade.toStack(1)));
        assertTrue(IngredientMatcher.test(bladeIngredient, blade));

        CraftNode node = node(nodeId, "slashblade", List.of(new InputDemand(inputId,
                bladeIngredient, 1, DemandRole.CONSUMED, new ItemStack(Items.DIAMOND))), List.of());
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(
                new MaterialAllocation(new AllocationId(0), inputId,
                        new MaterialSource.InitialPool(blade), blade, 1)),
                List.of(new RootDemand(bladeIngredient, 1, 0, new ItemStack(Items.DIAMOND),
                        List.of(new RootAllocation(new MaterialSource.InitialPool(blade), blade, 1)))),
                List.of(), List.of(nodeId));

        CraftPlanValidator.validate(graph);
    }

    @Test
    void rejectsMissingAndDuplicateTopologicalNodes() {
        NodeId first = new NodeId(0);
        NodeId second = new NodeId(1);
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        List<CraftNode> nodes = List.of(node(first, "first", List.of(), List.of()),
                node(second, "second", List.of(), List.of()));
        RootDemand root = new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                new ItemStack(Items.STICK), List.of(new RootAllocation(
                new MaterialSource.InitialPool(target), target, 1)));

        CraftPlanGraph missing = new CraftPlanGraph(1, nodes, List.of(), List.of(root),
                List.of(), List.of(first));
        CraftPlanGraph duplicate = new CraftPlanGraph(1, nodes, List.of(), List.of(root),
                List.of(), List.of(first, first));

        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(missing));
        assertThrows(IllegalArgumentException.class, () -> CraftPlanValidator.validate(duplicate));
    }

    private static MaterialKey taggedDiamond(String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return MaterialKey.of(stack);
    }

    private static Ingredient slashBladeIngredient(Item item) {
        JsonObject request = new JsonObject();
        request.addProperty("name", "minecraft:diamond");
        request.addProperty("kill", 400);
        request.addProperty("refine", 4);
        JsonObject json = new JsonObject();
        json.addProperty("type", "slashblade:blade");
        json.add("request", request);
        return new SerializedIngredient(item, json);
    }

    private static final class SerializedIngredient extends Ingredient {
        private final JsonObject json;

        private SerializedIngredient(Item item, JsonObject json) {
            super(Stream.of(new ItemValue(new ItemStack(item))));
            this.json = json;
        }

        @Override
        public boolean test(ItemStack stack) {
            return false;
        }

        @Override
        public JsonElement toJson() {
            return json;
        }
    }

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return new CraftNode(id, new ResourceLocation("rsintegration", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(), false,
                null, null, inputs, outputs);
    }
}
