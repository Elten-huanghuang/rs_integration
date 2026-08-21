package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RefinedStorageDriverIsolationTest {
    @Test
    void testableSessionAndExecutorDoNotLinkNativeRsTypes() throws IOException {
        for (Class<?> type : List.of(RefinedStorageDriver.class,
                RefinedStorageSnapshotRead.class,
                RefinedStorageOperationExecutor.class,
                RefinedStorageSnapshotMapper.class,
                RefinedStorageSession.class,
                RefinedStorageIds.class,
                RefinedStoragePermissionRules.class)) {
            String constants = new String(classBytes(type), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("com/refinedmods/refinedstorage"),
                    type.getSimpleName() + " links native RS API");
            assertFalse(constants.contains("RefinedStorageBackend"),
                    type.getSimpleName() + " links the native backend class");
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String name = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing class resource: " + name);
            return input.readAllBytes();
        }
    }
}
