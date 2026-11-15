package com.huanghuang.rsintegration.client.gui;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.GuiGraphics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** 玻璃边缘只合成一次，防止叠加白色高光形成亮圈。 */
class CraftingGlassSurfaceTest extends BootstrapTest {
    @AfterEach
    void unload() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
    }

    @Test
    void glassPanelsAndButtonsDoNotStackEdgeHighlights() {
        for (CraftingUiTheme theme : List.of(CraftingUiTheme.FROSTED_GLASS, CraftingUiTheme.BLACK_GLASS)) {
            CommentedConfig config = CommentedConfig.inMemory();
            config.set("craftingUi.theme", theme.name());
            config.set("craftingUi.treeTheme", theme.name());
            config.set("craftingUi.themeSchema", 1);
            RSIntegrationConfig.CLIENT_SPEC.setConfig(config);
            assertTrue(CraftingUiStyle.border() >>> 24 <= 0x48, "普通边框保持低不透明度");
            assertEquals(CraftingUiStyle.border(), Color.NODE_BORDER.value());
            assertEquals(CraftingUiStyle.border(), CraftingUiStyle.highlight());
            for (boolean button : new boolean[]{false, true}) {
                int[][] coverage = new int[20][30];
                GuiGraphics graphics = mock(GuiGraphics.class);
                doAnswer(call -> {
                    int left = call.getArgument(0), top = call.getArgument(1);
                    int right = call.getArgument(2), bottom = call.getArgument(3);
                    for (int y = top; y < bottom; y++) {
                        for (int x = left; x < right; x++) {
                            assertEquals(0, coverage[y][x]++, "玻璃边缘与底色不应重复叠加");
                        }
                    }
                    return null;
                }).when(graphics).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
                if (button) CraftingUiStyle.raised(graphics, 0, 0, 30, 20, CraftingUiStyle.panel(), CraftingUiStyle.border());
                else CraftingUiStyle.panel(graphics, 0, 0, 30, 20);
                assertEquals(1, coverage[10][15], "内容底板仍然存在");
                assertEquals(1, coverage[0][15], "保留一层细边框");
                verify(graphics, never()).renderOutline(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }
            GuiGraphics decoration = mock(GuiGraphics.class);
            CraftingThemeDecoration.frame(decoration, 0, 0, 100, 80, theme);
            verifyNoInteractions(decoration);
        }
    }
}
