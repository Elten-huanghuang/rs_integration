package com.huanghuang.rsintegration.storage;

import java.util.Objects;
import java.util.Optional;

/** Snapshot access result; failures cannot masquerade as an empty inventory. */
public final class StorageSnapshotResult {
    private final StorageSnapshotStatus status;
    private final StorageSnapshot snapshot;
    private final StorageDiagnosticCode diagnosticCode;

    private StorageSnapshotResult(StorageSnapshotStatus status, StorageSnapshot snapshot,
                                  StorageDiagnosticCode diagnosticCode) {
        this.status = Objects.requireNonNull(status, "status");
        this.snapshot = snapshot;
        this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public static StorageSnapshotResult success(StorageSnapshot snapshot) {
        return new StorageSnapshotResult(StorageSnapshotStatus.SUCCESS,
                Objects.requireNonNull(snapshot, "snapshot"), StorageDiagnosticCode.NONE);
    }

    public static StorageSnapshotResult failure(StorageSnapshotStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == StorageSnapshotStatus.SUCCESS) {
            throw new IllegalArgumentException("snapshot failure requires a failure status");
        }
        return failure(status, StorageDiagnosticCode.NONE);
    }

    public static StorageSnapshotResult failure(StorageSnapshotStatus status,
                                                StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if (status == StorageSnapshotStatus.SUCCESS) {
            throw new IllegalArgumentException("snapshot failure requires a failure status");
        }
        boolean requiresDiagnostic = status == StorageSnapshotStatus.FAILED
                || status == StorageSnapshotStatus.INVALID_RESPONSE;
        if (requiresDiagnostic == (diagnosticCode == StorageDiagnosticCode.NONE)) {
            throw new IllegalArgumentException(
                    "failed or invalid snapshots require a diagnostic code");
        }
        return new StorageSnapshotResult(status, null, diagnosticCode);
    }

    public StorageSnapshotStatus status() { return status; }
    public Optional<StorageSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    public StorageDiagnosticCode diagnosticCode() { return diagnosticCode; }
    public boolean successful() { return status == StorageSnapshotStatus.SUCCESS; }
}
