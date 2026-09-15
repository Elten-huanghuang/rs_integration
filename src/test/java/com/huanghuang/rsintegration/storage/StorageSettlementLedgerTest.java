package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSettlementLedgerTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");

    @Test
    void cancellingMiddleAndLastReservationsPreservesOtherTokens() {
        var ledger = new StorageSettlementLedger();
        var first = ledger.reserve(source(), key(Items.DIAMOND), 1);
        var token = ledger.tokenSince(0);
        var middle = ledger.reserve(source(), key(Items.GOLD_INGOT), 1);
        var last = ledger.reserve(source(), key(Items.APPLE), 1);
        ledger.cancelReservation(middle);
        ledger.cancelReservation(last);
        assertEquals(1, ledger.size());
        assertTrue(ledger.entry(middle).isEmpty());
        assertTrue(ledger.entry(last).isEmpty());
        ledger.beginCommit();
        ledger.recordExtraction(first, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        assertEquals(StorageSettlementLedger.State.COMMITTED, ledger.finishCommit());
        ledger.settle(token);
        assertEquals(StorageSettlementLedger.State.SETTLED, ledger.state());
    }

    @Test
    void cancellationRejectsStaleAndForeignIdsWithoutChangingLiveReservations() {
        var ledger = new StorageSettlementLedger();
        var stale = ledger.reserve(source(), key(Items.DIAMOND), 1);
        ledger.cancelReservation(stale);
        var live = ledger.reserve(source(), key(Items.APPLE), 1);
        var foreign = new StorageSettlementLedger().reserve(source(), key(Items.APPLE), 1);
        assertThrows(IllegalArgumentException.class, () -> ledger.cancelReservation(stale));
        assertThrows(IllegalArgumentException.class, () -> ledger.cancelReservation(foreign));
        assertEquals(1, ledger.size());
        assertTrue(ledger.entry(live).isPresent());
    }

    @Test
    void cancellationCannotRemoveCommittingOrCommittedAssets() {
        var ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 1);
        ledger.beginCommit();
        assertThrows(IllegalStateException.class, () -> ledger.cancelReservation(entry));
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        ledger.finishCommit();
        assertThrows(IllegalStateException.class, () -> ledger.cancelReservation(entry));
        assertEquals(1, ledger.size());
        ledger.beginRecovery();
        assertEquals(1, ledger.recoveryAssets(entry).get(0).stack().getCount());
    }

    @Test
    void committedGroupsCanSettleIndependently() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        int firstMark = ledger.reservationMark();
        var first = ledger.reserve(source(), key(Items.DIAMOND), 2);
        var firstToken = ledger.tokenSince(firstMark);
        int secondMark = ledger.reservationMark();
        var second = ledger.reserve(source(), key(Items.GOLD_INGOT), 1);
        var secondToken = ledger.tokenSince(secondMark);

        ledger.beginCommit();
        ledger.recordExtraction(first, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                2, List.of(new ItemStack(Items.DIAMOND, 2))));
        ledger.recordExtraction(second, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.GOLD_INGOT))));

        assertEquals(StorageSettlementLedger.State.COMMITTED, ledger.finishCommit());
        ledger.settle(firstToken);
        assertEquals(StorageSettlementLedger.State.COMMITTED, ledger.state());
        ledger.settle(secondToken);
        assertEquals(StorageSettlementLedger.State.SETTLED, ledger.state());
    }

    @Test
    void partialExtractionRequiresRecoveryOfOnlyConfirmedAssets() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 5);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                5, List.of(new ItemStack(Items.DIAMOND, 3))));

        assertEquals(StorageSettlementLedger.State.RECOVERY_REQUIRED, ledger.finishCommit());
        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);
        assertEquals(3, asset.stack().getCount());
        ledger.recordRecovery(entry, asset.id(), StorageOperationResult.inserted(
                StorageOperationMode.PERFORM,
                new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.DIAMOND, 1)));
        assertEquals(1, ledger.recoveryAssets(entry).get(0).stack().getCount());
        ledger.recordRecoveredAmount(entry, asset.id(), 1);

        assertEquals(StorageSettlementLedger.State.ROLLED_BACK, ledger.finishRecovery());
        assertEquals(3, ledger.entry(entry).orElseThrow().recoveredAmount());
    }

    @Test
    void fullCountWithInvalidStatusStillRequiresRecovery() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 2);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.failedExtraction(
                StorageOperationMode.PERFORM,
                2, StorageOperationStatus.INVALID_RESPONSE,
                List.of(new ItemStack(Items.DIAMOND, 2)), List.of(),
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE));

        assertEquals(StorageSettlementLedger.State.RECOVERY_REQUIRED, ledger.finishCommit());
        assertEquals(StorageDiagnosticCode.INVALID_NATIVE_RESPONSE,
                ledger.entry(entry).orElseThrow().diagnosticCode());
    }

    @Test
    void incompleteRecoveryCanBeRetriedWithoutLosingTheRemainder() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 3);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                3, List.of(new ItemStack(Items.DIAMOND, 3))));
        ledger.finishCommit();
        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);
        ledger.recordRecoveredAmount(entry, asset.id(), 1);

        assertEquals(StorageSettlementLedger.State.RECOVERY_REQUIRED, ledger.finishRecovery());
        ledger.beginRecovery();
        assertEquals(2, ledger.recoveryAssets(entry).get(0).stack().getCount());
        ledger.recordRecoveredAmount(entry, asset.id(), 2);
        assertEquals(StorageSettlementLedger.State.ROLLED_BACK, ledger.finishRecovery());
    }

    @Test
    void indeterminateExtractionStaysIndeterminateAfterConfirmedFragmentsAreRecovered() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 5);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.indeterminateExtraction(
                5, List.of(new ItemStack(Items.DIAMOND, 2)), List.of(),
                StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION));

        assertEquals(StorageSettlementLedger.State.INDETERMINATE, ledger.finishCommit());
        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);
        ledger.recordRecoveredAmount(entry, asset.id(), 2);

        assertEquals(StorageSettlementLedger.State.INDETERMINATE, ledger.finishRecovery());
        assertEquals(0, ledger.entry(entry).orElseThrow().recoveryRemaining());
    }

    @Test
    void indeterminateRefundCannotBeReportedAsSuccessfulRollback() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 2);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                2, List.of(new ItemStack(Items.DIAMOND, 2))));
        assertEquals(StorageSettlementLedger.State.COMMITTED, ledger.finishCommit());

        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);
        ledger.recordRecovery(entry, asset.id(), StorageOperationResult.indeterminateInsert(
                new ItemStack(Items.DIAMOND, 2),
                StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION));

        assertEquals(StorageSettlementLedger.State.INDETERMINATE, ledger.finishRecovery());
    }

    @Test
    void simulatedInsertionCannotAdvanceRecoveryAccounting() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 1);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        ledger.finishCommit();
        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);

        assertThrows(IllegalArgumentException.class, () -> ledger.recordRecovery(
                entry, asset.id(), StorageOperationResult.inserted(
                        StorageOperationMode.SIMULATE,
                        new ItemStack(Items.DIAMOND), ItemStack.EMPTY)));
        assertEquals(1, ledger.entry(entry).orElseThrow().recoveryRemaining());
    }

    @Test
    void unfinishedCommitAndMismatchedResultsFailClosed() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 2);
        ledger.beginCommit();

        assertThrows(IllegalStateException.class, ledger::finishCommit);
        assertThrows(IllegalArgumentException.class, () -> ledger.recordExtraction(entry,
                StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                        1, List.of(new ItemStack(Items.DIAMOND)))));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordExtraction(entry,
                StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                        new ItemStack(Items.DIAMOND, 2), ItemStack.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordExtraction(entry,
                StorageOperationResult.extracted(StorageOperationMode.SIMULATE,
                        2, List.of(new ItemStack(Items.DIAMOND, 2)))));
    }

    @Test
    void extractionAndNetworkSourceCannotSwitchIdentityOrBackend() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(
                StorageReservationSource.storage(new StorageReference(
                        new StorageBackendId("other"), "network")),
                key(Items.DIAMOND), 1));

        var entry = ledger.reserve(source(), key(Items.DIAMOND), 1);
        ledger.beginCommit();
        assertThrows(IllegalArgumentException.class, () -> ledger.recordExtraction(entry,
                StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                        1, List.of(new ItemStack(Items.GOLD_INGOT)))));
        ledger.recordExtraction(entry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        ledger.finishCommit();
        ledger.beginRecovery();
        StorageSettlementLedger foreign = new StorageSettlementLedger();
        var foreignEntry = foreign.reserve(source(), key(Items.DIAMOND), 1);
        foreign.beginCommit();
        foreign.recordExtraction(foreignEntry, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        foreign.finishCommit();
        foreign.beginRecovery();
        assertThrows(IllegalArgumentException.class, () -> ledger.recordRecovery(
                entry, foreign.recoveryAssets(foreignEntry).get(0).id(),
                StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                        new ItemStack(Items.DIAMOND), ItemStack.EMPTY)));
    }

    @Test
    void unexpectedRecoveryStacksAreIndependentPhysicalAssets() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 1);
        ItemStack malformed = new ItemStack(Items.GOLD_INGOT, 2);
        malformed.getOrCreateTag().putDouble("unstable", Double.NaN);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.failedExtraction(
                StorageOperationMode.PERFORM,
                1, StorageOperationStatus.INVALID_RESPONSE, List.of(), List.of(malformed),
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE));

        assertEquals(StorageSettlementLedger.State.RECOVERY_REQUIRED, ledger.finishCommit());
        ledger.beginRecovery();
        var asset = ledger.recoveryAssets(entry).get(0);
        assertEquals(Items.GOLD_INGOT, asset.stack().getItem());
        assertEquals(2, asset.stack().getCount());
        assertTrue(Double.isNaN(asset.stack().getTag().getDouble("unstable")));
        ledger.recordRecoveredAmount(entry, asset.id(), 2);
        assertEquals(StorageSettlementLedger.State.ROLLED_BACK, ledger.finishRecovery());
        assertEquals(0, ledger.entry(entry).orElseThrow().recoveryRemaining());
    }

    @Test
    void marksCancelOnlyUncommittedReservationsAndTokensAreImmutable() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        ledger.reserve(source(), key(Items.DIAMOND), 1);
        int mark = ledger.reservationMark();
        ledger.reserve(source(), key(Items.GOLD_INGOT), 1);
        List<StorageSettlementLedger.EntryId> ids = new ArrayList<>(ledger.tokenSince(mark).entryIds());
        StorageSettlementLedger.ReservationToken token =
                new StorageSettlementLedger.ReservationToken(ids);
        ids.clear();

        assertEquals(1, token.entryIds().size());
        assertThrows(UnsupportedOperationException.class, () -> token.entryIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new StorageSettlementLedger.ReservationToken(
                List.of(token.entryIds().get(0), token.entryIds().get(0))));
        ledger.cancelReservationsSince(mark);
        assertEquals(1, ledger.size());
        assertThrows(IllegalStateException.class, () -> {
            ledger.beginCommit();
            ledger.cancelReservationsSince(0);
        });
    }

    @Test
    void entryIdsAndTokensCannotCrossLedgerInstances() {
        StorageSettlementLedger first = new StorageSettlementLedger();
        StorageSettlementLedger second = new StorageSettlementLedger();
        var foreignId = first.reserve(source(), key(Items.DIAMOND), 1);
        var localId = second.reserve(source(), key(Items.DIAMOND), 1);

        assertEquals(foreignId.value(), localId.value());
        assertThrows(IllegalArgumentException.class, () -> second.entry(foreignId)
                .orElseThrow(() -> new IllegalArgumentException("foreign entry")));
        second.beginCommit();
        assertThrows(IllegalArgumentException.class, () -> second.recordExtraction(
                foreignId, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                        1, List.of(new ItemStack(Items.DIAMOND)))));
    }

    @Test
    void foreignTokenFailsBeforeAnyLocalEntryIsSettled() {
        StorageSettlementLedger local = new StorageSettlementLedger();
        StorageSettlementLedger foreign = new StorageSettlementLedger();
        var localId = local.reserve(source(), key(Items.DIAMOND), 1);
        var foreignId = foreign.reserve(source(), key(Items.GOLD_INGOT), 1);
        local.beginCommit();
        local.recordExtraction(localId, StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND))));
        local.finishCommit();

        var mixed = new StorageSettlementLedger.ReservationToken(List.of(localId, foreignId));
        assertThrows(IllegalArgumentException.class, () -> local.settle(mixed));
        assertEquals(StorageSettlementLedger.EntryState.COMMITTED,
                local.entry(localId).orElseThrow().state());
    }

    @Test
    void reservationSourcesEnforceNetworkAndLocalIdentityShapes() {
        StorageReference reference = new StorageReference(BACKEND, "network");
        assertEquals(reference, StorageReservationSource.storage(reference)
                .storageReference().orElseThrow());
        assertEquals(StorageReservationSource.Kind.PLAYER_INVENTORY,
                StorageReservationSource.playerInventory(UUID.randomUUID()).kind());
        assertEquals("machine:1", StorageReservationSource.external(" machine:1 ").localId());
        assertThrows(IllegalArgumentException.class, () -> new StorageReservationSource(
                StorageReservationSource.Kind.STORAGE_NETWORK, java.util.Optional.empty(), ""));
        assertThrows(IllegalArgumentException.class, () -> new StorageReservationSource(
                StorageReservationSource.Kind.EXTERNAL, java.util.Optional.of(reference), "external"));
    }

    @Test
    void snapshotsDoNotExposeMutableConfirmedStacks() {
        StorageSettlementLedger ledger = new StorageSettlementLedger();
        var entry = ledger.reserve(source(), key(Items.DIAMOND), 2);
        ItemStack extracted = new ItemStack(Items.DIAMOND, 2);
        ledger.beginCommit();
        ledger.recordExtraction(entry, StorageOperationResult.extracted(
                StorageOperationMode.PERFORM, 2, List.of(extracted)));
        extracted.setCount(1);
        ledger.finishCommit();
        ledger.beginRecovery();
        List<StorageSettlementLedger.RecoveryAsset> recovery = ledger.recoveryAssets(entry);
        recovery.get(0).stack().setCount(1);

        assertEquals(2, ledger.recoveryAssets(entry).get(0).stack().getCount());
        assertTrue(ledger.entries().get(0).recoveryRemaining() > 0);
    }

    private static StorageReservationSource source() {
        return StorageReservationSource.storage(new StorageReference(BACKEND, "network"));
    }

    private static StorageItemKey key(net.minecraft.world.item.Item item) {
        return StorageItemKey.fromItemStack(BACKEND, new ItemStack(item));
    }
}
