package com.huanghuang.rsintegration.storage;

import net.minecraftforge.fml.ModList;

import java.util.Objects;
import java.util.function.Predicate;

/** Keeps optional adapter classes behind a mod-presence and reflection boundary. */
public final class StorageBackendLoader {
    private StorageBackendLoader() {}

    public static StorageBackendLoadResult load(OptionalStorageBackendDescriptor descriptor,
                                                StorageBackendRegistry registry) {
        return load(descriptor, registry, modId -> ModList.get().isLoaded(modId),
                StorageBackendLoader.class.getClassLoader());
    }

    static StorageBackendLoadResult load(OptionalStorageBackendDescriptor descriptor,
                                         StorageBackendRegistry registry,
                                         Predicate<String> modLoaded,
                                         ClassLoader classLoader) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(modLoaded, "modLoaded");
        Objects.requireNonNull(classLoader, "classLoader");
        final boolean present;
        try {
            present = modLoaded.test(descriptor.modId());
        } catch (RuntimeException | LinkageError e) {
            return result(descriptor, StorageBackendLoadStatus.FAILED);
        }
        if (!present) return result(descriptor, StorageBackendLoadStatus.MOD_NOT_LOADED);

        Class<?> providerClass;
        try {
            providerClass = Class.forName(descriptor.providerClassName(), true, classLoader);
        } catch (ClassNotFoundException e) {
            return result(descriptor, StorageBackendLoadStatus.PROVIDER_NOT_FOUND);
        } catch (RuntimeException | LinkageError e) {
            return result(descriptor, StorageBackendLoadStatus.FAILED);
        }
        if (!StorageBackendProvider.class.isAssignableFrom(providerClass)) {
            return result(descriptor, StorageBackendLoadStatus.INVALID_PROVIDER);
        }
        try {
            StorageBackendProvider provider = (StorageBackendProvider) providerClass.getDeclaredConstructor().newInstance();
            StorageBackend backend = Objects.requireNonNull(provider.createBackend(), "provider backend");
            if (!descriptor.backendId().equals(backend.id())) {
                return result(descriptor, StorageBackendLoadStatus.ID_MISMATCH);
            }
            registry.register(backend);
            return result(descriptor, StorageBackendLoadStatus.LOADED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return result(descriptor, StorageBackendLoadStatus.FAILED);
        }
    }

    private static StorageBackendLoadResult result(OptionalStorageBackendDescriptor descriptor,
                                                   StorageBackendLoadStatus status) {
        return new StorageBackendLoadResult(descriptor.backendId(), status);
    }
}
