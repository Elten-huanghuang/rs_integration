package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageBackend;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageInsertObserver;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageResolutionStatus;
import com.huanghuang.rsintegration.storage.StorageThreadGuard;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.Objects;

/** Refined Storage adapter. This class must only be loaded when RS is present. */
public final class RefinedStorageBackend implements StorageBackend {
    private final StorageInsertObserver insertObserver;

    public RefinedStorageBackend() {
        this(RsiRefinedStorageInsertObserver.INSTANCE);
    }

    RefinedStorageBackend(StorageInsertObserver insertObserver) {
        this.insertObserver = Objects.requireNonNull(insertObserver, "insertObserver");
    }

    @Override
    public StorageBackendId id() {
        return RefinedStorageIds.BACKEND;
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("refinedstorage");
    }

    @Override
    public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        try {
            INetwork network = RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player);
            return network == null
                    ? StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND)
                    : createSession(network);
        } catch (RuntimeException | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    @Override
    public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        try {
            var parsed = RefinedStorageReference.parse(reference);
            if (parsed.isEmpty()) {
                return StorageResolutionResult.failure(StorageResolutionStatus.INVALID_REFERENCE);
            }
            var location = parsed.orElseThrow();
            var level = player.server.getLevel(location.dimension());
            if (level == null) return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
            if (!level.isLoaded(location.position())) {
                return StorageResolutionResult.failure(StorageResolutionStatus.UNLOADED);
            }
            INetwork network = RSIntegrationNetwork.resolveNetworkStrict(
                    player.server, location.dimension(), location.position());
            return network == null
                    ? StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND)
                    : createSession(network);
        } catch (RuntimeException | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    private StorageResolutionResult createSession(INetwork network) {
        StorageReference reference = RefinedStorageReference.fromNetwork(network);
        return StorageResolutionResult.resolved(new RefinedStorageSession(
                new NativeRefinedStorageDriver(network), reference, insertObserver));
    }
}
