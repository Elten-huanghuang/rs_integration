package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Adapter entry point for one optional storage mod. */
public interface StorageBackend {
    StorageBackendId id();

    boolean isAvailable();

    /** Resolves the backend-selected default network for this player. */
    StorageResolutionResult resolveForPlayer(ServerPlayer player);

    /** Discovers every network this player can explicitly select from this backend. */
    default StorageDiscoveryResult discoverForPlayer(ServerPlayer player) {
        StorageResolutionResult result = resolveForPlayer(player);
        if (result.resolved()) {
            StorageSession session = result.session().orElseThrow();
            StorageReference reference = session.reference();
            return StorageDiscoveryResult.success(List.of(new StorageNetworkDescriptor(
                    reference, reference.networkId(), true, session.capabilities())));
        }
        return switch (result.status()) {
            case NOT_FOUND -> StorageDiscoveryResult.success(List.of());
            case BACKEND_UNAVAILABLE -> StorageDiscoveryResult.failure(StorageDiscoveryStatus.BACKEND_UNAVAILABLE);
            case DENIED -> StorageDiscoveryResult.failure(StorageDiscoveryStatus.DENIED);
            default -> StorageDiscoveryResult.failure(StorageDiscoveryStatus.FAILED);
        };
    }

    StorageResolutionResult resolve(StorageReference reference, ServerPlayer player);
}
