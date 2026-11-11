package com.huanghuang.rsintegration.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 计划页和原生监控器共用配色；使用 GUI 批处理绘制，避免直接提交三角形丢失底板。 */
public final class CraftingUiStyle {
    public static final int PANEL = 0xFFD0CFD6;
    public static final int CANVAS = 0xFFA1A5BB;
    public static final int BORDER = 0xFF666B82;
    public static final int TEXT = 0xFF292832;
    public static final int MUTED = 0xFF464550;
    public static final int HINT = 0xFF83869B;
    public static final int ACCENT = 0xFF706E88;
    public static final int SUCCESS = 0xFF29795D;
    public static final int WARNING = 0xFF9A6725;
    public static final int DANGER = 0xFFA44D53;
    public static final int HIGHLIGHT = 0xFFF0F0F5;
    public static final int GRID_LINE = 0xFFCFD0DC;

    private CraftingUiStyle() {}

    public static void surface(GuiGraphics graphics, int x, int y, int width, int height,
                               int background, int border) {
        graphics.fill(x + 1, y, x + width - 1, y + height, border);
        graphics.fill(x, y + 1, x + width, y + height - 1, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 3, y + 4, x + width + 3, y + height + 4, 0x50000000);
        surface(graphics, x, y, width, height, PANEL, BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFFF1F1F5);
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, 0xFFE5E6ED);
    }

    /** 监控器使用紧凑的像素边框，避免深色粗线与大阴影争抢内容。 */
    public static void raised(GuiGraphics graphics, int x, int y, int width, int height,
                              int background, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, HIGHLIGHT);
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, HIGHLIGHT);
        graphics.fill(x + 2, y + height - 2, x + width - 1, y + height - 1, 0xFF9294A5);
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, 0xFF9294A5);
    }

    public static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, HIGHLIGHT);
        graphics.fill(x, y, x + width, y + height, CANVAS);
    }

    public static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    public static void planIcon(GuiGraphics graphics, int x, int y, int color) {
        surface(graphics, x, y, 14, 16, 0xFFF6FAFC, color);
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
            int fill = !active ? 0xFFB9BCCB : primary ? (isHoveredOrFocused() ? 0xFF777E9C : ACCENT)
                    : isHoveredOrFocused() ? 0xFFB8BDCF : 0xFFA6ABC0;
            raised(graphics, getX(), getY(), getWidth(), getHeight(), fill,
                    active && isHoveredOrFocused() ? ACCENT : BORDER);
            var font = Minecraft.getInstance().font;
            String label = fit(font, getMessage().getString(), getWidth() - 10);
            graphics.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2,
                    !active ? MUTED : 0xFFF7F7FA, false);
        }
    }
}
