package com.huanghuang.rsintegration.storage;

import java.util.Objects;

/** Backend-qualified, persistent reference to a storage network. */
public record StorageReference(StorageBackendId backendId, String networkId) {
    public static final int MAX_NETWORK_ID_LENGTH = 512;

    public StorageReference {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(networkId, "networkId");
        networkId = networkId.trim();
        if (networkId.isEmpty() || networkId.length() > MAX_NETWORK_ID_LENGTH) {
            throw new IllegalArgumentException("network id must not be blank");
        }
    }
}
