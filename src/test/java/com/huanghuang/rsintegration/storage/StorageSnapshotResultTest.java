package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSnapshotResultTest {
    @Test
    void failureIsDistinctFromSuccessfulEmptySnapshot() {
        StorageSnapshotResult empty = StorageSnapshotResult.success(new StorageSnapshot(
                new StorageBackendId("test"), java.util.List.of()));
        StorageSnapshotResult unavailable = StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);

        assertEquals(StorageSnapshotStatus.SUCCESS, empty.status());
        assertEquals(StorageSnapshotStatus.UNAVAILABLE, unavailable.status());
        assertTrue(empty.snapshot().isPresent());
        assertTrue(unavailable.snapshot().isEmpty());
        assertFalse(unavailable.successful());
        assertThrows(IllegalArgumentException.class,
                () -> StorageSnapshotResult.failure(StorageSnapshotStatus.SUCCESS));
        assertThrows(IllegalArgumentException.class,
                () -> StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED));
        assertThrows(IllegalArgumentException.class,
                () -> StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE,
                        StorageDiagnosticCode.BACKEND_EXCEPTION));

        StorageSnapshotResult failed = StorageSnapshotResult.failure(
                StorageSnapshotStatus.FAILED, StorageDiagnosticCode.BACKEND_EXCEPTION);
        assertEquals(StorageDiagnosticCode.BACKEND_EXCEPTION, failed.diagnosticCode());
    }
}
