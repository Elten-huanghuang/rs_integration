package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Enforces that live backend handles are only accessed on the Minecraft server thread. */
public final class StorageThreadGuard {
    private StorageThreadGuard() {}

    public static void requireServerThread(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("live storage access is only allowed on the server thread");
        }
    }
}
