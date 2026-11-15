package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.huanghuang.rsintegration.config.CraftingUiTheme;

/** 使用原版 GUI 批次绘制，避免即时三角扇受整合包的深度/剔除状态影响而消失。 */
public final class PlanUiDraw {
    private PlanUiDraw() {}

    public static void rounded(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        if (w <= 0 || h <= 0) return;
        if (CraftingUiTheme.current(CraftingUiTheme.Scope.TREE) == CraftingUiTheme.VANILLA) radius = 0;
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

    /** 边框只占外圈，不在半透明面板下面再铺一层实色。 */
    public static void framed(GuiGraphics g, float x, float y, float w, float h,
                              float radius, int fill, int border) {
        if (!CraftingUiTheme.current(CraftingUiTheme.Scope.TREE).isGlass()) {
            rounded(g, x, y, w, h, radius, border);
            rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
            return;
        }
        int left = Math.round(x), top = Math.round(y), right = Math.round(x + w), bottom = Math.round(y + h);
        int r = Math.max(1, Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2)));
        for (int row = 0; row < bottom - top; row++) {
            int edge = Math.min(row, bottom - top - row - 1);
            double dy = r - Math.min(edge, r) - 0.5;
            int inset = edge < r ? (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy))) : 0;
            int l = left + inset, rr = right - inset;
            if (row == 0 || row == bottom - top - 1) g.fill(l, top + row, rr, top + row + 1, border);
            else {
                g.fill(l, top + row, l + 1, top + row + 1, border);
                g.fill(rr - 1, top + row, rr, top + row + 1, border);
                g.fill(l + 1, top + row, rr - 1, top + row + 1, fill);
            }
        }
    }

    public static void textBackdrop(GuiGraphics g, Font font, int x, int y, String text, int color) {
        g.fill(x - 2, y - 1, x + font.width(text) + 2, y + font.lineHeight + 1, color);
    }
}
