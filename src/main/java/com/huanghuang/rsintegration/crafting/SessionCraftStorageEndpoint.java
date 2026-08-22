package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.storage.StorageSession;

import java.util.Objects;

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
}
