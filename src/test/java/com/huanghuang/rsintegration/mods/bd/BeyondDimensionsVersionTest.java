package com.huanghuang.rsintegration.mods.bd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验超越维度版本判定的解析与 0.8 分界。 */
class BeyondDimensionsVersionTest {

    @Test
    void versionsBelowZeroEightAreLegacy() {
        assertFalse(BeyondDimensionsVersion.isModernVersion("0.7.30"));
        assertFalse(BeyondDimensionsVersion.isModernVersion("0.7"));
        assertFalse(BeyondDimensionsVersion.isModernVersion("0.7.0-SNAPSHOT"));
        assertFalse(BeyondDimensionsVersion.isModernVersion("0.0.1"));
    }

    @Test
    void versionsFromZeroEightOnAreModern() {
        assertTrue(BeyondDimensionsVersion.isModernVersion("0.8"));
        assertTrue(BeyondDimensionsVersion.isModernVersion("0.8.7"));
        assertTrue(BeyondDimensionsVersion.isModernVersion("0.8.7-beta+abc"));
        assertTrue(BeyondDimensionsVersion.isModernVersion("0.9.0"));
        assertTrue(BeyondDimensionsVersion.isModernVersion("0.10.3"));
        assertTrue(BeyondDimensionsVersion.isModernVersion("1.0.0"));
    }

    @Test
    void unparsableVersionsFallBackToLegacy() {
        // 版本号读不到时按旧版处理，避免把 0.8 的逻辑用到 0.7 上。
        assertFalse(BeyondDimensionsVersion.isModernVersion(""));
        assertFalse(BeyondDimensionsVersion.isModernVersion("   "));
        assertFalse(BeyondDimensionsVersion.isModernVersion(null));
        assertFalse(BeyondDimensionsVersion.isModernVersion("unknown"));
    }

    @Test
    void majorMinorParsingIgnoresSuffixes() {
        assertArrayEquals(new int[]{0, 8}, BeyondDimensionsVersion.parseMajorMinor("0.8.7"));
        assertArrayEquals(new int[]{1, 2}, BeyondDimensionsVersion.parseMajorMinor("1.2.3"));
        assertArrayEquals(new int[]{0, 8}, BeyondDimensionsVersion.parseMajorMinor("0.8.7-beta+abc"));
        assertArrayEquals(new int[]{0, 0}, BeyondDimensionsVersion.parseMajorMinor("unknown"));
        assertArrayEquals(new int[]{0, 0}, BeyondDimensionsVersion.parseMajorMinor(null));
    }
}
