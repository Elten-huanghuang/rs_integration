package com.huanghuang.rsintegration.crafting.plan;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanUiConfigTest {
    @AfterEach
    void unload() { PlanUiConfig.SPEC.setConfig(null); }

    @Test
    void rgbIsOpaqueAndArgbPreservesTransparency() {
        assertEquals(0xFF123456, PlanUiConfig.parseColor("#123456", 0));
        assertEquals(0x80123456, PlanUiConfig.parseColor("#80123456", 0));
        assertEquals(0x00123456, PlanUiConfig.parseColor("#00123456", 0));
        assertEquals(123, PlanUiConfig.parseColor("not-a-color", 123));
        assertFalse(PlanUiConfig.isColor("#12345"));
        assertFalse(PlanUiConfig.isColor("#GG1234"));
    }

    @Test
    void reloadingThemeUpdatesColorsAndLayoutWithoutRestarting() {
        CommentedConfig first = CommentedConfig.inMemory();
        first.set("colors.accent", "#336699");
        first.set("layout.panelWidth", 280);
        PlanUiConfig.SPEC.setConfig(first);
        assertEquals(0xFF336699, PlanUiConfig.Color.ACCENT.value());
        assertEquals(280, PlanUiConfig.panelWidth());

        CommentedConfig second = CommentedConfig.inMemory();
        second.set("colors.accent", "#80ABCDEF");
        second.set("layout.panelWidth", 320);
        PlanUiConfig.SPEC.setConfig(second);
        assertEquals(0x80ABCDEF, PlanUiConfig.Color.ACCENT.value());
        assertEquals(320, PlanUiConfig.panelWidth());
    }

    @Test
    void invalidConfigurationIsCorrectedToUsableDefaults() {
        CommentedConfig values = CommentedConfig.inMemory();
        values.set("colors.text", "broken");
        values.set("layout.listRowHeight", -1);
        PlanUiConfig.SPEC.setConfig(values);
        assertEquals(0xFFE5EEE8, PlanUiConfig.Color.TEXT.value());
        assertTrue(PlanUiConfig.rowHeight() >= 22);
    }
}
