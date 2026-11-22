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
    void fourIngredientRecipeRequiresNewPreviewAndSeparateConfirmationAfterRestocking() {
        var wheat = new ItemStack(Items.WHEAT);
        var key = IngredientKey.of(wheat);
        var unresolved = new PlanGraphView.UnresolvedView(0, 0, wheat, 3);
        var oldPreview = plan(List.of(initial(wheat, 1)), List.of(unresolved), Map.of(key, 1L));
        var inventory = new PreviewInventoryModel(oldPreview);
        var requests = new PlanResponseRequestState(10, oldPreview.recipeId());
        assertEquals(PlanConfirmationAction.COMPLETE_ROUTE, PlanConfirmationAction.forPlan(oldPreview, null));
        assertEquals(PlanConfirmationAction.COMPLETE_ROUTE, PlanConfirmationAction.forPlan(
                oldPreview, PreviewInventoryModel.Status.UNAVAILABLE));
        assertFalse(oldPreview.canConfirmExecution());

        inventory.update(key, 1);
        assertEquals(3, inventory.drain(true).get(0).missing());
        inventory.update(key, 4);
        assertEquals(0, inventory.drain(false).get(0).missing());
        assertEquals(PreviewInventoryModel.Status.REPLAN, inventory.status());
        var action = PlanConfirmationAction.forPlan(oldPreview, inventory.status());
        assertEquals(PlanResponseRequestState.Confirmation.RECALCULATE,
                requests.confirmation(false, action.replan, oldPreview.canConfirmExecution()));
        requests.beginPreview(12, oldPreview.recipeId());
        assertEquals(PlanResponseRequestState.Confirmation.WAIT,
                requests.confirmation(false, action.replan, oldPreview.canConfirmExecution()));
        assertFalse(requests.accept(10, oldPreview.recipeId()));

        var newPreview = plan(List.of(initial(wheat, 4)), List.of(), Map.of(key, 4L));
        assertTrue(requests.accept(12, newPreview.recipeId()));
        var newAction = PlanConfirmationAction.forPlan(newPreview, null);
        assertEquals(PlanResponseRequestState.Confirmation.EXECUTE,
                requests.confirmation(false, newAction.replan, newPreview.canConfirmExecution()));
    }

    @Test
    void sameStockCannotClearTheConfirmedShortageShownForACraftingIngredient() {
        var wheat = new ItemStack(Items.WHEAT);
        var key = IngredientKey.of(wheat);
        var preview = plan(List.of(), List.of(new PlanGraphView.UnresolvedView(0, 0, wheat, 3)),
                Map.of(key, 4L));
        var inventory = new PreviewInventoryModel(preview);
        var client = new PlanInventoryClientState(10, 11, preview);

        inventory.update(key, 4);
        assertEquals(PreviewInventoryModel.Status.MISSING, inventory.status());
        assertTrue(client.accept(new PlanInventoryUpdatePacket(10, 11, 1, true,
                inventory.status(), 0, 1, inventory.drain(true))));
        var shortages = MissingMaterialBookmarkList.textEntries(client.view());
        assertEquals(1, shortages.size());
        assertEquals(Items.WHEAT, shortages.get(0).bookmark().getItem());
        assertEquals(3, shortages.get(0).missingCount());

        inventory.update(key, 5);
        assertTrue(client.accept(new PlanInventoryUpdatePacket(10, 11, 2, false,
                inventory.status(), 0, 1, inventory.drain(false))));
        assertEquals(2, MissingMaterialBookmarkList.textEntries(client.view()).get(0).missingCount());

        inventory.update(key, 7);
        assertEquals(PreviewInventoryModel.Status.REPLAN, inventory.status());
        assertTrue(client.accept(new PlanInventoryUpdatePacket(10, 11, 3, false,
                inventory.status(), 0, 1, inventory.drain(false))));
        assertTrue(MissingMaterialBookmarkList.textEntries(client.view()).isEmpty());
        assertTrue(MissingMaterialBookmarkList.unitemizedShortages(preview).isEmpty());
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
