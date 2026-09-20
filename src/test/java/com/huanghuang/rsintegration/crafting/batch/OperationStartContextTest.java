package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationStartContextTest extends BootstrapTest {

    @Test
    void delegateExtractedContextHasNoLegacyOrBufferedMaterials() {
        OperationStartContext context = OperationStartContext.delegateExtracted(
                mock(ServerPlayer.class), new ExtractionLedger());

        assertEquals(OperationStartContext.MaterialOwnership.DELEGATE_EXTRACTED,
                context.materialOwnership());
        assertTrue(context.materialPlan().entries().isEmpty());
        assertTrue(context.legacyMaterials().isEmpty());
        assertFalse(context.buffered());
    }

    @Test
    void delegateExtractedStartPreservesThePrivateExtractionEntryPoint() {
        ServerPlayer player = mock(ServerPlayer.class);
        ExtractionLedger ledger = new ExtractionLedger();
        IBatchDelegate delegate = mock(IBatchDelegate.class, CALLS_REAL_METHODS);
        when(delegate.tryStartSingleCraft(player)).thenReturn(true);

        assertTrue(delegate.startOperation(
                OperationStartContext.delegateExtracted(player, ledger)));

        verify(delegate).tryStartSingleCraft(player);
        verify(delegate, never()).tryStartSingleCraft(player, ledger);
    }

    @Test
    void bindsBufferedInputByStableEntryId() {
        MaterialPlan materialPlan = new MaterialPlan(List.of(
                new MaterialPlan.Entry("ore", new IngredientSpec(
                        Ingredient.of(Items.RAW_IRON), 1),
                        MaterialPlan.Allocation.GRAPH, false, 0)));
        InputBufferPlan inputBuffer = new InputBufferPlan(6,
                List.of(new InputBufferPlan.InputSlot(
                        "ore", 0, new ItemStack(Items.IRON_ORE, 6), 1, false)),
                List.of());

        OperationStartContext context = OperationStartContext.chainReserved(
                mock(ServerPlayer.class), new ExtractionLedger(), materialPlan,
                List.of(new ItemStack(Items.RAW_IRON, 6)), inputBuffer);

        assertTrue(context.buffered());
        assertTrue(context.inputBufferPlan().inputs().get(0).stack().is(Items.RAW_IRON));
        assertEquals(6, context.inputBufferPlan().inputs().get(0).stack().getCount());
        assertTrue(context.legacyMaterials().get(0).is(Items.RAW_IRON));
    }

    @Test
    void restoresLegacySlotsWhenOrderedListContainsEmptyPlaceholders() {
        MaterialPlan materialPlan = MaterialPlan.fromLegacy(List.of(
                        IngredientSpec.EMPTY,
                        new IngredientSpec(Ingredient.of(Items.COAL), 1)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                        IBatchDelegate.MaterialReservationScope.PER_OPERATION));

        OperationStartContext context = OperationStartContext.chainReserved(
                mock(ServerPlayer.class), new ExtractionLedger(), materialPlan,
                List.of(ItemStack.EMPTY, new ItemStack(Items.COAL)), InputBufferPlan.none());

        assertEquals(List.of(Items.COAL), context.legacyMaterials().stream()
                .map(ItemStack::getItem).toList());
    }

    @Test
    void rejectsBufferedPlanThatReferencesAnotherMaterialEntry() {
        MaterialPlan materialPlan = MaterialPlan.fromLegacy(List.of(
                        new IngredientSpec(Ingredient.of(Items.RAW_IRON), 1)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION));
        InputBufferPlan wrongEntry = new InputBufferPlan(2,
                List.of(new InputBufferPlan.InputSlot(
                        "other", 0, new ItemStack(Items.RAW_IRON, 2), 1, false)),
                List.of());

        assertThrows(IllegalArgumentException.class, () ->
                OperationStartContext.chainReserved(mock(ServerPlayer.class),
                        new ExtractionLedger(), materialPlan,
                        List.of(new ItemStack(Items.RAW_IRON, 2)), wrongEntry));
    }

    @Test
    void repeatedPlanPreservesOperationRowsAndLegacyEmptySlots() {
        MaterialPlan perOperation = MaterialPlan.fromLegacy(List.of(
                        IngredientSpec.EMPTY,
                        new IngredientSpec(Ingredient.of(Items.COAL, Items.CHARCOAL), 1)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                        IBatchDelegate.MaterialReservationScope.PER_OPERATION));

        OperationStartContext context = OperationStartContext.repeatedChainReserved(
                mock(ServerPlayer.class), new ExtractionLedger(), perOperation,
                2, 2, List.of(ItemStack.EMPTY, new ItemStack(Items.COAL),
                        ItemStack.EMPTY, new ItemStack(Items.CHARCOAL)));

        RepeatedOperationPlan repeated = context.repeatedOperationPlan();
        assertEquals(2, repeated.operations());
        assertEquals(2, repeated.legacyStride());
        assertTrue(repeated.legacyMaterials(0).get(0).isEmpty());
        assertTrue(repeated.legacyMaterials(0).get(1).is(Items.COAL));
        assertTrue(repeated.legacyMaterials(1).get(1).is(Items.CHARCOAL));
    }

    @Test
    void repeatedPlanRejectsWrongMatrixSizeAndDefaultDelegateCannotConsumeIt() {
        MaterialPlan perOperation = MaterialPlan.fromLegacy(List.of(
                        new IngredientSpec(Ingredient.of(Items.COAL), 1)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION));
        ServerPlayer player = mock(ServerPlayer.class);
        ExtractionLedger ledger = new ExtractionLedger();

        assertThrows(IllegalArgumentException.class, () ->
                OperationStartContext.repeatedChainReserved(player, ledger,
                        perOperation, 2, 1, List.of(new ItemStack(Items.COAL))));

        OperationStartContext context = OperationStartContext.repeatedChainReserved(
                player, ledger, perOperation, 2, 1,
                List.of(new ItemStack(Items.COAL), new ItemStack(Items.COAL)));
        IBatchDelegate delegate = mock(IBatchDelegate.class, CALLS_REAL_METHODS);
        assertFalse(delegate.startOperation(context));
        verify(delegate, never()).tryStartWithMaterials(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedPlanRejectsWrongIngredientAndCountBeforeDispatch() {
        MaterialPlan perOperation = MaterialPlan.fromLegacy(List.of(
                        new IngredientSpec(Ingredient.of(Items.COAL), 2)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION));
        ServerPlayer player = mock(ServerPlayer.class);
        ExtractionLedger ledger = new ExtractionLedger();

        assertThrows(IllegalArgumentException.class, () ->
                OperationStartContext.repeatedChainReserved(player, ledger,
                        perOperation, 1, 1, List.of(new ItemStack(Items.CHARCOAL, 2))));
        assertThrows(IllegalArgumentException.class, () ->
                OperationStartContext.repeatedChainReserved(player, ledger,
                        perOperation, 1, 1, List.of(new ItemStack(Items.COAL, 1))));
    }
}
