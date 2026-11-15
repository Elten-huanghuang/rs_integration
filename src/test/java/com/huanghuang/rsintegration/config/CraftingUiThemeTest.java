package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.client.gui.MaterialCellStyle;
import com.huanghuang.rsintegration.config.ConfigEditorModel.ConfigFile;
import com.huanghuang.rsintegration.config.ConfigEditorModel.Option;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorGridStyle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CraftingUiThemeTest extends BootstrapTest {
    @AfterEach
    void unload() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        PlanUiConfig.SPEC.setConfig(null);
    }

    @Test
    void oldFilesKeepTheirColorsAndSwitchingBackRestoresCustomArgbAndLayout() {
        CommentedConfig client = new TomlParser().parse("[craftingUi]\npanel = \"#80123456\"\n");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(client);
        CommentedConfig tree = CommentedConfig.inMemory();
        tree.set("colors.panel", "#90654321");
        tree.set("layout.panelWidth", 333);
        PlanUiConfig.SPEC.setConfig(tree);
        assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
        assertEquals(0x80123456, CraftingUiStyle.panel());
        assertEquals(0x90654321, Color.PANEL.value());

        for (CraftingUiTheme theme : CraftingUiTheme.values()) {
            if (theme == CraftingUiTheme.CUSTOM) continue;
            apply(theme);
            assertEquals(theme, CraftingUiTheme.current());
            assertEquals(Color.PANEL.value(), CraftingUiStyle.panel());
            assertNotEquals(0x80123456, CraftingUiStyle.panel());
            assertEquals(333, PlanUiConfig.panelWidth());
            assertEquals("#80123456", client.get("craftingUi.panel"));
            assertEquals("#90654321", tree.get("colors.panel"));
        }

        apply(CraftingUiTheme.CUSTOM);
        assertEquals(0x80123456, CraftingUiStyle.panel());
        assertEquals(0x90654321, Color.PANEL.value());
    }

    @Test
    void everyPresetHasDistinctReadableSurfacesAndSixMaterialStates() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        Set<Integer> backgrounds = new HashSet<>();
        for (CraftingUiTheme theme : CraftingUiTheme.values()) {
            if (theme == CraftingUiTheme.CUSTOM) continue;
            apply(theme);
            assertTrue(backgrounds.add(Color.BACKGROUND.value()), theme.name());
            for (Color color : Color.values()) assertNotEquals(0, color.value(), theme + ": " + color);
            for (CraftingUiColors color : CraftingUiColors.values()) assertNotEquals(0, color.argb(), theme + ": " + color);
            for (Color color : List.of(Color.BACKGROUND, Color.PANEL, Color.SURFACE, Color.HOVER,
                    Color.SELECTED, Color.PRIMARY, Color.WARNING_SURFACE, Color.ERROR_SURFACE)) {
                assertReadable(Color.TEXT.value(), color.value(), theme + ": " + color);
            }
            assertReadable(CraftingUiColors.BUTTON_TEXT.argb(), CraftingUiColors.BUTTON.argb(), theme + ": button");
            assertReadable(CraftingUiColors.BUTTON_TEXT.argb(), CraftingUiColors.BUTTON_HOVER.argb(), theme + ": buttonHover");
            assertReadable(CraftingUiColors.BUTTON_TEXT.argb(), CraftingUiColors.primaryButtonArgb(), theme + ": primaryButton");
            for (CraftingUiColors background : List.of(CraftingUiColors.CANVAS, CraftingUiColors.PANEL,
                    CraftingUiColors.ROW, CraftingUiColors.ROW_HOVER, CraftingUiColors.SELECTED_ROW)) {
                assertReadable(CraftingUiStyle.accent(), background.argb(), theme + ": accent on " + background);
            }
            Set<Integer> states = new HashSet<>();
            for (MaterialCellStyle state : MaterialCellStyle.values()) {
                assertTrue(states.add(state.color()), theme + ": " + state);
                assertReadable(CraftingUiStyle.text(), state.color(), theme + ": " + state);
            }
            assertEquals(MaterialCellStyle.RUNNING.color(), MonitorGridStyle.RUNNING.color());
            assertEquals(MaterialCellStyle.BLOCKED.color(), MonitorGridStyle.BLOCKED.color());
        }
    }

    @Test
    void reloadingAndUnloadingClientConfigUpdatesAlreadyInitializedTreeAndMonitor() {
        assertEquals(0xFF17251E, Color.PANEL.value());
        assertEquals(0xFFD0CFD6, CraftingUiStyle.panel());
        for (String theme : List.of("OCEAN", "WARM_PAPER", "AMETHYST", "FOREST")) {
            RSIntegrationConfig.CLIENT_SPEC.setConfig(new TomlParser().parse("[craftingUi]\ntheme = \"" + theme + "\"\n"));
            assertTrue(CraftingUiTheme.migrateIndependentThemes());
            assertEquals(CraftingUiTheme.valueOf(theme), CraftingUiTheme.current());
            assertEquals(Color.PANEL.value(), CraftingUiStyle.panel());
        }
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
        assertEquals(0xFF17251E, Color.PANEL.value());
        assertEquals(0xFFD0CFD6, CraftingUiStyle.panel());
    }

    @Test
    void invalidThemeFallsBackToCustomWithoutResettingColors() {
        CommentedConfig client = new TomlParser().parse("[craftingUi]\ntheme = \"UNKNOWN\"\npanel = \"#123456\"\n");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(client);
        assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
        assertEquals(0xFF123456, CraftingUiStyle.panel());
    }

    @Test
    void customPrimaryButtonKeepsFollowingCustomAccent() {
        CommentedConfig client = new TomlParser().parse("[craftingUi]\naccent = \"#80402010\"\nbutton = \"#112233\"\n");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(client);
        assertEquals(0x80402010, CraftingUiColors.primaryButtonArgb());
        apply(CraftingUiTheme.FOREST);
        assertNotEquals(CraftingUiStyle.accent(), CraftingUiColors.primaryButtonArgb());
        apply(CraftingUiTheme.CUSTOM);
        assertEquals(0x80402010, CraftingUiColors.primaryButtonArgb());
    }

    @Test
    void configEditorCyclesOnlyDraftUntilApplyAndResetRestoresCustom() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        var model = new ConfigEditorModel(List.of(new ConfigFile("client", "rs_integration/client.toml",
                RSIntegrationConfig.CLIENT_SPEC, false)));
        Option option = model.entries().stream().filter(entry -> entry.path().equals("craftingUi.theme"))
                .findFirst().orElseThrow();
        assertFalse(option.requiresRestart());
        assertEquals("监控器主题", option.displayName());
        for (int index = 0; index < CraftingUiTheme.values().length; index++) {
            CraftingUiTheme next = ((CraftingUiTheme) option.parsed()).next();
            option.setText(next.name());
            assertTrue(option.isValid());
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
        }
        assertFalse(model.isDirty());
        option.setText("OCEAN");
        model.save(ignored -> {});
        assertEquals(CraftingUiTheme.OCEAN, CraftingUiTheme.current());
        option.resetDefault();
        assertEquals(CraftingUiTheme.OCEAN, CraftingUiTheme.current());
        model.save(ignored -> {});
        assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
    }

    @Test
    void directSelectionSavesOnceAndRollsBackOnSaveFailure() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        try (var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            config.when(RSIntegrationConfig::saveClientConfig).thenAnswer(ignored -> null);
            assertTrue(CraftingUiTheme.select(CraftingUiTheme.QUARTZ));
            assertTrue(CraftingUiTheme.select(CraftingUiTheme.QUARTZ));
            assertEquals(CraftingUiTheme.QUARTZ, CraftingUiTheme.current());
            config.verify(RSIntegrationConfig::saveClientConfig, times(1));
            config.when(RSIntegrationConfig::saveClientConfig).thenThrow(new IllegalStateException("模拟磁盘写入失败"));
            assertThrows(IllegalStateException.class, () -> CraftingUiTheme.select(CraftingUiTheme.BRASS));
            assertEquals(CraftingUiTheme.QUARTZ, CraftingUiTheme.current());
        }
    }

    @Test
    void unloadedConfigCannotBeChangedAndCycleWorksInBothDirections() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        assertFalse(CraftingUiTheme.select(CraftingUiTheme.ARCANE));
        for (CraftingUiTheme theme : CraftingUiTheme.values()) {
            assertEquals(theme, theme.next().previous());
            assertEquals(theme, theme.previous().next());
        }
    }

    @Test
    void warmPaperRetainsItsColorsAndVanillaUsesWhitePixelHighlights() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        CraftingUiTheme.select(CraftingUiTheme.WARM_PAPER);
        CraftingUiTheme.select(CraftingUiTheme.Scope.TREE, CraftingUiTheme.WARM_PAPER);
        assertEquals(0xFFE7DDCB, Color.BACKGROUND.value());
        assertEquals(0xFF3D3328, Color.TEXT.value());
        assertEquals(0xFFE6DBCB, CraftingUiColors.HIGHLIGHT.argb());
        CraftingUiTheme.select(CraftingUiTheme.VANILLA);
        CraftingUiTheme.select(CraftingUiTheme.Scope.TREE, CraftingUiTheme.VANILLA);
        assertEquals(0xFFC6C6C6, Color.PANEL.value());
        assertEquals(0xFFFFFFFF, CraftingUiColors.HIGHLIGHT.argb());
    }

    private static void apply(CraftingUiTheme theme) {
        CraftingUiTheme.migrateIndependentThemes();
        var model = new ConfigEditorModel(List.of(new ConfigFile("client", "rs_integration/client.toml",
                RSIntegrationConfig.CLIENT_SPEC, false)));
        Option option = model.entries().stream().filter(entry -> entry.path().equals("craftingUi.theme"))
                .findFirst().orElseThrow();
        option.setText(theme.name());
        model.entries().stream().filter(entry -> entry.path().equals("craftingUi.treeTheme"))
                .findFirst().orElseThrow().setText(theme.name());
        model.save(ignored -> {});
    }

    @Test
    void oldThemeMigratesOnceAndBothSelectionsSurviveReloadIndependently() {
        CommentedConfig values = new TomlParser().parse("[craftingUi]\ntheme = \"WARM_PAPER\"\n");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        assertTrue(CraftingUiTheme.migrateIndependentThemes());
        assertEquals(CraftingUiTheme.WARM_PAPER, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
        int paper = CraftingUiStyle.panel();
        assertTrue(CraftingUiTheme.select(CraftingUiTheme.Scope.TREE, CraftingUiTheme.ARCANE));
        assertEquals(paper, CraftingUiStyle.panel());
        int arcane = Color.PANEL.value();
        assertTrue(CraftingUiTheme.select(CraftingUiTheme.Scope.MONITOR, CraftingUiTheme.BLACK_GLASS));
        assertEquals(arcane, Color.PANEL.value());
        assertFalse(CraftingUiTheme.migrateIndependentThemes());
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        assertEquals(CraftingUiTheme.ARCANE, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
        assertEquals(CraftingUiTheme.BLACK_GLASS, CraftingUiTheme.current(CraftingUiTheme.Scope.MONITOR));
    }

    @Test
    void treeSaveFailureRestoresBothScopesAndMigrationState() {
        CommentedConfig values = new TomlParser().parse("[craftingUi]\ntheme = \"BRASS\"\n");
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        try (var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            config.when(RSIntegrationConfig::saveClientConfig).thenThrow(new IllegalStateException("模拟保存失败"));
            assertThrows(IllegalStateException.class, () -> CraftingUiTheme.select(CraftingUiTheme.Scope.TREE, CraftingUiTheme.ARCANE));
            assertEquals(CraftingUiTheme.BRASS, CraftingUiTheme.current());
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
            assertEquals(0, (Integer) values.get("craftingUi.themeSchema"));
        }
    }

    @Test
    void glassSurfacesAndMaterialBlendsPreserveAlpha() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        for (CraftingUiTheme theme : List.of(CraftingUiTheme.FROSTED_GLASS, CraftingUiTheme.BLACK_GLASS)) {
            apply(theme);
            for (Color color : List.of(Color.PANEL, Color.BACKGROUND, Color.SURFACE, Color.ERROR_SURFACE, Color.GRID)) {
                assertTrue(color.value() >>> 24 > 0 && color.value() >>> 24 < 224, theme + ": " + color);
            }
            assertTrue(Color.BACKGROUND.value() >>> 24 <= 0x30, "大画布保持透光，内容卡片单独增加衬底");
            for (MaterialCellStyle state : MaterialCellStyle.values()) assertTrue(state.color() >>> 24 < 160);
            assertEquals(255, Color.TEXT.value() >>> 24);
        }
    }

    @Test
    void requestedBrassMagicAndSpaceKeepTheirDistinctColorFamilies() {
        int brass = CraftingUiTheme.BRASS.treeColor(Color.SURFACE);
        assertTrue((brass >>> 16 & 255) > (brass >>> 8 & 255) && (brass >>> 8 & 255) > (brass & 255));
        assertTrue((brass >>> 16 & 255) < 100, "黄铜使用深黄棕底，不回到浅灰白");
        for (Color color : List.of(Color.BACKGROUND, Color.PANEL, Color.SURFACE, Color.HOVER, Color.READY)) {
            int magic = CraftingUiTheme.ARCANE.treeColor(color);
            assertTrue((magic >>> 8 & 255) < Math.max(magic >>> 16 & 255, magic & 255), "魔法配色不用绿色：" + color);
        }
        int space = CraftingUiTheme.DEEP_SPACE.treeColor(Color.BACKGROUND);
        assertTrue((space & 255) > (space >>> 8 & 255) && (space >>> 8 & 255) > (space >>> 16 & 255));
        assertTrue((space & 255) < 40, "深空保持接近黑色的靛蓝背景");
    }

    private static void assertReadable(int text, int background, String message) {
        double first = luminance(text), second = luminance(background);
        double ratio = (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
        assertTrue(ratio >= 4.5, () -> message + ": 对比度 " + ratio);
    }

    private static double luminance(int color) {
        return channel(color >>> 16 & 255) * 0.2126 + channel(color >>> 8 & 255) * 0.7152
                + channel(color & 255) * 0.0722;
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
