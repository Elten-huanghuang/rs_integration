package com.huanghuang.rsintegration.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeyondDimensionsOverlayLayerContractTest {
    private static final Path CLIENT_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "machine", "BeyondDimensionsMachineHubClient.java");
    private static final Path MIXIN_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mixin", "beyonddimensions", "BDBaseGuiOverlayMixin.java");

    @Test
    void terminalControlsRenderBeforeBdItemTooltip() throws IOException {
        String clientSource = Files.readString(CLIENT_SOURCE);
        String mixinSource = Files.readString(MIXIN_SOURCE);

        assertTrue(mixinSource.contains("at = @At(\"HEAD\")"));
        assertTrue(mixinSource.contains("renderTerminalControlsBeforeTooltip"));
        assertTrue(clientSource.contains("renderMachineCenterEntry(screen, graphics, mouseX, mouseY);"));
        assertTrue(clientSource.contains("renderResonanceEntry(screen, graphics, mouseX, mouseY);"));
        assertFalse(clientSource.contains("translate(0, 0, 460)"),
                "terminal controls must stay below Minecraft's tooltip layer");
    }
}
