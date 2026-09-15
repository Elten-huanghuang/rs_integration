package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.storage.*;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtractionLedgerEndpointTest extends BootstrapTest {
    private final StorageBackendId backend = new StorageBackendId("test");
    private final List<ItemStack> refunds = new ArrayList<>();
    private StorageSession session;
    private CraftStorageEndpoint endpoint;
    private ServerPlayer player;
    private ExtractionLedger ledger;
    private final Ingredient ingredient = Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT);

    @BeforeEach
    void setUp() throws Exception {
        player = mock(ServerPlayer.class);
        var field = ServerPlayer.class.getField("server");
        field.setAccessible(true);
        field.set(player, mock(MinecraftServer.class));
        session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(backend, "ledger-test"));
        when(session.snapshotItems(player)).thenReturn(StorageSnapshotResult.success(
                new StorageSnapshot(backend, List.of(stored(named("first", 20)),
                        stored(named("second", 20)), stored(new ItemStack(Items.GOLD_INGOT, 20))))));
        doAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            refunds.add(stack.copy());
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        }).when(session).insert(eq(player), any(ItemStack.class), eq(false));
        endpoint = () -> session;
        ledger = new ExtractionLedger();
    }

    @Test
    void committedRefundPreservesEveryItemAndNbtFragmentAndIsIdempotent() {
        List<ItemStack> actual = List.of(named("first", 2), named("second", 1),
                new ItemStack(Items.GOLD_INGOT, 2));
        reserve(5, StorageOperationResult.extracted(StorageOperationMode.PERFORM, 5, actual));
        assertTrue(ledger.commit(null, player));
        ledger.refundCommitted(null, player);
        ledger.refundCommitted(null, player);
        assertStacks(actual, refunds);
    }

    @Test
    void fullCountFailureStillRollsBackIncludingRecoveryItems() {
        List<ItemStack> actual = List.of(named("first", 2), named("second", 3));
        ItemStack recovery = new ItemStack(Items.DIAMOND);
        reserve(5, StorageOperationResult.failedExtraction(StorageOperationMode.PERFORM, 5,
                StorageOperationStatus.INVALID_RESPONSE, actual, List.of(recovery),
                StorageDiagnosticCode.BACKEND_EXCEPTION));
        assertFalse(ledger.commit(null, player));
        assertEquals(ExtractionLedger.State.ROLLED_BACK, ledger.state());
        assertStacks(List.of(actual.get(0), actual.get(1), recovery), refunds);
    }

    @Test
    void indeterminateExtractionRefundsOnlyConfirmedAndRecoveryFragments() {
        ItemStack confirmed = named("second", 2);
        ItemStack recovery = new ItemStack(Items.DIAMOND);
        reserve(5, StorageOperationResult.indeterminateExtraction(5,
                List.of(confirmed), List.of(recovery), StorageDiagnosticCode.BACKEND_EXCEPTION));
        assertFalse(ledger.commit(null, player));
        assertStacks(List.of(confirmed, recovery), refunds);
    }

    @Test
    void laterPartialFailureRollsBackEarlierEntriesWithoutMergingVariants() {
        ItemStack first = named("first", 2);
        ItemStack second = named("second", 1);
        ledger.reserveFromEndpoint(ingredient, 2, endpoint, player);
        ledger.reserveFromEndpoint(ingredient, 2, endpoint, player);
        when(session.extractMatching(player, ingredient, 2, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2, List.of(first)),
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2, List.of(second)));
        assertFalse(ledger.commit(null, player));
        assertStacks(List.of(first, second), refunds);
    }

    @Test
    void recoveredRefundsRetainOnlyActualVariantQuantitiesAcrossRepeatedCleanup() {
        reserve(5, StorageOperationResult.extracted(StorageOperationMode.PERFORM, 5,
                List.of(named("first", 2), named("second", 3))));
        assertTrue(ledger.commit(null, player));
        ledger.retainCommittedRefunds(List.of(named("second", 2), named("unknown", 5)));
        ledger.retainCommittedRefunds(List.of(named("second", 3), named("first", 2)));
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("second", 2)), refunds);
    }

    @Test
    void releasingOneVariantDoesNotReleaseOtherVariantsInTheSameEntry() {
        reserve(5, StorageOperationResult.extracted(StorageOperationMode.PERFORM, 5,
                List.of(named("first", 2), named("second", 3))));
        assertTrue(ledger.commit(null, player));
        ledger.releaseCommittedEntries(List.of(named("second", 2)));
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("first", 2), named("second", 1)), refunds);
    }

    @Test
    void tokenSettlementDoesNotRefundAnotherOperationsFragments() {
        ledger.reserveFromEndpoint(ingredient, 2, endpoint, player);
        var firstToken = ledger.tokenSince(0);
        ledger.reserveFromEndpoint(ingredient, 2, endpoint, player);
        var secondToken = ledger.tokenSince(1);
        when(session.extractMatching(player, ingredient, 2, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2,
                        List.of(named("first", 1), named("second", 1))),
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2,
                        List.of(named("third", 2))));
        assertTrue(ledger.commit(null, player));
        ledger.settleCommitted(secondToken);
        ledger.refundCommitted(firstToken, null, player);
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("first", 1), named("second", 1)), refunds);
    }

    private void reserve(int count, StorageOperationResult result) {
        assertEquals(count, ledger.reserveFromEndpoint(ingredient, count, endpoint, player).getCount());
        when(session.extractMatching(player, ingredient, count, false)).thenReturn(result);
    }

    @Test
    void precheckFiltersUnionOfTypesWithoutWeakeningStrictOrPartialNbt() {
        Ingredient strict = net.minecraftforge.common.crafting.StrictNBTIngredient.of(named("first", 1));
        Ingredient partial = net.minecraftforge.common.crafting.PartialNBTIngredient.of(Items.IRON_INGOT,
                named("second", 1).getTag());
        Ingredient gold = Ingredient.of(Items.GOLD_INGOT);
        assertEquals(1, ledger.reserveFromEndpoint(strict, 1, endpoint, player).getCount());
        assertEquals(1, ledger.reserveFromEndpoint(partial, 1, endpoint, player).getCount());
        assertEquals(1, ledger.reserveFromEndpoint(gold, 1, endpoint, player).getCount());
        when(session.extractMatching(player, strict, 1, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of(named("first", 1))));
        when(session.extractMatching(player, partial, 1, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of(named("second", 1))));
        when(session.extractMatching(player, gold, 1, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of(new ItemStack(Items.GOLD_INGOT))));
        assertTrue(ledger.commit(null, player));
        verify(session).snapshotItems(player, java.util.Set.of(Items.IRON_INGOT, Items.GOLD_INGOT));
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("first", 1), named("second", 1), new ItemStack(Items.GOLD_INGOT)), refunds);
    }

    @Test
    void customPredicateForcesFullPrecheckEvenWhenItsDisplayItemIsDifferent() {
        Ingredient custom = new Ingredient(java.util.stream.Stream.of(
                new Ingredient.ItemValue(new ItemStack(Items.DIAMOND)))) {
            @Override public boolean test(ItemStack stack) { return stack.is(Items.GOLD_INGOT); }
        };
        assertEquals(2, ledger.reserveFromEndpoint(custom, 2, endpoint, player).getCount());
        when(session.extractMatching(player, custom, 2, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2,
                        List.of(new ItemStack(Items.GOLD_INGOT, 2))));
        assertTrue(ledger.commit(null, player));
        verify(session, times(2)).snapshotItems(player);
        verify(session, never()).snapshotItems(eq(player), anySet());
    }

    @Test
    void precheckRejectsChangedNbtBeforeAnyPhysicalExtraction() {
        Ingredient strict = net.minecraftforge.common.crafting.StrictNBTIngredient.of(named("first", 1));
        assertEquals(1, ledger.reserveFromEndpoint(strict, 1, endpoint, player).getCount());
        when(session.snapshotItems(player, java.util.Set.of(Items.IRON_INGOT))).thenReturn(
                StorageSnapshotResult.success(new StorageSnapshot(backend, List.of(stored(named("second", 20))))));
        assertFalse(ledger.commit(null, player));
        verify(session, never()).extractMatching(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void cancelledCatalystReservationDoesNotConsumeItemsOrLeaveAnUnfinishedMirror() throws Exception {
        Ingredient catalyst = net.minecraftforge.common.crafting.StrictNBTIngredient.of(named("first", 1));
        assertEquals(1, ledger.reserveFromEndpoint(catalyst, 1, endpoint, player).getCount());
        ledger.cancelLastReservation();
        assertEquals(1, ledger.reserveFromEndpoint(catalyst, 1, endpoint, player).getCount());
        ledger.cancelLastReservation();
        reserve(2, StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2, List.of(named("second", 2))));
        assertTrue(ledger.commit(null, player));
        verify(session, never()).extractMatching(player, catalyst, 1, false);
        var field = ExtractionLedger.class.getDeclaredField("settlementLedger");
        field.setAccessible(true);
        StorageSettlementLedger mirror = (StorageSettlementLedger) field.get(ledger);
        assertEquals(StorageSettlementLedger.State.COMMITTED, mirror.state());
        assertEquals(1, mirror.size());
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("second", 2)), refunds);
    }

    @Test
    void changedResonanceCatalystIsCheckedAfterNetworkPrecheck() {
        ItemStack catalystStack = new ItemStack(Items.STONE_SWORD);
        catalystStack.getOrCreateTag().putString("owner", "first");
        Ingredient catalyst = net.minecraftforge.common.crafting.StrictNBTIngredient.of(catalystStack);
        var view = mock(com.huanghuang.rsintegration.resonance.api.ResonanceStorageView.class);
        when(view.backendId()).thenReturn("test");
        when(view.storedStacks()).thenReturn(List.of(
                new com.huanghuang.rsintegration.resonance.api.ResonanceStorageView.StoredStack(0, catalystStack)));
        when(view.extractExactView(anyInt(), any(), anyInt(), eq(true))).thenReturn(ItemStack.EMPTY);
        try (var sources = mockStatic(ResonanceCraftingSource.class)) {
            sources.when(() -> ResonanceCraftingSource.viewsFor(endpoint, player)).thenReturn(List.of(view));
            ledger.preferResonanceFor(catalyst);
            assertEquals(1, ledger.reserveFromEndpoint(catalyst, 1, endpoint, player).getCount());
            assertEquals(1, ledger.reserveFromEndpoint(ingredient, 1, endpoint, player).getCount());
            assertFalse(ledger.commit(null, player));
            verify(view).extractExactView(eq(0), any(), eq(1), eq(true));
            verify(view, never()).extractExactView(anyInt(), any(), anyInt(), eq(false));
            verify(session, never()).extractMatching(any(), any(), anyLong(), anyBoolean());
        }
    }

    @Test
    void rollbackExceptionDoesNotRepeatEarlierRefundsOrSkipLaterFragments() {
        reserve(5, StorageOperationResult.failedExtraction(StorageOperationMode.PERFORM, 5,
                StorageOperationStatus.INVALID_RESPONSE,
                List.of(named("first", 1), named("second", 2), named("third", 2)), List.of(),
                StorageDiagnosticCode.BACKEND_EXCEPTION));
        doAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            refunds.add(stack.copy());
            if (stack.getTag().getString("variant").equals("second")) throw new IllegalStateException("after insert");
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        }).when(session).insert(eq(player), any(ItemStack.class), eq(false));
        assertFalse(ledger.commit(null, player));
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("first", 1), named("second", 2), named("third", 2)), refunds);
    }

    @Test
    void committedRefundExceptionDoesNotRetryOrSkipOtherFragments() {
        reserve(3, StorageOperationResult.extracted(StorageOperationMode.PERFORM, 3,
                List.of(named("first", 1), named("second", 2))));
        assertTrue(ledger.commit(null, player));
        doAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            refunds.add(stack.copy());
            throw new IllegalStateException("after insert");
        }).when(session).insert(eq(player), any(ItemStack.class), eq(false));
        ledger.refundCommitted(null, player);
        ledger.refundCommitted(null, player);
        assertStacks(List.of(named("first", 1), named("second", 2)), refunds);
    }

    @Test
    void releasingLatestMatchingFragmentsPreservesEarlierTokenOwnership() {
        ledger.reserveFromEndpoint(ingredient, 2, endpoint, player);
        var firstToken = ledger.tokenSince(0);
        ledger.reserveFromEndpoint(ingredient, 3, endpoint, player);
        var secondToken = ledger.tokenSince(1);
        when(session.extractMatching(player, ingredient, 2, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2, List.of(named("same", 2))));
        when(session.extractMatching(player, ingredient, 3, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 3, List.of(named("same", 3))));
        assertTrue(ledger.commit(null, player));
        ledger.releaseCommittedEntries(List.of(named("same", 2)));
        ledger.refundCommitted(secondToken, null, player);
        assertStacks(List.of(named("same", 1)), refunds);
        ledger.refundCommitted(firstToken, null, player);
        assertStacks(List.of(named("same", 1), named("same", 2)), refunds);
    }

    private StoredItem stored(ItemStack stack) {
        return new StoredItem(StorageItemKey.fromItemStack(backend, stack), stack.getCount());
    }

    private static ItemStack named(String variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }

    private static void assertStacks(List<ItemStack> expected, List<ItemStack> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(ItemStack.isSameItemSameTags(expected.get(i), actual.get(i)), "fragment " + i);
            assertEquals(expected.get(i).getCount(), actual.get(i).getCount());
        }
    }
}
