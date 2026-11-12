package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.client.gui.MaterialCellStyle;
import com.huanghuang.rsintegration.config.ConfigEditorModel.ConfigFile;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorGridStyle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingUiColorsTest extends BootstrapTest {
    @BeforeEach
    @AfterEach
    void unloadClientConfig() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
    }

    @Test
    void parsesRgbAndArgbColors() {
        assertEquals(0xFFD0CFD6, CraftingUiColors.parse("#D0CFD6"));
        assertEquals(0x50000000, CraftingUiColors.parse("#50000000"));
        assertEquals(0xFFABCDEF, CraftingUiColors.parse(" #abcdef "));
    }

    @Test
    void rejectsAmbiguousOrMalformedColors() {
        assertFalse(CraftingUiColors.isValid("D0CFD6"));
        assertFalse(CraftingUiColors.isValid("#12345"));
        assertFalse(CraftingUiColors.isValid("#GGGGGG"));
        assertFalse(CraftingUiColors.isValid(null));
    }

    @Test
    void defaultsRemainTheCurrentPalette() {
        CommentedConfig values = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(values);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        try {
            assertEquals(0xFFD0CFD6, CraftingUiColors.PANEL.argb());
            assertEquals(0xFFA1A5BB, CraftingUiColors.MATERIAL_STORED.argb());
            assertTrue(CraftingUiColors.PANEL.argb() != CraftingUiColors.CANVAS.argb());
        } finally {
            RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        }
    }

    @Test
    void loadedAndReloadedColorsReachAlreadyInitializedUiAndMaterialStyles() {
        assertEquals(0xFFD0CFD6, CraftingUiStyle.panel());
        assertEquals(0xFF88B0AD, MaterialCellStyle.RUNNING.color());
        assertEquals(0xFF88B0AD, MonitorGridStyle.RUNNING.color());

        CommentedConfig first = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(first);
        first.set("craftingUi.panel", "#123456");
        first.set("craftingUi.materialRunning", "#80402010");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(first);
        assertEquals(0xFF123456, CraftingUiStyle.panel());
        assertEquals(0x80402010, MaterialCellStyle.RUNNING.color());
        assertEquals(0x80402010, MonitorGridStyle.RUNNING.color());

        CommentedConfig second = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(second);
        second.set("craftingUi.panel", "#ABCDEF");
        second.set("craftingUi.materialRunning", "#112233");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(second);
        assertEquals(0xFFABCDEF, CraftingUiStyle.panel());
        assertEquals(0xFF112233, MaterialCellStyle.RUNNING.color());
        assertEquals(0xFF112233, MonitorGridStyle.RUNNING.color());

        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        assertEquals(0xFFD0CFD6, CraftingUiStyle.panel());
        assertEquals(0xFF88B0AD, MonitorGridStyle.RUNNING.color());
    }

    @Test
    void configEditorValidatesColorsAndAppliesThemToBothMaterialViews() {
        CommentedConfig values = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(values);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        var file = new ConfigFile("client", "rs_integration/client.toml", RSIntegrationConfig.CLIENT_SPEC, false);
        var model = new ConfigEditorModel(List.of(file));
        var option = model.entries().stream().filter(entry -> entry.path().equals("craftingUi.materialBlocked"))
                .findFirst().orElseThrow();
        assertFalse(option.requiresRestart());
        option.setText("#GG0000");
        assertFalse(option.isValid());
        option.setText("#CC1122");
        assertTrue(option.isValid());
        model.save(ignored -> {});
        assertEquals(0xFFCC1122, MaterialCellStyle.BLOCKED.color());
        assertEquals(0xFFCC1122, MonitorGridStyle.BLOCKED.color());
    }

    @Test
    void invalidFileColorsAreCorrectedWithoutChangingValidOverrides() {
        CommentedConfig values = CommentedConfig.inMemory();
        values.set("craftingUi.panel", "#bad-color");
        values.set("craftingUi.text", "#ffffff");
        RSIntegrationConfig.CLIENT_SPEC.correct(values);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        assertEquals(0xFFD0CFD6, CraftingUiStyle.panel());
        assertEquals(0xFFFFFFFF, CraftingUiStyle.text());
    }

    @Test
    void exampleContainsEveryRegisteredColorWithItsUnchangedDefault() throws IOException {
        var example = new TomlParser().parse(Files.readString(Path.of("docs/config/crafting-ui.example.toml")));
        var colors = RSIntegrationConfig.CLIENT_SPEC.getValues().get("craftingUi");
        assertTrue(colors instanceof UnmodifiableConfig);
        UnmodifiableConfig section = (UnmodifiableConfig) colors;
        assertEquals(CraftingUiColors.values().length, section.valueMap().size());
        for (String key : section.valueMap().keySet()) {
            ForgeConfigSpec.ConfigValue<?> value = section.get(key);
            assertEquals(value.getDefault(), example.get("craftingUi." + key), key);
        }
    }
}
