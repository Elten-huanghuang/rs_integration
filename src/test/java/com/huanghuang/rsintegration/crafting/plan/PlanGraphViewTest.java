package com.huanghuang.rsintegration.crafting.plan;

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
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanGraphViewTest extends BootstrapTest {

    @Test
    void fromGraphPreservesSharedProducerIdentityAndQuantities() {
        CraftPlanGraph graph = sharedProducerGraph();
        PlanGraphView view = PlanGraphView.from(graph);

        assertEquals(List.of(0, 1, 2), view.topologicalOrder());
        assertEquals(3, view.nodes().size());
        assertEquals(List.of(new ResourceLocation("test", "alternate")),
                view.nodes().get(0).alternativeIds());
        assertEquals(List.of(ModType.GENERIC.id()),
                view.nodes().get(0).alternativeModTypeIds());
        assertEquals(view.nodes().get(0).alternativeIds(),
                view.nodes().get(0).asPlanStep().alternatives());
        assertEquals(2, view.edges().size());
        assertEquals(0, view.edges().get(0).source().producerNodeId());
        assertEquals(1, view.edges().get(0).quantity());
        assertEquals(0, view.edges().get(1).source().producerNodeId());
        assertEquals(2, view.edges().get(1).quantity());
        assertFalse(view.edges().get(0).source().initial());
    }

    @Test
    void cardStepConvertsGraphTotalsToPerExecutionQuantities() {
        NodeId nodeId = new NodeId(0);
        InputPortId inputId = new InputPortId(nodeId, 0);
        OutputPortId outputId = new OutputPortId(nodeId, 0);
        MaterialKey clock = MaterialKey.of(new ItemStack(Items.CLOCK));
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("minecraft", "clock"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"), 6,
                List.of(), List.of(), false, null, null,
                List.of(new InputDemand(inputId, Ingredient.of(Items.REDSTONE), 6,
                        DemandRole.CONSUMED, new ItemStack(Items.REDSTONE))),
                List.of(new OutputDeclaration(outputId, clock, 6, OutputKind.PRIMARY)));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.CLOCK), 6, 0,
                        new ItemStack(Items.CLOCK), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(outputId), clock, 6)))),
                List.of(), List.of(nodeId));

        PlanStep step = PlanGraphView.from(graph).nodes().get(0).asPlanStep();

        assertEquals(6, step.batches());
        assertEquals(1, step.output().getCount());
        assertEquals(1, step.inputs().get(0).getCount());
        assertEquals(6, step.totalOutputCount());
        assertEquals(6, step.totalInputCount());
    }

    @Test
    void packetRoundTripPreservesGraphView() {
        PlanGraphView graph = PlanGraphView.from(sharedProducerGraph());
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "round-trip");
        graph.nodes().get(0).primaryOutput().setTag(tag);
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(), java.util.Map.of(), List.of(), "test:root",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), java.util.Map.of(), null, graph);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new PlanResponsePacket(plan).encode(buf);
        int encodedSize = buf.readableBytes();

        // Decode and check graph decoded correctly
        PlanResponse decoded = PlanResponsePacket.decode(buf).plan();
        assertNotNull(decoded.graph());
        assertEquals(graph.topologicalOrder(), decoded.graph().topologicalOrder());
        assertEquals(graph.edges().size(), decoded.graph().edges().size());
        assertEquals(graph.edges().get(1).quantity(), decoded.graph().edges().get(1).quantity());
        assertEquals(tag, decoded.graph().nodes().get(0).primaryOutput().getTag());
        assertEquals(graph.nodes().get(0).alternativeIds(),
                decoded.graph().nodes().get(0).alternativeIds());
        assertEquals(graph.nodes().get(0).alternativeModTypeIds(),
                decoded.graph().nodes().get(0).alternativeModTypeIds());
        assertEquals(graph.nodes().get(0).primaryOutput().getCount(),
                decoded.graph().nodes().get(0).primaryOutput().getCount());
        assertEquals(1, decoded.graph().nodes().get(0).outputs().get(0).display().getCount());
        assertEquals(3, decoded.graph().nodes().get(0).outputs().get(0).quantity());
        assertEquals(1, decoded.graph().edges().get(1).material().getCount());
        assertEquals(2, decoded.graph().edges().get(1).quantity());
        assertEquals(0, buf.readableBytes(), "encoded=" + encodedSize);
    }

    @Test
    void decodeRejectsTrailingBytes() {
        // Encode a valid packet, then append trailing garbage
        PlanGraphView graph = PlanGraphView.from(sharedProducerGraph());
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(), java.util.Map.of(), List.of(), "test:root",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), java.util.Map.of(), null, graph);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new PlanResponsePacket(plan).encode(buf);
        // Append one trailing byte
        buf.writeByte(0xFF);
        assertThrows(io.netty.handler.codec.DecoderException.class, () -> PlanResponsePacket.decode(buf));
    }

    @Test
    void decodeRejectsTruncatedRequestIdMarker() {
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(), java.util.Map.of(), List.of(), "test:root",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), java.util.Map.of(), null, null);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new PlanResponsePacket(plan).encode(buf);
        buf.writerIndex(buf.writerIndex() - 1);

        assertThrows(IndexOutOfBoundsException.class, () -> PlanResponsePacket.decode(buf));
    }

    @Test
    void packetRoundTripPreservesRequestId() {
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(), java.util.Map.of(), List.of(), "test:root",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), java.util.Map.of(), null, null);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new PlanResponsePacket(plan, 42L).encode(buf);

        assertEquals(42L, PlanResponsePacket.decode(buf).requestId());
        assertEquals(0, buf.readableBytes());
    }

    private static CraftPlanGraph sharedProducerGraph() {
        NodeId producerId = new NodeId(0);
        NodeId leftId = new NodeId(1);
        NodeId rightId = new NodeId(2);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        OutputPortId producerOutput = new OutputPortId(producerId, 0);
        InputPortId leftInput = new InputPortId(leftId, 0);
        InputPortId rightInput = new InputPortId(rightId, 0);
        OutputPortId leftOutput = new OutputPortId(leftId, 0);
        OutputPortId rightOutput = new OutputPortId(rightId, 0);
        CraftNode producer = node(producerId, "producer", List.of(),
                List.of(new OutputDeclaration(producerOutput, iron, 3, OutputKind.PRIMARY)), true);
        CraftNode left = node(leftId, "left", List.of(input(leftInput, 1)),
                List.of(new OutputDeclaration(leftOutput, diamond, 1, OutputKind.PRIMARY)));
        CraftNode right = node(rightId, "right", List.of(input(rightInput, 2)),
                List.of(new OutputDeclaration(rightOutput, diamond, 1, OutputKind.PRIMARY)));
        return new CraftPlanGraph(1, List.of(producer, left, right), List.of(
                new MaterialAllocation(new AllocationId(0), leftInput,
                        new MaterialSource.ProducerOutput(producerOutput), iron, 1),
                new MaterialAllocation(new AllocationId(1), rightInput,
                        new MaterialSource.ProducerOutput(producerOutput), iron, 2)),
                List.of(new RootDemand(Ingredient.of(Items.DIAMOND), 1, 0,
                        new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(leftOutput), diamond, 1)))),
                List.of(), List.of(producerId, leftId, rightId));
    }

    private static InputDemand input(InputPortId id, int quantity) {
        return new InputDemand(id, Ingredient.of(Items.IRON_INGOT), quantity,
                DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT));
    }

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return node(id, path, inputs, outputs, false);
    }

    private static CraftNode node(NodeId id, String path, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs, boolean withAlternative) {
        return new CraftNode(id, new ResourceLocation("test", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1,
                withAlternative ? List.of(new ResourceLocation("test", "alternate")) : List.of(),
                withAlternative ? List.of(ModType.GENERIC.id()) : List.of(),
                false, null, null, inputs, outputs);
    }
}
