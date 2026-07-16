package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerminalGraphComposerTest extends BootstrapTest {

    @Test
    void embedsProducerAndInitialRootsIntoTerminalNode() {
        NodeId producerId = new NodeId(4);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey stick = MaterialKey.of(new ItemStack(Items.STICK));
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        CraftNode producer = node(producerId, "producer", List.of(), List.of(
                new OutputDeclaration(producerOutput, iron, 2, OutputKind.PRIMARY)));
        CraftPlanGraph base = new CraftPlanGraph(1, List.of(producer), List.of(), List.of(
                new RootDemand(Ingredient.of(Items.IRON_INGOT), 2, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(producerOutput), iron, 2))),
                new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(stick), stick, 1)))),
                List.of(), List.of(producerId));

        CraftPlanGraph result = TerminalGraphComposer.compose(base,
                step("terminal", 1), new ItemStack(Items.IRON_SWORD));

        NodeId terminalId = new NodeId(5);
        assertEquals(List.of(producerId, terminalId), result.topologicalOrder());
        assertEquals(List.of(producerId), result.dependenciesOf(terminalId).stream().toList());
        assertEquals(2, result.nodesById().get(terminalId).inputs().size());
        assertEquals(1, result.rootDemands().size());
        assertEquals(Items.IRON_SWORD, result.rootDemands().get(0).displayHint().getItem());
        CraftPlanValidator.validate(result);
    }

    @Test
    void scalesTerminalOutputByExecutionsAndUsesNonCollidingAllocationIds() {
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        NodeId existingId = new NodeId(2);
        InputPortId existingInput = new InputPortId(existingId, 0);
        CraftNode existing = node(existingId, "existing", List.of(new InputDemand(existingInput,
                Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED,
                new ItemStack(Items.IRON_INGOT))), List.of());
        CraftPlanGraph base = new CraftPlanGraph(1, List.of(existing), List.of(
                new MaterialAllocation(new AllocationId(8), existingInput,
                        new MaterialSource.InitialPool(iron), iron, 1)), List.of(
                new RootDemand(Ingredient.of(Items.IRON_INGOT), 3, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(iron), iron, 3)))), List.of(), List.of(existingId));

        CraftPlanGraph result = TerminalGraphComposer.compose(base,
                step("terminal", 3), new ItemStack(Items.DIAMOND, 2));

        CraftNode terminal = result.nodesById().get(new NodeId(3));
        assertEquals(3, terminal.executions());
        assertEquals(6, terminal.outputs().get(0).quantity());
        assertEquals(9, result.allocations().get(1).id().value());
    }

    @Test
    void preservesExactNbtIdentityForTerminalOutput() {
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CraftPlanGraph base = new CraftPlanGraph(1, List.of(), List.of(), List.of(
                new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(iron), iron, 1)))), List.of(), List.of());
        ItemStack target = new ItemStack(Items.DIAMOND_SWORD);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("variant", "requested");
        target.setTag(tag);

        CraftPlanGraph result = TerminalGraphComposer.compose(base, step("terminal", 1), target);

        OutputDeclaration output = result.nodes().get(0).outputs().get(0);
        assertEquals("requested", output.material().toStack(1).getTag().getString("variant"));
        assertEquals(output.material(), result.rootDemands().get(0).allocations().get(0).material());
    }

    @Test
    void marksRandomTerminalOutputAsDynamic() {
        MaterialKey geode = MaterialKey.of(new ItemStack(Items.PRISMARINE));
        CraftPlanGraph base = new CraftPlanGraph(1, List.of(), List.of(), List.of(
                new RootDemand(Ingredient.of(Items.PRISMARINE), 1, 0,
                        new ItemStack(Items.PRISMARINE), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(geode), geode, 1)))), List.of(), List.of());

        CraftPlanGraph result = TerminalGraphComposer.compose(base, step("random", 1),
                new ItemStack(Items.SNIFFER_EGG), false);

        assertEquals(OutputKind.DYNAMIC, result.nodes().get(0).outputs().get(0).kind());
        CraftPlanValidator.validate(result);
    }

    @Test
    void rejectsUnresolvedOrUnknownTerminalShape() {
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CraftPlanGraph unresolved = new CraftPlanGraph(1, List.of(), List.of(), List.of(
                new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 1,
                        new ItemStack(Items.IRON_INGOT), List.of())), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> TerminalGraphComposer.compose(
                unresolved, step("terminal", 1), new ItemStack(Items.IRON_SWORD)));

        CraftPlanGraph resolved = new CraftPlanGraph(1, List.of(), List.of(), List.of(
                new RootDemand(Ingredient.of(Items.IRON_INGOT), 1, 0,
                        new ItemStack(Items.IRON_INGOT), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(iron), iron, 1)))), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> TerminalGraphComposer.compose(
                resolved, step("terminal", 1), ItemStack.EMPTY));
    }

    private static CraftingResolver.ResolutionStep step(String path, int executions) {
        return new CraftingResolver.ResolutionStep(new ResourceLocation("test", path),
                ModType.GENERIC, new ResourceLocation("test", "type"),
                List.of(), List.of(), false, executions);
    }

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return new CraftNode(id, new ResourceLocation("test", path), ModType.GENERIC.id(),
                new ResourceLocation("test", "type"), 1, List.of(), List.of(), false,
                null, null, inputs, outputs);
    }
}
