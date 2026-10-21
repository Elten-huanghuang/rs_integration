package com.huanghuang.rsintegration.crafting.endpoint;

import com.huanghuang.rsintegration.storage.StorageSession;

import java.util.Optional;
import java.util.Objects;

import com.huanghuang.rsintegration.storage.StorageNetworkLocation;

/** Default endpoint adapter for one already-resolved storage session. */
public final class SessionCraftStorageEndpoint implements CraftStorageEndpoint {
    private final StorageSession session;

    public SessionCraftStorageEndpoint(StorageSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public StorageSession session() {
        return session;
    }

    @Override
    public boolean isAvailable() {
        return session.isAvailable();
    }

    @Override
    public Optional<StorageNetworkLocation> location() {
        return session.location();
    }
}
