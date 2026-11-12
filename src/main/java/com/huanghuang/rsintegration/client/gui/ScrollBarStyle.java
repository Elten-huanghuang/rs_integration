package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiColors;
import net.minecraft.client.gui.GuiGraphics;

/** 三栏共用的像素滚动条；短滑块的高度与 RS 原生滚动条一致。 */
public final class ScrollBarStyle {
    public static final int WIDTH = 12;
    public static final int COMPACT_WIDTH = 7;
    public static final int THUMB_HEIGHT = 15;
    public static final int CONTENT_GAP = 3;

    private ScrollBarStyle() {}

    public static int thumbHeight(int height) { return Math.min(height, THUMB_HEIGHT); }

    public static int thumbTop(int height, int offset, int maximum) {
        return maximum <= 0 ? 0 : (int) ((long) (height - thumbHeight(height))
                * Math.max(0, Math.min(offset, maximum)) / maximum);
    }

    public static void render(GuiGraphics graphics, int x, int y, int width, int height,
                              int offset, int maximum, boolean hovered) {
        // 无溢出时仍保留顶部灰色滑块，不能只留下像分隔线一样的空轨道。
        graphics.fill(x, y, x + width, y + height, CraftingUiStyle.highlight());
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, CraftingUiStyle.canvas());
        boolean enabled = maximum > 0;
        int top = y + thumbTop(height, offset, maximum);
        int bottom = top + thumbHeight(height);
        int face = !enabled ? CraftingUiColors.SCROLLBAR_DISABLED.argb()
                : hovered ? CraftingUiColors.SCROLLBAR_HOVER.argb() : CraftingUiColors.SCROLLBAR.argb();
        CraftingUiStyle.raised(graphics, x, top, width, bottom - top, face,
                enabled ? CraftingUiStyle.border() : CraftingUiColors.SCROLLBAR_DISABLED_BORDER.argb());
        int grip = enabled ? CraftingUiColors.SCROLLBAR_GRIP.argb() : CraftingUiColors.SCROLLBAR_DISABLED_GRIP.argb();
        int middle = top + (bottom - top) / 2;
        graphics.fill(x + 3, middle - 2, x + width - 3, middle - 1, grip);
        graphics.fill(x + 3, middle + 1, x + width - 3, middle + 2, grip);
    }
}
