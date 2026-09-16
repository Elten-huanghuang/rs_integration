package com.huanghuang.rsintegration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiNetworkOverlayCompatibilityTest {
    @Test
    void mixinPluginProbesTheActualJeiTargetInsteadOfAnOldPackage() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mixin/plugin/RSIntegrationMixinPlugin.java"),
                StandardCharsets.UTF_8);
        int branch = source.indexOf("jei.IngredientListRendererNetworkOverlayMixin");
        assertTrue(branch >= 0);
        String body = source.substring(branch, Math.min(source.length(), branch + 300));
        assertTrue(body.contains("isClassPresent(targetClassName)"));
        assertFalse(body.contains("mezz.jei.gui.overlay.IngredientListRenderer"));
    }

    @Test
    void mixinTargetsTheJei1549IngredientRendererPackage() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mixin/jei/IngredientListRendererNetworkOverlayMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("mezz.jei.gui.overlay.ingredients.IngredientListRenderer"));
        assertTrue(source.contains("@Inject(method = \"render\", at = @At(\"TAIL\"))"));
    }

    @Test
    void legacyMixinTargetsTheJei1520RendererPackage() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mixin/jei/IngredientListRendererLegacyNetworkOverlayMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("mezz.jei.gui.overlay.IngredientListRenderer"));
        assertFalse(source.contains("overlay.ingredients.IngredientListRenderer"));
    }
}
