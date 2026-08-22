package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageOperationResultTest extends BootstrapTest {
    @Test
    void insertionReportsAcceptedCountAndDefensivelyCopiesRemainder() {
        ItemStack input = new ItemStack(Items.IRON_INGOT, 10);
        ItemStack remainder = new ItemStack(Items.IRON_INGOT, 3);

        StorageOperationResult result = StorageOperationResult.inserted(
                StorageOperationMode.PERFORM, input, remainder);
        remainder.setCount(1);

        assertEquals(StorageOperationResult.Kind.INSERT, result.kind());
        assertEquals(StorageOperationMode.PERFORM, result.mode());
        assertEquals(StorageOperationStatus.PARTIAL, result.status());
        assertEquals(10, result.requestedAmount());
        assertEquals(7, result.transferredAmount().orElseThrow());
        assertFalse(result.complete());
        assertEquals(3, result.remainder().orElseThrow().getCount());

        ItemStack returned = result.remainder().orElseThrow();
        returned.setCount(2);
        assertEquals(3, result.remainder().orElseThrow().getCount());
    }

    @Test
    void extractionPreservesAllActualFragmentsAndCopiesThem() {
        ItemStack first = new ItemStack(Items.APPLE, 2);
        ItemStack second = new ItemStack(Items.GOLDEN_APPLE, 1);
        StorageOperationResult result = StorageOperationResult.extracted(
                StorageOperationMode.PERFORM, 3, List.of(first, second));
        first.setCount(1);

        assertEquals(StorageOperationResult.Kind.EXTRACT, result.kind());
        assertEquals(StorageOperationStatus.SUCCESS, result.status());
        assertEquals(3, result.transferredAmount().orElseThrow());
        assertTrue(result.complete());
        assertEquals(List.of(2, 1), result.extractedStacks().stream().map(ItemStack::getCount).toList());

        result.extractedStacks().get(0).setCount(64);
        assertEquals(2, result.extractedStacks().get(0).getCount());
    }

    @Test
    void rejectsImpossibleBackendResults() {
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.inserted(
                StorageOperationMode.PERFORM,
                ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT, 1)));
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.inserted(
                StorageOperationMode.PERFORM,
                new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.GOLD_INGOT, 1)));
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.extracted(
                StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.IRON_INGOT, 2))));
    }

    @Test
    void backendIdentityRuleCanValidateAValidNonVanillaRemainder() {
        ItemStack input = new ItemStack(Items.DIAMOND, 2);
        input.getOrCreateTag().putString("native", "first");
        ItemStack remainder = new ItemStack(Items.DIAMOND, 1);
        remainder.getOrCreateTag().putString("native", "second");

        assertThrows(IllegalArgumentException.class,
                () -> StorageOperationResult.inserted(
                        StorageOperationMode.PERFORM, input, remainder));
        StorageOperationResult result = StorageOperationResult.inserted(
                StorageOperationMode.PERFORM, input, remainder, (left, right) -> {
                    left.setCount(64);
                    right.setCount(64);
                    return true;
                });

        assertEquals(StorageOperationStatus.PARTIAL, result.status());
        assertEquals(1, result.transferredAmount().orElseThrow());
        assertEquals(2, input.getCount());
        assertEquals(1, remainder.getCount());
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.inserted(
                StorageOperationMode.PERFORM,
                input, new ItemStack(Items.GOLD_INGOT), (left, right) -> true));
    }

    @Test
    void failuresRemainDistinctAndRecoveryStacksAreNotCountedAsTransfers() {
        ItemStack wrongIdentity = new ItemStack(Items.GOLD_INGOT, 2);
        StorageOperationResult result = StorageOperationResult.failedExtraction(
                StorageOperationMode.PERFORM, 4,
                StorageOperationStatus.INVALID_RESPONSE,
                List.of(new ItemStack(Items.IRON_INGOT, 1)), List.of(wrongIdentity),
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        wrongIdentity.setCount(1);

        assertEquals(StorageOperationStatus.INVALID_RESPONSE, result.status());
        assertEquals(1, result.transferredAmount().orElseThrow());
        assertEquals(2, result.recoveryStacks().get(0).getCount());
        assertFalse(result.complete());
    }

    @Test
    void deniedInsertRetainsTheWholeInput() {
        StorageOperationResult result = StorageOperationResult.failedInsert(
                StorageOperationMode.PERFORM,
                new ItemStack(Items.DIAMOND, 5), StorageOperationStatus.DENIED);

        assertEquals(StorageOperationStatus.DENIED, result.status());
        assertEquals(0, result.transferredAmount().orElseThrow());
        assertEquals(5, result.remainder().orElseThrow().getCount());
    }

    @Test
    void mutationPhaseFailureDoesNotPretendThatNothingMoved() {
        ItemStack input = new ItemStack(Items.DIAMOND, 5);
        StorageOperationResult insert = StorageOperationResult.indeterminateInsert(input,
                StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
        StorageOperationResult extraction = StorageOperationResult.indeterminateExtraction(5,
                List.of(new ItemStack(Items.DIAMOND, 2)), List.of(),
                StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);

        assertEquals(StorageOperationStatus.INDETERMINATE, insert.status());
        assertTrue(insert.transferredAmount().isEmpty());
        assertTrue(insert.remainder().isEmpty());
        assertEquals(StorageOperationStatus.INDETERMINATE, extraction.status());
        assertTrue(extraction.transferredAmount().isEmpty());
        assertEquals(2, extraction.extractedStacks().get(0).getCount());
    }

    @Test
    void operationSpecificFieldsRejectTheWrongResultKind() {
        StorageOperationResult insert = StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                new ItemStack(Items.DIAMOND), ItemStack.EMPTY);
        StorageOperationResult extract = StorageOperationResult.extracted(StorageOperationMode.PERFORM,
                1, List.of(new ItemStack(Items.DIAMOND)));

        assertThrows(IllegalStateException.class, insert::extractedStacks);
        assertThrows(IllegalStateException.class, insert::recoveryStacks);
        assertThrows(IllegalStateException.class, extract::remainder);
        assertThrows(IllegalArgumentException.class, () ->
                StorageOperationResult.indeterminateInsert(
                        new ItemStack(Items.DIAMOND), StorageDiagnosticCode.NONE));
    }

    @Test
    void simulationModeIsPartOfTheOperationResult() {
        StorageOperationResult simulated = StorageOperationResult.extracted(
                StorageOperationMode.SIMULATE, 1,
                List.of(new ItemStack(Items.DIAMOND)));

        assertEquals(StorageOperationMode.SIMULATE, simulated.mode());
        assertEquals(1, simulated.transferredAmount().orElseThrow());
    }

    @Test
    void failureStatusesRequireConsistentDiagnostics() {
        ItemStack input = new ItemStack(Items.DIAMOND);

        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.failedInsert(
                StorageOperationMode.PERFORM, input, StorageOperationStatus.FAILED));
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.failedExtraction(
                StorageOperationMode.PERFORM, 1, StorageOperationStatus.INVALID_RESPONSE,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> StorageOperationResult.failedInsert(
                StorageOperationMode.PERFORM, input, StorageOperationStatus.DENIED,
                StorageDiagnosticCode.BACKEND_EXCEPTION));
    }
}
