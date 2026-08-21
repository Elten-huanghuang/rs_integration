package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageReferenceTest {
    @Test
    void backendIdsAreCanonicalAndReferencesRemainBackendQualified() {
        StorageBackendId id = new StorageBackendId(" RefinedStorage ");
        StorageReference reference = new StorageReference(id, " network:42 ");

        assertEquals("refinedstorage", id.value());
        assertEquals("network:42", reference.networkId());
        assertEquals(id, reference.backendId());
    }

    @Test
    void rejectsInvalidOrBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new StorageBackendId("beyond dimensions"));
        assertThrows(IllegalArgumentException.class, () -> new StorageBackendId(""));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageReference(new StorageBackendId("rs"), "  "));
    }

    @Test
    void versionedCodecRoundTripsAndRejectsUnknownOrOversizedData() {
        StorageReference reference = new StorageReference(
                new StorageBackendId("beyond_dimensions"), "network:27");
        assertEquals(reference, StorageReferenceCodec.decode(
                StorageReferenceCodec.encode(reference)).orElseThrow());

        CompoundTag unknown = StorageReferenceCodec.encode(reference);
        unknown.putInt("version", 99);
        assertEquals(java.util.Optional.empty(), StorageReferenceCodec.decode(unknown));
        assertThrows(IllegalArgumentException.class, () -> new StorageReference(
                reference.backendId(), "x".repeat(StorageReference.MAX_NETWORK_ID_LENGTH + 1)));
    }
}
