package com.huanghuang.rsintegration.storage;

import java.util.Objects;
import java.util.Optional;

/** 流体快照读取结果，失败不能伪装为空库存。 */
public record StorageFluidSnapshotResult(
        StorageSnapshotStatus status,
        StorageFluidSnapshot snapshot,
        StorageDiagnosticCode diagnosticCode
) {
    public StorageFluidSnapshotResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if (status == StorageSnapshotStatus.SUCCESS) {
            Objects.requireNonNull(snapshot, "successful snapshot");
        } else if (snapshot != null) {
            throw new IllegalArgumentException(
                    "failed snapshot cannot carry a value");
        }
        boolean diagnosticRequired = status == StorageSnapshotStatus.FAILED
                || status == StorageSnapshotStatus.INVALID_RESPONSE;
        if (diagnosticRequired == (
                diagnosticCode == StorageDiagnosticCode.NONE)) {
            throw new IllegalArgumentException(
                    "snapshot diagnostic does not match status");
        }
    }

    public static StorageFluidSnapshotResult success(
            StorageFluidSnapshot snapshot
    ) {
        return new StorageFluidSnapshotResult(
                StorageSnapshotStatus.SUCCESS,
                snapshot,
                StorageDiagnosticCode.NONE
        );
    }

    public static StorageFluidSnapshotResult failure(
            StorageSnapshotStatus status
    ) {
        return failure(status, StorageDiagnosticCode.NONE);
    }

    public static StorageFluidSnapshotResult failure(
            StorageSnapshotStatus status,
            StorageDiagnosticCode diagnosticCode
    ) {
        return new StorageFluidSnapshotResult(status, null, diagnosticCode);
    }

    public boolean successful() {
        return status == StorageSnapshotStatus.SUCCESS;
    }

    public Optional<StorageFluidSnapshot> snapshotValue() {
        return Optional.ofNullable(snapshot);
    }
}
