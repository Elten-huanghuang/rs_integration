package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StorageBackendLoaderTest {
    private static final StorageBackendId ID = new StorageBackendId("loader_test");

    @Test
    void absentModDoesNotAttemptToLoadProviderClass() {
        ClassLoader failOnUse = new ClassLoader() {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("provider class loader must not be touched");
            }
        };
        StorageBackendLoadResult result = StorageBackendLoader.load(
                new OptionalStorageBackendDescriptor(ID, "missing_mod", "missing.Provider"),
                new StorageBackendRegistry(), ignored -> false, failOnUse);

        assertEquals(StorageBackendLoadStatus.MOD_NOT_LOADED, result.status());
    }

    @Test
    void matchingProviderRegistersBackend() {
        StorageBackendRegistry registry = new StorageBackendRegistry();
        StorageBackendLoadResult result = StorageBackendLoader.load(
                new OptionalStorageBackendDescriptor(ID, "present_mod", TestProvider.class.getName()),
                registry, ignored -> true, getClass().getClassLoader());

        assertEquals(StorageBackendLoadStatus.LOADED, result.status());
        assertSame(TestProvider.BACKEND, registry.get(ID).orElseThrow());
    }

    @Test
    void modPresenceFailureIsContained() {
        StorageBackendLoadResult result = StorageBackendLoader.load(
                new OptionalStorageBackendDescriptor(ID, "broken_mod", TestProvider.class.getName()),
                new StorageBackendRegistry(), ignored -> { throw new IllegalStateException("broken"); },
                getClass().getClassLoader());

        assertEquals(StorageBackendLoadStatus.FAILED, result.status());
    }

    public static final class TestProvider implements StorageBackendProvider {
        static final StorageBackend BACKEND = new TestBackend();

        @Override
        public StorageBackend createBackend() { return BACKEND; }
    }

    private static final class TestBackend implements StorageBackend {
        @Override public StorageBackendId id() { return ID; }
        @Override public boolean isAvailable() { return true; }
        @Override public StorageResolutionResult resolveForPlayer(ServerPlayer player) {
            return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
        }
        @Override public StorageResolutionResult resolve(StorageReference reference, ServerPlayer player) {
            return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
        }
    }
}
