package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 计划页和原生监控器共用配色；使用 GUI 批处理绘制，避免直接提交三角形丢失底板。 */
public final class CraftingUiStyle {
    public static int panel() { return CraftingUiColors.PANEL.argb(); }
    public static int canvas() { return CraftingUiColors.CANVAS.argb(); }
    public static int border() { return CraftingUiColors.BORDER.argb(); }
    public static int text() { return CraftingUiColors.TEXT.argb(); }
    public static int muted() { return CraftingUiColors.MUTED.argb(); }
    public static int hint() { return CraftingUiColors.HINT.argb(); }
    public static int accent() { return CraftingUiColors.ACCENT.argb(); }
    public static int success() { return CraftingUiColors.SUCCESS.argb(); }
    public static int warning() { return CraftingUiColors.WARNING.argb(); }
    public static int danger() { return CraftingUiColors.DANGER.argb(); }
    public static int highlight() { return CraftingUiColors.HIGHLIGHT.argb(); }
    public static int gridLine() { return CraftingUiColors.GRID_LINE.argb(); }

    private CraftingUiStyle() {}

    public static void surface(GuiGraphics graphics, int x, int y, int width, int height,
                               int background, int border) {
        graphics.fill(x + 1, y, x + width - 1, y + height, border);
        graphics.fill(x, y + 1, x + width, y + height - 1, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 3, y + 4, x + width + 3, y + height + 4, CraftingUiColors.PANEL_SHADOW.argb());
        surface(graphics, x, y, width, height, panel(), border());
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, CraftingUiColors.PANEL_TOP_EDGE.argb());
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, CraftingUiColors.PANEL_LEFT_EDGE.argb());
    }

    /** 监控器使用紧凑的像素边框，避免深色粗线与大阴影争抢内容。 */
    public static void raised(GuiGraphics graphics, int x, int y, int width, int height,
                              int background, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, highlight());
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, highlight());
        graphics.fill(x + 2, y + height - 2, x + width - 1, y + height - 1, CraftingUiColors.EDGE_SHADOW.argb());
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, CraftingUiColors.EDGE_SHADOW.argb());
    }

    public static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, highlight());
        graphics.fill(x, y, x + width, y + height, canvas());
    }

    public static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    public static void planIcon(GuiGraphics graphics, int x, int y, int color) {
        surface(graphics, x, y, 14, 16, CraftingUiColors.ICON_PAPER.argb(), color);
        for (int row = 0; row < 3; row++) {
            graphics.fill(x + 3, y + 4 + row * 4, x + 5, y + 6 + row * 4, color);
            graphics.fill(x + 7, y + 4 + row * 4, x + 11, y + 5 + row * 4, color);
        }
    }

    public static final class ActionButton extends Button {
        private final boolean primary;

        public ActionButton(int x, int y, int width, int height, Component label, boolean primary, OnPress press) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.primary = primary;
        }

        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int fill = !active ? CraftingUiColors.BUTTON_DISABLED.argb()
                    : primary ? (isHoveredOrFocused() ? CraftingUiColors.BUTTON_PRIMARY_HOVER.argb() : accent())
                    : isHoveredOrFocused() ? CraftingUiColors.BUTTON_HOVER.argb() : CraftingUiColors.BUTTON.argb();
            raised(graphics, getX(), getY(), getWidth(), getHeight(), fill,
                    active && isHoveredOrFocused() ? accent() : border());
            var font = Minecraft.getInstance().font;
            String label = fit(font, getMessage().getString(), getWidth() - 10);
            graphics.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2,
                    !active ? muted() : CraftingUiColors.BUTTON_TEXT.argb(), false);
        }
    }
}
