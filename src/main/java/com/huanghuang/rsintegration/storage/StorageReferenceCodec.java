package com.huanghuang.rsintegration.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/** Versioned persistent representation used by bindings and upgrade items. */
public final class StorageReferenceCodec {
    private static final int VERSION = 1;
    private static final String VERSION_KEY = "version";
    private static final String BACKEND_KEY = "backend";
    private static final String NETWORK_KEY = "network";

    private StorageReferenceCodec() {}

    public static CompoundTag encode(StorageReference reference) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION_KEY, VERSION);
        tag.putString(BACKEND_KEY, reference.backendId().value());
        tag.putString(NETWORK_KEY, reference.networkId());
        return tag;
    }

    public static Optional<StorageReference> decode(CompoundTag tag) {
        if (tag == null || tag.getInt(VERSION_KEY) != VERSION
                || !tag.contains(BACKEND_KEY, Tag.TAG_STRING)
                || !tag.contains(NETWORK_KEY, Tag.TAG_STRING)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StorageReference(
                    new StorageBackendId(tag.getString(BACKEND_KEY)),
                    tag.getString(NETWORK_KEY)));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Optional.empty();
        }
    }
}
