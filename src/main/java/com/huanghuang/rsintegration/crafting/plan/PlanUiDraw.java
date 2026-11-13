package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 使用原版 GUI 批次绘制，避免即时三角扇受整合包的深度/剔除状态影响而消失。 */
public final class PlanUiDraw {
    private PlanUiDraw() {}

    public static void rounded(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        if (w <= 0 || h <= 0) return;
        int left = Math.round(x), top = Math.round(y), right = Math.round(x + w), bottom = Math.round(y + h);
        int r = Math.max(0, Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2)));
        if (r <= 1) { g.fill(left, top, right, bottom, color); return; }
        g.fill(left, top + r, right, bottom - r, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            g.fill(left + inset, top + row, right - inset, top + row + 1, color);
            g.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
        }
    }

    public static void roundedGradient(GuiGraphics g, float x, float y, float w, float h,
                                       float radius, int top, int bottom) {
        rounded(g, x, y, w, h, radius, top);
    }

    public static void textBackdrop(GuiGraphics g, Font font, int x, int y, String text, int color) {
        g.fill(x - 2, y - 1, x + font.width(text) + 2, y + font.lineHeight + 1, color);
    }
}
