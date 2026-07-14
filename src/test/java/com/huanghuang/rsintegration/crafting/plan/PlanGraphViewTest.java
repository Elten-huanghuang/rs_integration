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

class PlanGraphViewTest extends BootstrapTest {

    @Test
    void fromGraphPreservesSharedProducerIdentityAndQuantities() {
        CraftPlanGraph graph = sharedProducerGraph();
        PlanGraphView view = PlanGraphView.from(graph);

        assertEquals(List.of(0, 1, 2), view.topologicalOrder());
        assertEquals(3, view.nodes().size());
        assertEquals(2, view.edges().size());
        assertEquals(0, view.edges().get(0).source().producerNodeId());
        assertEquals(1, view.edges().get(0).quantity());
        assertEquals(0, view.edges().get(1).source().producerNodeId());
        assertEquals(2, view.edges().get(1).quantity());
        assertFalse(view.edges().get(0).source().initial());
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

        PlanResponse decoded = PlanResponsePacket.decode(buf).plan();
        assertNotNull(decoded.graph());
        assertEquals(graph.topologicalOrder(), decoded.graph().topologicalOrder());
        assertEquals(graph.edges().size(), decoded.graph().edges().size());
        assertEquals(graph.edges().get(1).quantity(), decoded.graph().edges().get(1).quantity());
        assertEquals(tag, decoded.graph().nodes().get(0).primaryOutput().getTag());
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
                List.of(new OutputDeclaration(producerOutput, iron, 3, OutputKind.PRIMARY)));
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
        return new CraftNode(id, new ResourceLocation("test", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1,
                List.of(), List.of(), false, null, null, inputs, outputs);
    }
}
