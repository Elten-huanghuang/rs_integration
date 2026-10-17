package com.huanghuang.rsintegration.magic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulBeyondDimensionsContractTest {
    private static final String TRANSLATION_KEY = "types.rs_integration.goety_soul.name";
    private static final Path TYPE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulType.java");
    private static final Path RENDER_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulStackKeyRender.java");
    private static final Path RS_GRID_STACK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "MagicStackGridStack.java");
    private static final Path SHARED_TEXTURE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "LiquidSoulEnergyTexture.java");
    private static final Path UNIFIED_GRID_VIEW_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "unifiedgrid", "client", "UnifiedGridView.java");

    @Test
    void rsAndBeyondDimensionsShareLocalizedNameAndSoulIcon() throws Exception {
        String typeSource = Files.readString(TYPE_SOURCE);
        String renderSource = Files.readString(RENDER_SOURCE);
        String rsGridStackSource = Files.readString(RS_GRID_STACK_SOURCE);
        String sharedTextureSource = Files.readString(SHARED_TEXTURE_SOURCE);
        String unifiedGridViewSource = Files.readString(UNIFIED_GRID_VIEW_SOURCE);

        assertTrue(typeSource.contains(TRANSLATION_KEY));
        assertEquals("Soul Energy", translation("en_us.json"));
        assertEquals("灵魂能量", translation("zh_cn.json"));
        assertTrue(renderSource.contains("LiquidSoulEnergyTexture.render(gui, x, y)"));
        assertFalse(renderSource.contains("block/soul_absorber"));
        assertTrue(rsGridStackSource.contains("MagicResourceTexture.render(graphics, kind, x, y)"));
        // 客户端侧把翻译键交给 LanguageMap 解析；传的是键而不是已渲染文本，
        // RS 与 BD 仍然共享同一个翻译键。
        assertTrue(rsGridStackSource.contains("I18n.get(kind.translationKey())"));
        assertTrue(rsGridStackSource.contains("formatWithUnits(amount)"));
        assertFalse(rsGridStackSource.contains("formatInBucketFormWithOnlyTrailingDigitsIfZero(amount)"));
        assertTrue(sharedTextureSource.contains(
                "new ResourceLocation(\"rs_integration\", \"block/liquid_soul_energy\")"));
        assertTrue(unifiedGridViewSource.contains("ingredient instanceof MagicResourceStack"));
        assertSoulTexture();
    }

    private static void assertSoulTexture() throws Exception {
        String path = "assets/rs_integration/textures/block/liquid_soul_energy.png";
        try (InputStream stream = GoetySoulBeyondDimensionsContractTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "缺少灵魂能量贴图 " + path);
            BufferedImage image = ImageIO.read(stream);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            boolean fullyOpaque = true;
            for (int y = 0; y < image.getHeight() && fullyOpaque; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 255) {
                        fullyOpaque = false;
                        break;
                    }
                }
            }
            assertTrue(fullyOpaque, "液态灵魂能量贴图应铺满 16×16");
        }
    }

    private static String translation(String fileName) throws Exception {
        String path = "assets/rs_integration/lang/" + fileName;
        try (InputStream stream = GoetySoulBeyondDimensionsContractTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "缺少语言资源 " + path);
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(json.has(TRANSLATION_KEY), "缺少翻译键 " + TRANSLATION_KEY);
            return json.get(TRANSLATION_KEY).getAsString();
        }
    }
}
