package com.huanghuang.rsintegration.storage;

import java.util.Objects;

public record StorageBackendResolution(StorageBackendId backendId, StorageResolutionResult result) {
    public StorageBackendResolution {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(result, "result");
    }
}
