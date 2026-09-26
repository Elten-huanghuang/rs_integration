package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import com.huanghuang.rsintegration.RSIntegrationMod;

import java.util.Objects;

/** Reflection-isolated BD adapter. Native BD classes never escape this package. */
public final class BeyondDimensionsBackend implements StorageBackend {
    static final StorageBackendId ID = BeyondDimensionsIds.BACKEND;

    @Override public StorageBackendId id() { return ID; }
    @Override public boolean isAvailable() { return ModList.get().isLoaded("beyonddimensions"); }

    @Override
    public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        return BeyondDimensionsReflection.resolvePrimary(player, ID);
    }

    @Override
    public StorageDiscoveryResult discoverForPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        return BeyondDimensionsReflection.discover(player, ID);
    }

    @Override
    public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        if (!ID.equals(reference.backendId())) {
            return StorageResolutionResult.failure(StorageResolutionStatus.INVALID_REFERENCE);
        }
        try {
            int id = Integer.parseInt(reference.networkId());
            if (id < 0) return StorageResolutionResult.failure(StorageResolutionStatus.INVALID_REFERENCE);
            StorageResolutionResult result = BeyondDimensionsReflection.resolveById(id, player, ID);
            if (!result.resolved()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Storage] BD reference unavailable player={} id={} status={}",
                        player.getGameProfile().getName(), id, result.status());
            }
            return result;
        } catch (NumberFormatException e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.INVALID_REFERENCE);
        }
    }
}
