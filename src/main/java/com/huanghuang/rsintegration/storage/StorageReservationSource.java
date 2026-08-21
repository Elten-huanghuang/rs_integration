package com.huanghuang.rsintegration.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stable origin used to return a reservation to the same physical source. */
public record StorageReservationSource(Kind kind, Optional<StorageReference> storageReference,
                                       String localId) {
    public static final int MAX_LOCAL_ID_LENGTH = 256;

    public enum Kind { STORAGE_NETWORK, PLAYER_INVENTORY, EXTERNAL }

    public StorageReservationSource {
        Objects.requireNonNull(kind, "kind");
        storageReference = Objects.requireNonNull(storageReference, "storageReference");
        localId = Objects.requireNonNull(localId, "localId").trim();
        if (localId.length() > MAX_LOCAL_ID_LENGTH) {
            throw new IllegalArgumentException("reservation source id is too long");
        }
        if (kind == Kind.STORAGE_NETWORK) {
            if (storageReference.isEmpty() || !localId.isEmpty()) {
                throw new IllegalArgumentException("storage source requires only a storage reference");
            }
        } else if (storageReference.isPresent() || localId.isEmpty()) {
            throw new IllegalArgumentException("local source requires only a non-blank id");
        }
    }

    public static StorageReservationSource storage(StorageReference reference) {
        return new StorageReservationSource(Kind.STORAGE_NETWORK,
                Optional.of(Objects.requireNonNull(reference, "reference")), "");
    }

    public static StorageReservationSource playerInventory(UUID playerId) {
        return new StorageReservationSource(Kind.PLAYER_INVENTORY, Optional.empty(),
                Objects.requireNonNull(playerId, "playerId").toString());
    }

    public static StorageReservationSource external(String sourceId) {
        return new StorageReservationSource(Kind.EXTERNAL, Optional.empty(), sourceId);
    }
}
