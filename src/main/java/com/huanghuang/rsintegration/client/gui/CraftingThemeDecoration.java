package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiTheme;
import net.minecraft.client.gui.GuiGraphics;

/** 符文刻线只沿边缘铺设，法阵置于内容下方，不覆盖文字与物品。 */
public final class CraftingThemeDecoration {
    private CraftingThemeDecoration() {}

    public static void frame(GuiGraphics g, int x, int y, int width, int height, CraftingUiTheme theme) {
        if (theme != CraftingUiTheme.ARCANE || width < 45 || height < 30) return;
        for (int side : new int[]{0, 1}) {
            int cx = side == 0 ? x + 4 : x + width - 5;
            int cy = side == 0 ? y + 4 : y + height - 5;
            int direction = side == 0 ? 1 : -1;
            for (int i = 0; i < 13; i++) {
                g.fill(cx + direction * i, cy, cx + direction * i + 1, cy + 1, 0xFFEAC778);
                g.fill(cx, cy + direction * i, cx + 1, cy + direction * i + 1, 0xFFEAC778);
            }
            diamond(g, cx + direction * 17, cy, 2, 0xFFEAC778);
        }
        for (int i = 30; i < width - 24; i += 32) diamond(g, x + i, y + height - 3, 1, 0xFF9B73C6);
    }

    public static void sigil(GuiGraphics g, int x, int y, int width, int height, CraftingUiTheme theme) {
        if (theme == CraftingUiTheme.DEEP_SPACE) {
            stars(g, x, y, width, height);
            return;
        }
        if (theme != CraftingUiTheme.ARCANE || width < 60 || height < 60) return;
        int cx = x + width / 2, cy = y + height / 2, radius = Math.min(90, Math.min(width, height) / 3);
        ring(g, cx, cy, radius, 0xFF2D1B2D);
        ring(g, cx, cy, radius - 5, 0xFF362136);
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3 - Math.PI / 2;
            double next = angle + Math.PI * 2 / 3;
            line(g, cx + (int) (Math.cos(angle) * (radius - 12)), cy + (int) (Math.sin(angle) * (radius - 12)),
                    cx + (int) (Math.cos(next) * (radius - 12)), cy + (int) (Math.sin(next) * (radius - 12)), 0xFF45273C);
            diamond(g, cx + (int) (Math.cos(angle) * radius), cy + (int) (Math.sin(angle) * radius), 3, 0xFF886776);
        }
        diamond(g, cx, cy, Math.max(4, radius / 7), 0xFF886776);
    }

    private static void stars(GuiGraphics g, int x, int y, int width, int height) {
        if (width < 20 || height < 20) return;
        int seed = 0x53AB91;
        int count = Math.min(110, width * height / 1400);
        for (int i = 0; i < count; i++) {
            seed = seed * 1664525 + 1013904223;
            int px = x + 5 + Math.floorMod(seed, width - 10);
            seed = seed * 1664525 + 1013904223;
            int py = y + 5 + Math.floorMod(seed, height - 10);
            int color = i % 5 == 0 ? 0xFF796C9C : 0xFF506B92;
            g.fill(px, py, px + 1, py + 1, color);
            if (i % 23 == 0) {
                g.fill(px - 1, py, px + 2, py + 1, 0xFF6683AF);
                g.fill(px, py - 1, px + 1, py + 2, 0xFF6683AF);
            }
        }
    }

    private static void ring(GuiGraphics g, int x, int y, int radius, int color) {
        for (int i = 0; i < 180; i++) {
            double a = i * Math.PI / 90;
            int px = x + (int) Math.round(Math.cos(a) * radius), py = y + (int) Math.round(Math.sin(a) * radius);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static void diamond(GuiGraphics g, int x, int y, int radius, int color) {
        line(g, x, y - radius, x + radius, y, color); line(g, x + radius, y, x, y + radius, color);
        line(g, x, y + radius, x - radius, y, color); line(g, x - radius, y, x, y - radius, color);
    }

    private static void line(GuiGraphics g, int x, int y, int endX, int endY, int color) {
        int steps = Math.max(Math.abs(endX - x), Math.abs(endY - y));
        for (int i = 0; i <= steps; i++) {
            int px = x + (endX - x) * i / Math.max(1, steps), py = y + (endY - y) * i / Math.max(1, steps);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }
}
