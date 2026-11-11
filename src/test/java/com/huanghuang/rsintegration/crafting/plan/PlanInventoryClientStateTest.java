package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.client.recipe.JeiCraftingPlanContext;
import com.huanghuang.rsintegration.client.recipe.JeiNetworkItemCache;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.initial;
import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.plan;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlanInventoryClientStateTest extends BootstrapTest {
    @Test
    void temporaryDisconnectRetainsThePlanAndDoesNotRequestAnotherSearch() {
        var original = source();
        var state = new PlanInventoryClientState(10, 11, original);
        assertTrue(state.accept(new PlanInventoryUpdatePacket(10, 11, 1, true,
                PreviewInventoryModel.Status.UNAVAILABLE, 0, 1, List.of())));
        assertSame(original.graph(), state.view().graph());
        assertFalse(PlanConfirmationAction.forInventory(state.status()).replan);
        assertEquals("rsi.plan.inventory.check_confirm", PlanConfirmationAction.forInventory(state.status()).labelKey);
        assertTrue(state.accept(new PlanInventoryUpdatePacket(10, 11, 2, true,
                PreviewInventoryModel.Status.READY, 0, 1, List.of(
                new PlanInventoryUpdatePacket.Entry(0, 120, 0), new PlanInventoryUpdatePacket.Entry(1, 2, 0)))));
        assertEquals(PlanConfirmationAction.CONFIRM, PlanConfirmationAction.forInventory(state.status()));
    }

    @Test
    void actualExpiryRequestsNewPlanningWithoutClearingTheDisplayedTreeOrResyncLoop() {
        var original = source();
        var state = new PlanInventoryClientState(10, 11, original);
        assertTrue(state.accept(new PlanInventoryUpdatePacket(10, 11, 1, true,
                PreviewInventoryModel.Status.EXPIRED, 0, 1, List.of())));
        assertFalse(state.needsResync());
        assertSame(original.graph(), state.view().graph());
        assertEquals(PlanConfirmationAction.REBUILD_EXPIRED, PlanConfirmationAction.forInventory(state.status()));
        assertTrue(PlanConfirmationAction.forInventory(state.status()).replan);
    }

    @Test
    void pipeShortageCanRecoverToConfirmationWithoutDiscardingTheRoute() {
        var state = new PlanInventoryClientState(10, 11, source());
        assertTrue(state.accept(packet(10, 11, 1, true, 0, 1,
                new PlanInventoryUpdatePacket.Entry(0, 90, 10), new PlanInventoryUpdatePacket.Entry(1, 2, 0))));
        assertEquals(PlanConfirmationAction.FIND_ALTERNATIVE, PlanConfirmationAction.forInventory(state.status()));
        assertTrue(state.accept(new PlanInventoryUpdatePacket(10, 11, 2, false,
                PreviewInventoryModel.Status.READY, 0, 1, List.of(new PlanInventoryUpdatePacket.Entry(0, 120, 0)))));
        assertFalse(PlanConfirmationAction.forInventory(state.status()).replan);
        assertEquals(PlanConfirmationAction.COMPLETE_ROUTE,
                PlanConfirmationAction.forInventory(PreviewInventoryModel.Status.REPLAN));
    }

    @Test
    void jeiDemandContextAppliesOnlyChangedMaterials() {
        var source = source();
        var state = new PlanInventoryClientState(10, 11, source);
        var context = JeiCraftingPlanContext.INSTANCE;
        try {
            context.activate(source);
            assertTrue(state.accept(packet(10, 11, 1, true, 0, 1,
                    new PlanInventoryUpdatePacket.Entry(0, 120, 0), new PlanInventoryUpdatePacket.Entry(1, 2, 0))));
            context.updateMaterials(state.view(), state.changed());
            assertTrue(state.accept(packet(10, 11, 2, false, 0, 1, new PlanInventoryUpdatePacket.Entry(0, 90, 10))));
            context.updateMaterials(state.view(), state.changed());
            var inventory = mock(JeiNetworkItemCache.class);
            assertEquals(10, context.demand(new ItemStack(Items.IRON_INGOT), inventory).missing());
            assertEquals(2, context.demand(new ItemStack(Items.COAL), inventory).plannedAvailable());
        } finally { context.clear(); }
    }

    private static PlanResponse source() {
        return plan(List.of(initial(new ItemStack(Items.IRON_INGOT), 100), initial(new ItemStack(Items.COAL), 2)), List.of(), Map.of());
    }

    private static PlanInventoryUpdatePacket packet(long request, long watch, long sequence, boolean full,
                                                    int part, int parts, PlanInventoryUpdatePacket.Entry... entries) {
        return new PlanInventoryUpdatePacket(request, watch, sequence, full, PreviewInventoryModel.Status.MISSING,
                part, parts, List.of(entries));
    }

    @Test
    void completePartsApplyAtomicallyWithoutReplacingGraphOrMutatingOriginalResponse() {
        var original = source();
        var state = new PlanInventoryClientState(10, 11, original);
        var view = state.view();
        assertFalse(state.accept(packet(10, 11, 1, true, 1, 2, new PlanInventoryUpdatePacket.Entry(1, 2, 0))));
        assertEquals(0, view.materials().get(IngredientKey.of(new ItemStack(Items.COAL))).available());
        assertTrue(state.accept(packet(10, 11, 1, true, 0, 2, new PlanInventoryUpdatePacket.Entry(0, 90, 10))));
        assertSame(view, state.view());
        assertSame(original.graph(), state.view().graph());
        assertSame(original.steps(), state.view().steps());
        assertEquals(0, original.materials().get(IngredientKey.of(new ItemStack(Items.IRON_INGOT))).available());
        assertEquals(90, view.materials().get(IngredientKey.of(new ItemStack(Items.IRON_INGOT))).available());
    }

    @Test
    void oldRequestsAndOldWindowsCannotOverwriteCurrentCounts() {
        var state = new PlanInventoryClientState(10, 11, source());
        assertFalse(state.accept(packet(9, 11, 1, true, 0, 1)));
        assertFalse(state.accept(packet(10, 9, 1, true, 0, 1)));
        assertFalse(state.needsResync());
    }

    @Test
    void deltaChangesOnlyOneRowAndDuplicateDeliveryIsIgnored() {
        var state = new PlanInventoryClientState(10, 11, source());
        assertTrue(state.accept(packet(10, 11, 1, true, 0, 1,
                new PlanInventoryUpdatePacket.Entry(0, 120, 0), new PlanInventoryUpdatePacket.Entry(1, 2, 0))));
        var delta = packet(10, 11, 2, false, 0, 1, new PlanInventoryUpdatePacket.Entry(0, 90, 10));
        assertTrue(state.accept(delta));
        assertEquals(List.of(IngredientKey.of(new ItemStack(Items.IRON_INGOT))), state.changed());
        assertFalse(state.accept(delta));
        assertEquals(2, state.view().materials().get(IngredientKey.of(new ItemStack(Items.COAL))).available());
    }

    @Test
    void skippedSequenceRequestsResyncRatherThanApplyingAnIncompleteInventory() {
        var state = new PlanInventoryClientState(10, 11, source());
        assertFalse(state.accept(packet(10, 11, 2, false, 0, 1, new PlanInventoryUpdatePacket.Entry(0, 90, 10))));
        assertTrue(state.needsResync());
    }

    @Test
    void malformedFullUpdateDoesNotPartiallyApply() {
        var state = new PlanInventoryClientState(10, 11, source());
        assertFalse(state.accept(packet(10, 11, 1, true, 0, 1, new PlanInventoryUpdatePacket.Entry(0, 90, 10))));
        assertTrue(state.needsResync());
        assertEquals(0, state.view().materials().get(IngredientKey.of(new ItemStack(Items.IRON_INGOT))).available());
    }

    @Test
    void packetRoundTripPreservesLongCountsAndRejectsTrailingData() {
        var packet = packet(10, 11, 1, true, 0, 1, new PlanInventoryUpdatePacket.Entry(0, Long.MAX_VALUE, 123));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertEquals(packet, PlanInventoryUpdatePacket.decode(buffer));
            buffer.clear();
            packet.encode(buffer);
            buffer.writeByte(1);
            assertThrows(DecoderException.class, () -> PlanInventoryUpdatePacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void oversizedCountIsRejectedBeforeAllocatingRows() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarLong(1).writeVarLong(2).writeVarLong(1).writeBoolean(true);
            buffer.writeEnum(PreviewInventoryModel.Status.READY);
            buffer.writeVarInt(0).writeVarInt(1).writeVarInt(Integer.MAX_VALUE);
            assertThrows(DecoderException.class, () -> PlanInventoryUpdatePacket.decode(buffer));
        } finally { buffer.release(); }
    }
}
