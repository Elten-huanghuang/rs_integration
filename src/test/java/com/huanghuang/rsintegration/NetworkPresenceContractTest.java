package com.huanghuang.rsintegration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPresenceContractTest {
    private static final Path MOD_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "RSIntegrationMod.java");
    private static final Path NETWORK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "network", "packet", "NetworkHandler.java");

    @Test
    void modDoesNotAllowAClientWithoutRsIntegration() throws IOException {
        String source = Files.readString(MOD_SOURCE);

        assertFalse(source.contains("IGNORESERVERONLY"),
                "RS Integration has client packets and must not advertise itself as server-only");
        assertFalse(source.contains("registerDisplayTest"),
                "Forge's default mod-list handshake must enforce client presence");
    }

    @Test
    void unifiedChannelStillRequiresMatchingProtocols() throws IOException {
        String source = Files.readString(NETWORK_SOURCE).replace("\r\n", "\n");

        assertTrue(source.contains("PROTOCOL_VERSION::equals,\n            PROTOCOL_VERSION::equals"),
                "both channel version predicates must remain strict");
    }
}
