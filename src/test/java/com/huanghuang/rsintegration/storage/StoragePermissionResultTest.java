package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoragePermissionResultTest {
    @Test
    void statusAndDiagnosticCannotContradictEachOther() {
        assertThrows(IllegalArgumentException.class, () -> new StoragePermissionResult(
                StoragePermissionStatus.ALLOWED, StorageDiagnosticCode.PERMISSION_CHECK_FAILED));
        assertThrows(IllegalArgumentException.class, () -> new StoragePermissionResult(
                StoragePermissionStatus.FAILED, StorageDiagnosticCode.NONE));

        assertEquals(StoragePermissionStatus.UNAVAILABLE,
                StoragePermissionResult.unavailable().status());
        assertEquals(StorageDiagnosticCode.PERMISSION_CHECK_FAILED,
                StoragePermissionResult.failed(StorageDiagnosticCode.PERMISSION_CHECK_FAILED)
                        .diagnosticCode());
    }
}
