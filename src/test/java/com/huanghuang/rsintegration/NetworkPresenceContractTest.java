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
    private static final Path RS_OPTIONAL_BOOTSTRAP_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "RSOptionalBootstrap.java");
    private static final Path MOD_ITEMS_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "ModItems.java");
    private static final Path RESONANCE_NETWORK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "network", "packet", "ResonanceNetworkHandler.java");
    private static final Path RS_SIDE_PANEL_NETWORK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "sidepanel", "RSSidePanelNetworkHandler.java");

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

    @Test
    void resonanceSyncRegistrationDoesNotDependOnRefinedStorage() throws IOException {
        String commonSource = Files.readString(MOD_SOURCE);
        String rsOptionalSource = Files.readString(RS_OPTIONAL_BOOTSTRAP_SOURCE);
        String modItemsSource = Files.readString(MOD_ITEMS_SOURCE);
        String resonanceNetworkSource = Files.readString(RESONANCE_NETWORK_SOURCE);
        String rsSidePanelNetworkSource = Files.readString(RS_SIDE_PANEL_NETWORK_SOURCE);

        assertTrue(commonSource.contains("ResonanceNetworkHandler.register();"),
                "BD-only resonance storage sends ResonanceSyncPacket and needs common registration");
        assertFalse(rsOptionalSource.contains("ResonanceNetworkHandler.register();"),
                "resonance packet registration must not be hidden behind the optional RS bootstrap");
        assertTrue(modItemsSource.contains("RESONANCE_BACKPACK = MENUS.register("),
                "the shared resonance menu must be registered even when RS is absent");
        assertFalse(rsOptionalSource.contains("resonance_backpack"),
                "the shared resonance menu must not be registered by the optional RS bootstrap");
        assertTrue(resonanceNetworkSource.contains(
                        "NetworkPacketIds.OPEN_RESONANCE_BACKPACK, OpenResonanceBackpackPacket.class"),
                "BD terminals need the shared resonance backpack packet without RS");
        assertFalse(rsSidePanelNetworkSource.contains("OpenResonanceBackpackPacket.class"),
                "shared resonance packets must not be registered by the RS-only side-panel handler");
    }
}
