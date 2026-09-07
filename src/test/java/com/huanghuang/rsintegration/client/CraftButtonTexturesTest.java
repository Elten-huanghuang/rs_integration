package com.huanghuang.rsintegration.client;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CraftButtonTexturesTest {
    private BufferedImage read(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/assets/rs_integration/textures/gui/recipe_buttons/" + name + ".png")) {
            assertNotNull(stream, name);
            return ImageIO.read(stream);
        }
    }

    @Test void backgroundsRetainAllOriginalNormalAndHoverColors() throws IOException {
        var atlas = read("backgrounds");
        int[] borders = {0x888888,0xFFFFFF,0xAA3333,0xFF6666,0x33AA33,0x66FF66,0x556677,0x88AACC};
        int[] backgrounds = {0x333333,0x555555,0x662222,0xAA3333,0x226622,0x33AA33,0x334455,0x556688};
        assertEquals(48, atlas.getWidth());
        assertEquals(6, atlas.getHeight());
        for (int state = 0; state < 8; state++) {
            for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
                int expected = x >= 2 && x < 4 && y >= 2 && y < 4 ? backgrounds[state] : borders[state];
                assertEquals(expected | 0xFF000000, atlas.getRGB(state * 6 + x, y));
            }
        }
    }

    @Test void symbolsHaveTransparentBackgroundAndOriginalPlusShadow() throws IOException {
        var symbols = read("symbols");
        assertEquals(96, symbols.getWidth());
        assertEquals(16, symbols.getHeight());
        for (int state = 0; state < 6; state++) {
            int painted = 0;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                if ((symbols.getRGB(state * 16 + x, y) >>> 24) != 0) painted++;
            }
            assertTrue(painted > 0 && painted < 128);
            assertEquals(0, symbols.getRGB(state * 16 + 15, 0) >>> 24);
        }
        assertEquals(0xFFAAAAAA, symbols.getRGB(4, 2));
        assertEquals(0xFF2A2A2A, symbols.getRGB(6, 4));
    }

    @Test void monitorRetainsBluePalette() throws IOException {
        var parts = read("machine_parts");
        int[] colors = {0x8899AA,0xAABBCC,0x667788,0xCCDDEE,0xEEF4FF,0x99AACC};
        for (int i = 0; i < colors.length; i++) assertEquals(colors[i] | 0xFF000000, parts.getRGB(i * 2, 0));
    }
}
