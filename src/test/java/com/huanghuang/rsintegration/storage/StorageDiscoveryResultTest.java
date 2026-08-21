package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDiscoveryResultTest {
    @Test
    void successfulDiscoveryCanExposeSeveralNetworksAndOneDefault() {
        StorageBackendId backend = new StorageBackendId("bd");
        StorageNetworkDescriptor first = new StorageNetworkDescriptor(
                new StorageReference(backend, "1"), "Primary", true);
        StorageNetworkDescriptor second = new StorageNetworkDescriptor(
                new StorageReference(backend, "2"), "Factory", false);

        StorageDiscoveryResult result = StorageDiscoveryResult.success(List.of(first, second));

        assertTrue(result.successful());
        assertEquals(List.of(first, second), result.networks());
        assertThrows(UnsupportedOperationException.class, () -> result.networks().add(first));
    }

    @Test
    void discoveryFailureCannotCarryInventedNetworks() {
        StorageDiscoveryResult result = StorageDiscoveryResult.failure(StorageDiscoveryStatus.DENIED);

        assertEquals(StorageDiscoveryStatus.DENIED, result.status());
        assertTrue(result.networks().isEmpty());
    }

    @Test
    void rejectsDuplicateReferences() {
        StorageBackendId backend = new StorageBackendId("bd");
        StorageReference reference = new StorageReference(backend, "1");

        assertThrows(IllegalArgumentException.class, () -> StorageDiscoveryResult.success(List.of(
                new StorageNetworkDescriptor(reference, "First", true),
                new StorageNetworkDescriptor(reference, "Duplicate", false))));
    }

    @Test
    void rejectsMultipleDefaultsAndMixedBackends() {
        StorageBackendId bd = new StorageBackendId("bd");
        StorageBackendId rs = new StorageBackendId("rs");

        assertThrows(IllegalArgumentException.class, () -> StorageDiscoveryResult.success(List.of(
                new StorageNetworkDescriptor(new StorageReference(bd, "1"), "First", true),
                new StorageNetworkDescriptor(new StorageReference(bd, "2"), "Second", true))));
        assertThrows(IllegalArgumentException.class, () -> StorageDiscoveryResult.success(List.of(
                new StorageNetworkDescriptor(new StorageReference(bd, "1"), "BD", true),
                new StorageNetworkDescriptor(new StorageReference(rs, "2"), "RS", false))));
    }
}
