package com.huanghuang.rsintegration.storage;

import java.util.Objects;

public record StoragePermissionResult(StoragePermissionStatus status,
                                      StorageDiagnosticCode diagnosticCode) {
    public StoragePermissionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if ((status == StoragePermissionStatus.FAILED)
                == (diagnosticCode == StorageDiagnosticCode.NONE)) {
            throw new IllegalArgumentException(
                    "only failed permission results require a diagnostic code");
        }
    }

    public static StoragePermissionResult allowed() {
        return new StoragePermissionResult(StoragePermissionStatus.ALLOWED, StorageDiagnosticCode.NONE);
    }

    public static StoragePermissionResult denied() {
        return new StoragePermissionResult(StoragePermissionStatus.DENIED, StorageDiagnosticCode.NONE);
    }

    public static StoragePermissionResult failed(StorageDiagnosticCode diagnosticCode) {
        return new StoragePermissionResult(StoragePermissionStatus.FAILED, diagnosticCode);
    }

    public static StoragePermissionResult unavailable() {
        return new StoragePermissionResult(StoragePermissionStatus.UNAVAILABLE, StorageDiagnosticCode.NONE);
    }

    public boolean allowedAccess() { return status == StoragePermissionStatus.ALLOWED; }
}
