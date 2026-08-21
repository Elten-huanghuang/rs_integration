package com.huanghuang.rsintegration.storage;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Structured discovery result; successful discovery may legitimately contain no networks. */
public final class StorageDiscoveryResult {
    private final StorageDiscoveryStatus status;
    private final List<StorageNetworkDescriptor> networks;

    private StorageDiscoveryResult(StorageDiscoveryStatus status, List<StorageNetworkDescriptor> networks) {
        this.status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(networks, "networks");
        this.networks = List.copyOf(networks);
    }

    public static StorageDiscoveryResult success(List<StorageNetworkDescriptor> networks) {
        validateNetworks(networks);
        return new StorageDiscoveryResult(StorageDiscoveryStatus.SUCCESS, networks);
    }

    public static StorageDiscoveryResult failure(StorageDiscoveryStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == StorageDiscoveryStatus.SUCCESS) {
            throw new IllegalArgumentException("successful discovery requires a network list");
        }
        return new StorageDiscoveryResult(status, List.of());
    }

    public StorageDiscoveryStatus status() { return status; }
    public List<StorageNetworkDescriptor> networks() { return networks; }
    public boolean successful() { return status == StorageDiscoveryStatus.SUCCESS; }

    private static void validateNetworks(List<StorageNetworkDescriptor> networks) {
        Objects.requireNonNull(networks, "networks");
        StorageBackendId backendId = null;
        Set<StorageReference> references = new HashSet<>();
        boolean defaultSeen = false;
        for (StorageNetworkDescriptor network : networks) {
            Objects.requireNonNull(network, "network");
            StorageBackendId currentBackend = network.reference().backendId();
            if (backendId == null) backendId = currentBackend;
            if (!backendId.equals(currentBackend)) {
                throw new IllegalArgumentException("discovery result mixes backend ids");
            }
            if (!references.add(network.reference())) {
                throw new IllegalArgumentException("duplicate storage network reference");
            }
            if (network.defaultNetwork() && defaultSeen) {
                throw new IllegalArgumentException("discovery result has multiple default networks");
            }
            defaultSeen |= network.defaultNetwork();
        }
    }
}
