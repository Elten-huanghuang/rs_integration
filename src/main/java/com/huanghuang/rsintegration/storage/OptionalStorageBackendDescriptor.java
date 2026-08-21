package com.huanghuang.rsintegration.storage;

import java.util.Objects;

public record OptionalStorageBackendDescriptor(StorageBackendId backendId,
                                               String modId,
                                               String providerClassName) {
    public OptionalStorageBackendDescriptor {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(providerClassName, "providerClassName");
        modId = modId.trim();
        providerClassName = providerClassName.trim();
        if (modId.isEmpty() || providerClassName.isEmpty()) {
            throw new IllegalArgumentException("optional backend descriptor values must not be blank");
        }
    }
}
