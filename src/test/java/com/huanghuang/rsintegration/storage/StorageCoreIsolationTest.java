package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StorageCoreIsolationTest {
    @Test
    void coreStorageClassesDoNotLinkOptionalBackendTypes() throws IOException {
        List<Class<?>> coreTypes = List.of(
                StorageBackend.class,
                StorageBackendDiscovery.class,
                StorageBackendDescriptors.class,
                StorageBackendLoader.class,
                StorageBackendRegistry.class,
                StorageCapability.class,
                StorageDiagnosticCode.class,
                StorageDiscoveryStatus.class,
                StorageDiscoveryResult.class,
                StorageIdentityBytes.class,
                StorageInsertObserver.class,
                StorageItemKey.class,
                StorageNetworkDescriptor.class,
                StorageOperationResult.class,
                StorageOperationMode.class,
                StorageOperationStatus.class,
                StoragePermission.class,
                StoragePermissionResult.class,
                StoragePermissionStatus.class,
                StorageReference.class,
                StorageReservationSource.class,
                StorageResolutionResult.class,
                StorageResolutionStatus.class,
                StorageSession.class,
                StorageSnapshot.class,
                StorageSnapshotResult.class,
                StorageSnapshotStatus.class,
                StorageSettlementLedger.class,
                StorageSettlementLedger.EntryId.class,
                StorageSettlementLedger.EntrySnapshot.class,
                StorageSettlementLedger.EntryState.class,
                StorageSettlementLedger.RecoveryAsset.class,
                StorageSettlementLedger.RecoveryAssetId.class,
                StorageSettlementLedger.ReservationToken.class,
                StorageSettlementLedger.State.class,
                StorageThreadGuard.class,
                StoredItem.class);

        for (Class<?> type : coreTypes) {
            String constants = new String(classBytes(type), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("com/refinedmods/refinedstorage"),
                    type.getSimpleName() + " links Refined Storage");
            assertFalse(constants.contains("com/wintercogs/beyonddimensions"),
                    type.getSimpleName() + " links BeyondDimensions");
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String name = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing class resource: " + name);
            return input.readAllBytes();
        }
    }
}
