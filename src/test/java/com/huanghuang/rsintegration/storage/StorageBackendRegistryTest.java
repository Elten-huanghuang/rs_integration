package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackendRegistryTest {
    @Test
    void registersByStableIdAndFiltersUnavailableAdapters() {
        StorageBackendRegistry registry = new StorageBackendRegistry();
        FakeBackend rs = new FakeBackend("refinedstorage", true);
        FakeBackend bd = new FakeBackend("beyond_dimensions", false);

        registry.register(rs);
        registry.register(bd);

        assertSame(rs, registry.get(rs.id()).orElseThrow());
        assertEquals(1, registry.availableBackends().size());
        assertSame(rs, registry.availableBackends().iterator().next());
        assertTrue(registry.get(new StorageBackendId("missing")).isEmpty());
    }

    @Test
    void duplicateIdsFailInsteadOfSilentlyReplacingAnAdapter() {
        StorageBackendRegistry registry = new StorageBackendRegistry();
        registry.register(new FakeBackend("refinedstorage", true));

        assertThrows(IllegalStateException.class,
                () -> registry.register(new FakeBackend("refinedstorage", true)));
    }

    @Test
    void brokenAvailabilityCheckDoesNotHideHealthyBackends() {
        StorageBackendRegistry registry = new StorageBackendRegistry();
        FakeBackend healthy = new FakeBackend("healthy", true);
        registry.register(new ThrowingBackend());
        registry.register(healthy);

        assertEquals(java.util.List.of(healthy), registry.availableBackends());
    }

    @Test
    void discoveryCannotClaimNetworksOwnedByAnotherBackend() {
        StorageBackendId registered = new StorageBackendId("registered");
        StorageDiscoveryResult foreign = StorageDiscoveryResult.success(java.util.List.of(
                new StorageNetworkDescriptor(new StorageReference(
                        new StorageBackendId("foreign"), "network"), "Foreign", true)));

        StorageDiscoveryResult result = StorageBackendRegistry.validateDiscoveryOwnership(
                registered, foreign);

        assertEquals(StorageDiscoveryStatus.FAILED, result.status());
        assertTrue(result.networks().isEmpty());
    }

    @Test
    void resolutionCannotReturnASessionOwnedByAnotherBackend() {
        StorageBackendId registered = new StorageBackendId("registered");
        StorageReference foreignReference = new StorageReference(
                new StorageBackendId("foreign"), "network");
        StorageSession foreignSession = new StorageSession() {
            @Override public StorageReference reference() { return foreignReference; }
            @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
                throw new UnsupportedOperationException();
            }
            @Override public StoragePermissionResult checkPermission(
                    ServerPlayer player, StoragePermission permission) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractExact(
                    ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractMatching(
                    ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult insert(
                    ServerPlayer player, ItemStack stack, boolean simulate) {
                throw new UnsupportedOperationException();
            }
        };

        StorageResolutionResult result = StorageBackendRegistry.validateResolutionOwnership(
                registered, StorageResolutionResult.resolved(foreignSession));

        assertEquals(StorageResolutionStatus.FAILED, result.status());
        assertTrue(result.session().isEmpty());
    }

    @Test
    void explicitResolutionCannotReturnAnotherNetworkFromTheSameBackend() {
        StorageBackendId backend = new StorageBackendId("registered");
        StorageReference requested = new StorageReference(backend, "requested");
        StorageReference returned = new StorageReference(backend, "returned");
        StorageSession wrongSession = session(returned);

        StorageResolutionResult result = StorageBackendRegistry.validateExactResolutionReference(
                requested, StorageResolutionResult.resolved(wrongSession));

        assertEquals(StorageResolutionStatus.INVALID_REFERENCE, result.status());
        assertTrue(result.session().isEmpty());
    }

    @Test
    void defaultDiscoveryPublishesTheResolvedSessionsCapabilities() {
        StorageBackendId backendId = new StorageBackendId("capable");
        StorageReference reference = new StorageReference(backendId, "network");
        StorageSession capableSession = new StorageSession() {
            @Override public StorageReference reference() { return reference; }
            @Override public java.util.Set<StorageCapability> capabilities() {
                return java.util.Set.of(StorageCapability.ITEM_STORAGE,
                        StorageCapability.SNAPSHOT_REVISION);
            }
            @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
                throw new UnsupportedOperationException();
            }
            @Override public StoragePermissionResult checkPermission(
                    ServerPlayer player, StoragePermission permission) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractExact(
                    ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractMatching(
                    ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult insert(
                    ServerPlayer player, ItemStack stack, boolean simulate) {
                throw new UnsupportedOperationException();
            }
        };
        StorageBackend backend = new StorageBackend() {
            @Override public StorageBackendId id() { return backendId; }
            @Override public boolean isAvailable() { return true; }
            @Override public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
                return StorageResolutionResult.resolved(capableSession);
            }
            @Override public StorageResolutionResult resolve(
                    StorageReference requested, ServerPlayer player) {
                return StorageResolutionResult.resolved(capableSession);
            }
        };

        StorageNetworkDescriptor descriptor = backend.discoverForPlayer(null)
                .networks().get(0);

        assertEquals(capableSession.capabilities(), descriptor.capabilities());
    }

    private static StorageSession session(StorageReference reference) {
        return new StorageSession() {
            @Override public StorageReference reference() { return reference; }
            @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
                throw new UnsupportedOperationException();
            }
            @Override public StoragePermissionResult checkPermission(
                    ServerPlayer player, StoragePermission permission) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractExact(
                    ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult extractMatching(
                    ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
                throw new UnsupportedOperationException();
            }
            @Override public StorageOperationResult insert(
                    ServerPlayer player, ItemStack stack, boolean simulate) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record FakeBackend(StorageBackendId id, boolean isAvailable) implements StorageBackend {
        FakeBackend(String id, boolean available) {
            this(new StorageBackendId(id), available);
        }

        @Override
        public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
            return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
        }

        @Override
        public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
            return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
        }
    }

    private static final class ThrowingBackend implements StorageBackend {
        @Override public StorageBackendId id() { return new StorageBackendId("broken"); }
        @Override public boolean isAvailable() { throw new NoClassDefFoundError("optional api"); }
        @Override public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
            throw new AssertionError("must not resolve unavailable backend");
        }
        @Override public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
            throw new AssertionError("must not resolve unavailable backend");
        }
    }
}
