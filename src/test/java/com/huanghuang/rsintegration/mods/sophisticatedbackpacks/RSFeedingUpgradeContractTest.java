package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSFeedingUpgradeContractTest {
    @Test
    void dedicatedTranslationsExistInBothLanguages() throws IOException {
        String chinese = Files.readString(Path.of(
                "src/main/resources/assets/rs_integration/lang/zh_cn.json"));
        String english = Files.readString(Path.of(
                "src/main/resources/assets/rs_integration/lang/en_us.json"));

        assertTrue(chinese.contains("\"upgrade.rs_integration.rs_feeding\": \"次元喂食\""));
        assertTrue(chinese.contains("\"upgrade.rs_integration.rs_feeding.tooltip\": \"次元喂食设置\""));
        assertTrue(english.contains("\"upgrade.rs_integration.rs_feeding\": \"RS Feeding\""));
        assertTrue(english.contains("\"upgrade.rs_integration.rs_feeding.tooltip\": \"RS Feeding Settings\""));
    }

    @Test
    void customTabKeepsAdvancedControlsWithoutAdvancedTitle() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/huanghuang/rsintegration/mods/sophisticatedbackpacks/RSFeedingUpgradeTab.java"));

        assertFalse(source.contains("extends FeedingUpgradeTab.Advanced"));
        assertTrue(source.contains("extends FeedingUpgradeTab"));
        assertTrue(source.contains("HUNGER_LEVEL"));
        assertTrue(source.contains("FEED_IMMEDIATELY_WHEN_HURT"));
        assertTrue(source.contains("FilterLogicControl.Advanced"));
    }
}
