package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import com.huanghuang.rsintegration.storage.StorageReference;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import com.refinedmods.refinedstorage.api.network.INetwork;

/** Resolves the backend-selected storage endpoint for recursive crafting. */
public final class CraftStorageEndpoints {
    private CraftStorageEndpoints() {}

    /** Transitional bridge for legacy call sites; native RS stays isolated here. */
    public static CraftStorageEndpoint fromLegacyNetwork(@Nonnull INetwork network) {
        return new LegacyRsCraftStorageEndpoint(network);
    }

    /**
     * Returns the first backend-default session in registry order. Backend
     * selection remains centralized in StorageBackendRegistry; this helper does
     * not inspect or reference any native storage implementation.
     */
    public static Optional<CraftStorageEndpoint> resolveDefault(@Nonnull ServerPlayer player) {
        List<com.huanghuang.rsintegration.storage.StorageSession> sessions =
                RSIntegrationMod.STORAGE_BACKENDS.registry().resolveDefaultSessionsForPlayer(player);
        return sessions.isEmpty()
                ? Optional.empty()
                : Optional.of(new SessionCraftStorageEndpoint(sessions.get(0)));
    }

    /** Resolves a persisted backend-qualified reference without exposing native backend types. */
    public static Optional<CraftStorageEndpoint> resolve(@Nonnull StorageReference reference,
                                                          @Nonnull ServerPlayer player) {
        return RSIntegrationMod.STORAGE_BACKENDS.registry().resolve(reference, player)
                .session()
                .map(SessionCraftStorageEndpoint::new);
    }
}
