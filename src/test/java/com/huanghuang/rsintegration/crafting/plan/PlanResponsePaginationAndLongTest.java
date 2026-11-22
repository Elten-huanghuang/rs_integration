package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanResponsePaginationAndLongTest extends BootstrapTest {
    private static final long REQUEST_ID = 7_000_000_000L;

    @Test
    void payloadRoundTripPreservesLongPlanAndGraphQuantities() {
        ResourceLocation recipeId = ResourceLocation.parse("test:long_plan");
        ItemStack raw = new ItemStack(Items.COBBLESTONE, 9);
        ItemStack output = new ItemStack(Items.DIAMOND, 2);
        long batches = (long) Integer.MAX_VALUE + 123L;
        PlanStep step = new PlanStep(recipeId, output, batches, List.of(raw));

        IngredientKey materialKey = IngredientKey.of(raw);
        PlanResponse.Availability availability =
                new PlanResponse.Availability(3_000_000_001L, 2_000_000_002L, 1_000_000_003L);
        PlanGraphView graph = new PlanGraphView(1,
                List.of(new PlanGraphView.NodeView(0, recipeId, "generic", 4,
                        output,
                        List.of(new PlanGraphView.InputView(0, raw, 3_000_000_004L,
                                DemandRole.CONSUMED.ordinal())),
                        List.of(new PlanGraphView.OutputView(0, output, 4_000_000_005L,
                                OutputKind.PRIMARY.ordinal())))),
                List.of(new PlanGraphView.EdgeView(0, 0,
                        new PlanGraphView.SourceView(true, -1, -1), raw, 3_000_000_006L)),
                List.of(new PlanGraphView.RootView(output, 7_000_000_007L, 6_000_000_008L,
                        List.of(new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(true, -1, -1),
                                raw, 2_000_000_009L)))),
                List.of(new PlanGraphView.UnresolvedView(0, 0, raw, 7_000_000_010L)),
                List.of(0));
        PlanResponse plan = new PlanResponse(true, "Target", output, List.of(step),
                Map.of(materialKey, availability), List.of(), recipeId.toString(),
                null, null, 0, 0, 0, List.of(), 1, null, null, null,
                0L, false, false, false, null, Set.of(),
                Map.of(materialKey, 8_000_000_011L), null, graph,
                false, List.of(), Map.of(), null, List.of());

        PlanResponsePacket packet = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(plan, REQUEST_ID));
        PlanResponse decoded = packet.plan();

        assertEquals(REQUEST_ID, packet.requestId());
        assertEquals(List.of(4L, batches), decoded.steps().stream().map(PlanStep::batches).toList());
        assertEquals(batches, decoded.standaloneSteps().get(0).batches());
        assertEquals(availability, decoded.materials().get(materialKey));
        assertEquals(8_000_000_011L, decoded.leftovers().get(materialKey));
        PlanGraphView decodedGraph = decoded.graph();
        assertEquals(3_000_000_004L, decodedGraph.nodes().get(0).inputs().get(0).quantity());
        assertEquals(4_000_000_005L, decodedGraph.nodes().get(0).outputs().get(0).quantity());
        assertEquals(3_000_000_006L, decodedGraph.edges().get(0).quantity());
        assertEquals(7_000_000_007L, decodedGraph.roots().get(0).quantity());
        assertEquals(6_000_000_008L, decodedGraph.roots().get(0).unresolvedQuantity());
        assertEquals(2_000_000_009L,
                decodedGraph.roots().get(0).allocations().get(0).quantity());
        assertEquals(7_000_000_010L, decodedGraph.unresolved().get(0).quantity());
    }

    @Test
    void encodingShareTagsDoesNotChangeCachedSupplyIdentity() {
        ItemStack material = new ItemStack(registerMutatingShareTagItem());
        StackKey stocked = StackKey.of(material, true);
        var initial = PlanGraphView.SourceView.fromInitial();
        var edge = new PlanGraphView.EdgeView(0, 0, initial, material, 1);
        var allocation = new PlanGraphView.RootEdgeView(initial, material, 1);
        var node = new PlanGraphView.NodeView(0, ResourceLocation.parse("test:intermediate"),
                "generic", 1, new ItemStack(Items.GOLD_INGOT),
                List.of(new PlanGraphView.InputView(0, material, 1, DemandRole.CONSUMED.ordinal())),
                List.of(new PlanGraphView.OutputView(0, new ItemStack(Items.GOLD_INGOT), 1,
                        OutputKind.PRIMARY.ordinal())));
        var graph = new PlanGraphView(1, List.of(node), List.of(edge),
                List.of(new PlanGraphView.RootView(material, 1, 0, List.of(allocation))),
                List.of(), List.of(0));
        var plan = new PlanResponse(true, "Target", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(), List.of(), "test:share_tag", null, null,
                0, 0, 0, List.of(), 1, null, null, null, 0L,
                false, false, false, null, Set.of(), Map.of(), null, graph);

        var decodedGraph = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(plan, REQUEST_ID)).plan().graph();

        assertEquals(stocked, StackKey.of(edge.material(), true));
        assertEquals(stocked, StackKey.of(allocation.material(), true));
        assertTrue(decodedGraph.edges().get(0).material().getTag().contains("Inventory"));
        assertTrue(decodedGraph.roots().get(0).allocations().get(0)
                .material().getTag().contains("Inventory"));
    }

    private static Item registerMutatingShareTagItem() {
        var forgeItems = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        var items = (MappedRegistry<Item>) BuiltInRegistries.ITEM;
        var id = ResourceLocation.parse("test:mutating_share_tag");
        boolean locked = forgeItems.isLocked();
        forgeItems.unfreeze();
        items.unfreeze();
        try {
            // 模拟人偶的同步行为：直接把容器内容写入传入的物品堆。
            Item item = new Item(new Item.Properties().stacksTo(1)) {
                @Override
                public CompoundTag getShareTag(ItemStack stack) {
                    CompoundTag inventory = new CompoundTag();
                    inventory.putInt("Size", 1);
                    inventory.put("Items", new ListTag());
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.put("Inventory", inventory);
                    return tag;
                }
            };
            forgeItems.register(id, item);
            return item;
        } finally {
            items.freeze();
            if (locked) forgeItems.freeze();
        }
    }

    @Test
    void pagesReassembleOutOfOrderAndRejectConflictingDuplicates() {
        PlanResponsePageAssembler.resetForTests();
        byte[] data = new byte[PlanResponsePagePacket.PAGE_BYTES * 3 + 17];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        var pages = PlanResponsePagePacket.split(42L, REQUEST_ID, data);

        byte[] assembled = null;
        for (int i = pages.size() - 1; i >= 0; i--) {
            assembled = PlanResponsePageAssembler.accept(pages.get(i));
            if (i > 0) assertNull(assembled);
        }
        assertArrayEquals(data, assembled);

        PlanResponsePageAssembler.resetForTests();
        var first = pages.get(0);
        assertNull(PlanResponsePageAssembler.accept(first));
        assertNull(PlanResponsePageAssembler.accept(first));
        byte[] conflicting = first.payload();
        conflicting[0] ^= 0x7F;
        assertThrows(IllegalArgumentException.class,
                () -> PlanResponsePageAssembler.accept(new PlanResponsePagePacket(
                        first.sessionId(), first.requestId(), first.pageIndex(),
                        first.pageCount(), conflicting)));
        PlanResponsePageAssembler.resetForTests();
    }

    @Test
    void pagePacketRoundTripPreservesSessionAndChunk() {
        byte[] chunk = new byte[PlanResponsePagePacket.PAGE_BYTES];
        Arrays.fill(chunk, (byte) 0x5A);
        PlanResponsePagePacket page =
                new PlanResponsePagePacket(11L, REQUEST_ID, 2, 4, chunk);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            page.encode(buffer);
            PlanResponsePagePacket decoded = PlanResponsePagePacket.decode(buffer);
            assertEquals(page.sessionId(), decoded.sessionId());
            assertEquals(page.requestId(), decoded.requestId());
            assertEquals(page.pageIndex(), decoded.pageIndex());
            assertEquals(page.pageCount(), decoded.pageCount());
            assertArrayEquals(chunk, decoded.payload());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void splitRejectsPayloadAboveTheConfiguredLimit() {
        byte[] oversized = new byte[PlanResponsePagePacket.MAX_TOTAL_BYTES + 1];
        assertThrows(IllegalArgumentException.class,
                () -> PlanResponsePagePacket.split(1L, 0L, oversized));
        assertTrue(PlanResponsePagePacket.MAX_PAGES <= 64);
    }

    @Test
    void planPayloadAcceptsMoreThanFourThousandGraphNodes() {
        int nodeCount = 4104;
        ResourceLocation recipeId = ResourceLocation.parse("test:large_graph");
        List<PlanGraphView.NodeView> nodes = new ArrayList<>(nodeCount);
        List<Integer> topological = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new PlanGraphView.NodeView(i, recipeId, "generic", 1,
                    ItemStack.EMPTY, List.of(), List.of()));
            topological.add(i);
        }
        PlanGraphView graph = new PlanGraphView(1, nodes, List.of(), List.of(), List.of(),
                topological);
        PlanResponse plan = new PlanResponse(true, "Large", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(), List.of(), recipeId.toString(), null, null,
                0, 0, 0, List.of(), 1, null, null, null, 0L,
                false, false, false, null, Set.of(), Map.of(), null, graph);

        PlanResponse decoded = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(plan, REQUEST_ID)).plan();

        assertEquals(nodeCount, decoded.graph().nodes().size());
        assertEquals(nodeCount - 1, decoded.graph().nodes().get(nodeCount - 1).nodeId());
    }
}
