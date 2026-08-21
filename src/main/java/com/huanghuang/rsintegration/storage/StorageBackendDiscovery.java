package com.huanghuang.rsintegration.storage;

import java.util.Objects;

public record StorageBackendDiscovery(StorageBackendId backendId, StorageDiscoveryResult result) {
    public StorageBackendDiscovery {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(result, "result");
    }
}
