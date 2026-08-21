package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedStorageOperationExecutorTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test_rs");

    @Test
    void trackedInsertRecordsAcceptedAmountBeforePerform() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = new ItemStack(Items.DIAMOND, 2);
        driver.performedInsertRemainder = new ItemStack(Items.DIAMOND, 2);
        ItemStack input = new ItemStack(Items.DIAMOND, 5);

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver, input, false,
                accepted -> driver.events.add("track:" + accepted.getCount()),
                accepted -> driver.events.add("observe:" + accepted.getCount()));

        assertEquals(List.of("simulate", "track:3", "observe:3", "perform"), driver.events);
        assertEquals(StorageOperationStatus.PARTIAL, result.status());
        assertEquals(3, result.transferredAmount().orElseThrow());
    }

    @Test
    void trackerFailurePreventsMutationAndRemainsKnownZero() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = ItemStack.EMPTY;
        ItemStack input = new ItemStack(Items.DIAMOND, 5);

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver, input, false,
                accepted -> { throw new IllegalStateException("tracker"); });

        assertEquals(List.of("simulate"), driver.events);
        assertEquals(StorageDiagnosticCode.CHANGE_TRACKING_FAILED, result.diagnosticCode());
        assertTrue(result.transferredAmount().isPresent());
        assertEquals(0, result.transferredAmount().orElseThrow());
    }

    @Test
    void performInsertExceptionIsIndeterminate() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = ItemStack.EMPTY;
        driver.throwOnPerformInsert = true;

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver,
                new ItemStack(Items.DIAMOND, 5), false, accepted -> { });

        assertEquals(StorageOperationStatus.INDETERMINATE, result.status());
        assertTrue(result.transferredAmount().isEmpty());
        assertTrue(result.remainder().isEmpty());
    }

    @Test
    void simulatedInsertRejectsInvalidNativeRemainderAsInvalidResponse() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = new ItemStack(Items.GOLD_INGOT, 1);

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver,
                new ItemStack(Items.DIAMOND, 5), true, accepted -> { });

        assertEquals(StorageOperationStatus.INVALID_RESPONSE, result.status());
        assertEquals(StorageDiagnosticCode.INVALID_NATIVE_RESPONSE, result.diagnosticCode());
        assertEquals(0, result.transferredAmount().orElseThrow());
    }

    @Test
    void nullInsertResponsesAreClassifiedByMutationPhase() {
        FakeDriver simulatedDriver = new FakeDriver();
        simulatedDriver.simulatedInsertRemainder = null;
        ItemStack input = new ItemStack(Items.DIAMOND, 5);

        StorageOperationResult simulated = RefinedStorageOperationExecutor.insert(
                simulatedDriver, input, true, accepted -> { });

        assertEquals(StorageOperationStatus.INVALID_RESPONSE, simulated.status());
        assertEquals(StorageOperationMode.SIMULATE, simulated.mode());
        assertEquals(StorageDiagnosticCode.INVALID_NATIVE_RESPONSE, simulated.diagnosticCode());
        assertEquals(0, simulated.transferredAmount().orElseThrow());

        FakeDriver performedDriver = new FakeDriver();
        performedDriver.simulatedInsertRemainder = ItemStack.EMPTY;
        performedDriver.performedInsertRemainder = null;
        StorageOperationResult performed = RefinedStorageOperationExecutor.insert(
                performedDriver, input, false, accepted -> { });

        assertEquals(StorageOperationStatus.INDETERMINATE, performed.status());
        assertEquals(StorageOperationMode.PERFORM, performed.mode());
        assertEquals(StorageDiagnosticCode.INVALID_NATIVE_RESPONSE, performed.diagnosticCode());
        assertTrue(performed.transferredAmount().isEmpty());
    }

    @Test
    void insertObserverFailurePreventsNativeMutationWithItsOwnDiagnostic() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = ItemStack.EMPTY;

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver,
                new ItemStack(Items.DIAMOND, 5), false, accepted -> { },
                accepted -> { throw new IllegalStateException("observer"); });

        assertEquals(List.of("simulate"), driver.events);
        assertEquals(StorageDiagnosticCode.INSERT_OBSERVER_FAILED, result.diagnosticCode());
        assertEquals(0, result.transferredAmount().orElseThrow());
    }

    @Test
    void observerStillRunsWhenPerformAcceptsAfterRejectedPreflight() {
        FakeDriver driver = new FakeDriver();
        driver.simulatedInsertRemainder = new ItemStack(Items.DIAMOND, 5);
        driver.performedInsertRemainder = ItemStack.EMPTY;

        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver,
                new ItemStack(Items.DIAMOND, 5), false,
                accepted -> driver.events.add("track"),
                estimate -> driver.events.add("observe:" + estimate.getCount()));

        assertEquals(List.of("simulate", "observe:0", "perform"), driver.events);
        assertEquals(5, result.transferredAmount().orElseThrow());
    }

    @Test
    void partialExtractionPreservesConfirmedReturnedStack() {
        FakeDriver driver = new FakeDriver();
        driver.extractResults.add(new ItemStack(Items.DIAMOND, 3));
        StorageItemKey key = StorageItemKey.fromItemStack(BACKEND, new ItemStack(Items.DIAMOND));

        StorageOperationResult result = RefinedStorageOperationExecutor.extract(driver, key,
                new ItemStack(Items.DIAMOND), 5, 5, false);

        assertEquals(StorageOperationStatus.PARTIAL, result.status());
        assertEquals(3, result.transferredAmount().orElseThrow());
        assertEquals(3, result.extractedStacks().get(0).getCount());
    }

    @Test
    void simulatedExtractionIsMarkedAsNonMutating() {
        FakeDriver driver = new FakeDriver();
        driver.extractResults.add(new ItemStack(Items.DIAMOND, 1));
        StorageItemKey key = StorageItemKey.fromItemStack(BACKEND, new ItemStack(Items.DIAMOND));

        StorageOperationResult result = RefinedStorageOperationExecutor.extract(driver, key,
                new ItemStack(Items.DIAMOND), 1, 1, true);

        assertEquals(StorageOperationMode.SIMULATE, result.mode());
        assertEquals(1, result.transferredAmount().orElseThrow());
    }

    @Test
    void extractionExceptionAfterPerformStartsIsIndeterminate() {
        FakeDriver driver = new FakeDriver();
        driver.throwOnExtract = true;
        StorageItemKey key = StorageItemKey.fromItemStack(BACKEND, new ItemStack(Items.DIAMOND));

        StorageOperationResult result = RefinedStorageOperationExecutor.extract(driver, key,
                new ItemStack(Items.DIAMOND), 1, 1, false);

        assertEquals(StorageOperationStatus.INDETERMINATE, result.status());
        assertTrue(result.transferredAmount().isEmpty());
    }

    @Test
    void wrongIdentityRecoveryExceptionDoesNotExposeDuplicateRecoveryStack() {
        FakeDriver driver = new FakeDriver();
        driver.extractResults.add(new ItemStack(Items.GOLD_INGOT, 1));
        driver.throwOnPerformInsert = true;
        StorageItemKey key = StorageItemKey.fromItemStack(BACKEND, new ItemStack(Items.DIAMOND));

        StorageOperationResult result = RefinedStorageOperationExecutor.extract(driver, key,
                new ItemStack(Items.DIAMOND), 1, 1, false);

        assertEquals(StorageOperationStatus.INDETERMINATE, result.status());
        assertTrue(result.recoveryStacks().isEmpty());
    }

    private static final class FakeDriver implements RefinedStorageDriver {
        final List<String> events = new ArrayList<>();
        final List<ItemStack> extractResults = new ArrayList<>();
        ItemStack simulatedInsertRemainder = ItemStack.EMPTY;
        ItemStack performedInsertRemainder = ItemStack.EMPTY;
        boolean throwOnPerformInsert;
        boolean throwOnExtract;

        @Override public RefinedStorageSnapshotRead snapshotItems() {
            return RefinedStorageSnapshotRead.available(List.of());
        }

        @Override public boolean hasPermission(ServerPlayer player, StoragePermission permission) {
            return true;
        }

        @Override public ItemStack extract(ItemStack template, int amount, boolean simulate) {
            events.add(simulate ? "extract-simulate" : "extract-perform");
            if (throwOnExtract) throw new IllegalStateException("extract");
            return extractResults.isEmpty() ? ItemStack.EMPTY : extractResults.remove(0).copy();
        }

        @Override public ItemStack insert(ItemStack stack, boolean simulate) {
            events.add(simulate ? "simulate" : "perform");
            if (!simulate && throwOnPerformInsert) throw new IllegalStateException("insert");
            ItemStack result = simulate ? simulatedInsertRemainder : performedInsertRemainder;
            return result == null ? null : result.copy();
        }

        @Override public void recordInsertion(ServerPlayer player, ItemStack accepted) { }
    }
}
