package com.huanghuang.rsintegration.storage;

import java.util.Objects;

public record StorageBackendLoadResult(StorageBackendId backendId, StorageBackendLoadStatus status) {
    public StorageBackendLoadResult {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(status, "status");
    }

    public boolean loaded() { return status == StorageBackendLoadStatus.LOADED; }
}
