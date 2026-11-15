package com.huanghuang.rsintegration.client.gui;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.sounds.SoundManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CraftingThemeButtonTest extends BootstrapTest {
    @AfterEach
    void unload() { RSIntegrationConfig.CLIENT_SPEC.setConfig(null); }

    @Test
    void iconOpensWheelAndSelectionSavesOnlyItsOwnScope() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        Minecraft client = mock(Minecraft.class);
        when(client.getSoundManager()).thenReturn(mock(SoundManager.class));
        try (var game = mockStatic(Minecraft.class);
             var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            game.when(Minecraft::getInstance).thenReturn(client);
            config.when(RSIntegrationConfig::saveClientConfig).thenAnswer(ignored -> null);
            CraftingThemeButton button = new CraftingThemeButton(10, 20, true);
            assertFalse(button.mouseClicked(9, 20, 0));
            assertFalse(button.mouseClicked(15, 25, 1));
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
            assertTrue(button.mouseClicked(15, 25, 0));
            assertTrue(button.isWheelOpen());
            config.verify(RSIntegrationConfig::saveClientConfig, never());
            button.wheelKeyPressed(GLFW.GLFW_KEY_RIGHT);
            button.wheelKeyPressed(GLFW.GLFW_KEY_ENTER);
            assertEquals(CraftingUiTheme.FOREST, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
            assertFalse(button.isWheelOpen());
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
            CraftingThemeButton monitor = new CraftingThemeButton(10, 20, false);
            monitor.setWheelViewport(320, 180);
            monitor.onPress();
            var layout = CraftingThemeWheel.Layout.forViewport(320, 180);
            int index = CraftingUiTheme.DEEP_SPACE.ordinal();
            assertTrue(monitor.mouseClicked(layout.nextPage().x() + 3, layout.nextPage().y() + 3, 0));
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
            assertTrue(monitor.mouseClicked(layout.pageIndicator(0).x() + 3, layout.pageIndicator(0).y() + 3, 0));
            assertTrue(monitor.mouseClicked(layout.pageIndicator(1).x() + 3, layout.pageIndicator(1).y() + 3, 0));
            assertTrue(monitor.isWheelOpen());
            config.verify(RSIntegrationConfig::saveClientConfig, times(1));
            assertTrue(monitor.mouseClicked(layout.pointX(index), layout.pointY(index), 0));
            assertEquals(CraftingUiTheme.DEEP_SPACE, CraftingUiTheme.current());
            assertEquals(CraftingUiTheme.FOREST, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
            config.verify(RSIntegrationConfig::saveClientConfig, times(2));
        }
    }

    @Test
    void outsideClickEscapeAndUnrelatedKeysDoNotApplyChanges() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        try (var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            CraftingThemeButton button = new CraftingThemeButton(10, 20, true);
            button.onPress();
            assertTrue(button.wheelScrolled(1));
            assertTrue(button.wheelKeyPressed(GLFW.GLFW_KEY_E));
            assertTrue(button.wheelKeyPressed(GLFW.GLFW_KEY_ESCAPE));
            assertFalse(button.isWheelOpen());
            assertFalse(button.wheelKeyPressed(GLFW.GLFW_KEY_ESCAPE));
            button.onPress();
            assertTrue(button.mouseClicked(0, 0, 0));
            assertFalse(button.isWheelOpen());
            button.onPress();
            assertTrue(button.mouseClicked(160, 90, 1));
            assertFalse(button.isWheelOpen());
            config.verify(RSIntegrationConfig::saveClientConfig, never());
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current(CraftingUiTheme.Scope.TREE));
        }
    }

    @Test
    void failedSaveKeepsWheelOpenAndRestoresTheme() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        try (var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            config.when(RSIntegrationConfig::saveClientConfig).thenThrow(new IllegalStateException("模拟保存失败"));
            CraftingThemeButton button = new CraftingThemeButton(10, 20, false);
            button.onPress();
            button.wheelKeyPressed(GLFW.GLFW_KEY_RIGHT);
            button.wheelKeyPressed(GLFW.GLFW_KEY_ENTER);
            assertTrue(button.isWheelOpen());
            assertEquals(CraftingUiTheme.CUSTOM, CraftingUiTheme.current());
        }
    }

    @Test
    void wheelFitsSmallWindowsAndEverySectorCanBeSelected() {
        for (int[] size : new int[][]{{320, 180}, {427, 240}, {640, 336}, {960, 540}}) {
            var layout = CraftingThemeWheel.Layout.forViewport(size[0], size[1]);
            assertEquals(-1, layout.themeAt(layout.x(), layout.y(), 0));
            for (CraftingUiTheme theme : CraftingUiTheme.values()) {
                var card = layout.card(theme.ordinal());
                assertTrue(card.x() >= 0 && card.y() >= 0);
                assertTrue(card.x() + card.width() <= size[0] && card.y() + card.height() <= size[1]);
                assertEquals(theme.ordinal(), layout.themeAt(layout.pointX(theme.ordinal()), layout.pointY(theme.ordinal()),
                        theme.ordinal() / CraftingThemeWheel.PAGE_SIZE));
                for (int other = theme.ordinal() + 1; other < Math.min(CraftingUiTheme.values().length,
                        (theme.ordinal() / CraftingThemeWheel.PAGE_SIZE + 1) * CraftingThemeWheel.PAGE_SIZE); other++) {
                    var next = layout.card(other);
                    assertTrue(card.x() + card.width() <= next.x() || next.x() + next.width() <= card.x()
                            || card.y() + card.height() <= next.y() || next.y() + next.height() <= card.y(), "主题卡不能重叠");
                }
            }
        }
    }

    @Test
    void paletteUsesPixelsInsideItsBoundsAndDisablesBeforeConfigLoads() {
        GuiGraphics graphics = mock(GuiGraphics.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(graphics).drawManaged(any());
        doAnswer(call -> {
            assertTrue(call.<Integer>getArgument(0) >= 10);
            assertTrue(call.<Integer>getArgument(1) >= 20);
            assertTrue(call.<Integer>getArgument(2) <= 10 + CraftingThemeButton.SIZE);
            assertTrue(call.<Integer>getArgument(3) <= 20 + CraftingThemeButton.SIZE);
            return null;
        }).when(graphics).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        CraftingThemeButton button = new CraftingThemeButton(10, 20, false);
        assertFalse(button.active);
        assertFalse(button.mouseClicked(15, 25, 0));
        button.renderWidget(graphics, -1, -1, 0);
        verify(graphics, atLeast(10)).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        button.renderWidget(graphics, -1, -1, 0);
        assertTrue(button.active);
    }
}
