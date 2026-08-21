package com.huanghuang.rsintegration.storage;

import java.util.Objects;
import java.util.Set;

/** Player-visible metadata for one network discovered through a backend. */
public record StorageNetworkDescriptor(StorageReference reference, String displayName,
                                       boolean defaultNetwork, Set<StorageCapability> capabilities) {
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;

    public StorageNetworkDescriptor {
        Objects.requireNonNull(reference, "reference");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty()) throw new IllegalArgumentException("network display name must not be blank");
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("network display name is too long");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (!capabilities.contains(StorageCapability.ITEM_STORAGE)) {
            throw new IllegalArgumentException("item storage network must declare ITEM_STORAGE");
        }
    }

    public StorageNetworkDescriptor(StorageReference reference, String displayName,
                                    boolean defaultNetwork) {
        this(reference, displayName, defaultNetwork, Set.of(StorageCapability.ITEM_STORAGE));
    }
}
