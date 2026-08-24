package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Ordered registry of optional storage adapters. Selection policy lives above this class. */
public final class StorageBackendRegistry {
    private final Map<StorageBackendId, StorageBackend> backends = new LinkedHashMap<>();

    public synchronized void register(StorageBackend backend) {
        Objects.requireNonNull(backend, "backend");
        StorageBackendId id = Objects.requireNonNull(backend.id(), "backend.id()");
        StorageBackend previous = backends.putIfAbsent(id, backend);
        if (previous != null) {
            throw new IllegalStateException("storage backend already registered: " + id);
        }
    }

    public synchronized Optional<StorageBackend> get(StorageBackendId id) {
        return Optional.ofNullable(backends.get(Objects.requireNonNull(id, "id")));
    }

    public Collection<StorageBackend> availableBackends() {
        List<StorageBackend> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(backends.values());
        }
        return candidates.stream().filter(StorageBackendRegistry::safeAvailable).toList();
    }

    public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        StorageBackend backend;
        synchronized (this) {
            backend = backends.get(reference.backendId());
        }
        if (backend == null || !safeAvailable(backend)) {
            return StorageResolutionResult.failure(StorageResolutionStatus.BACKEND_UNAVAILABLE);
        }
        try {
            StorageResolutionResult result = Objects.requireNonNull(
                    backend.resolve(reference, player), "backend resolution result");
            return validateExactResolutionReference(reference, result);
        } catch (RuntimeException | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    /** @deprecated use resolveDefaultSessionsForPlayer; this never meant every network. */
    @Deprecated(forRemoval = false)
    public List<StorageSession> resolveAllForPlayer(ServerPlayer player) {
        return resolveDefaultSessionsForPlayer(player);
    }

    public List<StorageSession> resolveDefaultSessionsForPlayer(ServerPlayer player) {
        return resolveDefaultResultsForPlayer(player).stream()
                .map(StorageBackendResolution::result)
                .filter(StorageResolutionResult::resolved)
                .map(result -> result.session().orElseThrow())
                .toList();
    }

    /** @deprecated this resolves one backend-selected default per backend, not every network. */
    @Deprecated(forRemoval = false)
    public List<StorageBackendResolution> resolveAllResultsForPlayer(ServerPlayer player) {
        return resolveDefaultResultsForPlayer(player);
    }

    public List<StorageBackendResolution> resolveDefaultResultsForPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        List<Map.Entry<StorageBackendId, StorageBackend>> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(backends.entrySet());
        }
        List<StorageBackendResolution> results = new ArrayList<>(candidates.size());
        for (Map.Entry<StorageBackendId, StorageBackend> candidate : candidates) {
            StorageBackend backend = candidate.getValue();
            StorageResolutionResult result = safeAvailable(backend)
                    ? safeResolveForPlayer(candidate.getKey(), backend, player)
                    : StorageResolutionResult.failure(StorageResolutionStatus.BACKEND_UNAVAILABLE);
            results.add(new StorageBackendResolution(candidate.getKey(), result));
        }
        return List.copyOf(results);
    }

    public List<StorageBackendDiscovery> discoverAllNetworksForPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        StorageThreadGuard.requireServerThread(player);
        List<Map.Entry<StorageBackendId, StorageBackend>> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(backends.entrySet());
        }
        List<StorageBackendDiscovery> results = new ArrayList<>(candidates.size());
        for (Map.Entry<StorageBackendId, StorageBackend> candidate : candidates) {
            StorageBackend backend = candidate.getValue();
            StorageDiscoveryResult result = safeAvailable(backend)
                    ? safeDiscoverForPlayer(candidate.getKey(), backend, player)
                    : StorageDiscoveryResult.failure(StorageDiscoveryStatus.BACKEND_UNAVAILABLE);
            results.add(new StorageBackendDiscovery(candidate.getKey(), result));
        }
        return List.copyOf(results);
    }

    /**
     * Returns the networks the player may explicitly target, preserving backend
     * registration order. Failed/unavailable backends contribute no choices;
     * the caller can still use {@link #discoverAllNetworksForPlayer(ServerPlayer)}
     * when it needs diagnostics.
     */
    public List<StorageNetworkDescriptor> discoverNetworksForPlayer(ServerPlayer player) {
        List<StorageNetworkDescriptor> networks = new ArrayList<>();
        for (StorageBackendDiscovery discovery : discoverAllNetworksForPlayer(player)) {
            if (!discovery.result().successful()) continue;
            networks.addAll(discovery.result().networks());
        }
        return List.copyOf(networks);
    }

    private static boolean safeAvailable(StorageBackend backend) {
        try {
            return backend.isAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static StorageResolutionResult safeResolveForPlayer(StorageBackendId expectedBackendId,
                                                                 StorageBackend backend,
                                                                 ServerPlayer player) {
        try {
            StorageResolutionResult result = Objects.requireNonNull(
                    backend.resolveForPlayer(player), "backend resolution result");
            return validateResolutionOwnership(expectedBackendId, result);
        } catch (RuntimeException | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    private static StorageDiscoveryResult safeDiscoverForPlayer(StorageBackendId expectedBackendId,
                                                                 StorageBackend backend,
                                                                 ServerPlayer player) {
        try {
            StorageDiscoveryResult result = Objects.requireNonNull(
                    backend.discoverForPlayer(player), "backend discovery result");
            return validateDiscoveryOwnership(expectedBackendId, result);
        } catch (RuntimeException | LinkageError e) {
            return StorageDiscoveryResult.failure(StorageDiscoveryStatus.FAILED);
        }
    }

    static StorageDiscoveryResult validateDiscoveryOwnership(StorageBackendId expectedBackendId,
                                                              StorageDiscoveryResult result) {
        Objects.requireNonNull(expectedBackendId, "expectedBackendId");
        Objects.requireNonNull(result, "result");
        if (result.successful() && result.networks().stream()
                .anyMatch(network -> !expectedBackendId.equals(network.reference().backendId()))) {
            return StorageDiscoveryResult.failure(StorageDiscoveryStatus.FAILED);
        }
        return result;
    }

    static StorageResolutionResult validateResolutionOwnership(StorageBackendId expectedBackendId,
                                                                StorageResolutionResult result) {
        Objects.requireNonNull(expectedBackendId, "expectedBackendId");
        Objects.requireNonNull(result, "result");
        if (result.resolved() && !expectedBackendId.equals(
                result.session().orElseThrow().reference().backendId())) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
        return result;
    }

    static StorageResolutionResult validateExactResolutionReference(StorageReference expectedReference,
                                                                    StorageResolutionResult result) {
        Objects.requireNonNull(expectedReference, "expectedReference");
        Objects.requireNonNull(result, "result");
        if (result.resolved() && !expectedReference.equals(
                result.session().orElseThrow().reference())) {
            return StorageResolutionResult.failure(StorageResolutionStatus.INVALID_REFERENCE);
        }
        return result;
    }
}
