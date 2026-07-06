package com.huanghuang.rsintegration.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Rounded-rect and gradient drawing primitives for polished UI.
 * Inspired by Modern JEI / BetterJEI.
 */
public final class UIRenderer {

    private static final int ARC_SEGMENTS = 28;

    private UIRenderer() {}

    // ── Math / easing ────────────────────────────────────────────

    public static float clamp(float t) { return Math.max(0f, Math.min(1f, t)); }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static float easeOutCubic(float t) {
        t = clamp(t);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    // ── Color utilities ──────────────────────────────────────────

    public static int alpha(int color, float a) {
        a = clamp(a);
        int baseAlpha = (color >> 24) & 0xFF;
        int newAlpha = Math.round(baseAlpha * a);
        return (color & 0x00FFFFFF) | (newAlpha << 24);
    }

    public static int mix(int a, int b, float t) {
        t = clamp(t);
        return (((int) lerp((a >> 24) & 0xFF, (b >> 24) & 0xFF, t)) << 24)
             | (((int) lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t)) << 16)
             | (((int) lerp((a >> 8)  & 0xFF, (b >> 8)  & 0xFF, t)) << 8)
             |  ((int) lerp( a        & 0xFF,  b        & 0xFF, t));
    }

    // ── Rounded rectangles ───────────────────────────────────────

    public static void rounded(GuiGraphics gfx, float x, float y, float w, float h,
                               float radius, int color) {
        if (w <= 0 || h <= 0) return;
        radius = clampRadius(radius, w, h);
        Matrix4f mat = gfx.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        vertex(buf, mat, x + w / 2f, y + h / 2f, color);
        arc(buf, mat, x + w - radius, y + radius,       radius, -90f,   0f, color);
        arc(buf, mat, x + w - radius, y + h - radius,   radius,   0f,  90f, color);
        arc(buf, mat, x + radius,     y + h - radius,   radius,  90f, 180f, color);
        arc(buf, mat, x + radius,     y + radius,       radius, 180f, 270f, color);

        tess.end();
    }

    public static void roundedGradient(GuiGraphics gfx, float x, float y, float w, float h,
                                       float radius, int colorTop, int colorBottom) {
        if (w <= 0 || h <= 0) return;
        radius = clampRadius(radius, w, h);
        Matrix4f mat = gfx.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        float cx = x + w / 2f;
        float cy = y + h / 2f;
        int midColor = mix(colorTop, colorBottom, 0.5f);
        gradientVertex(buf, mat, cx, cy, y, y + h, midColor, midColor);

        gradientArc(buf, mat, x + w - radius, y + radius,       radius, -90f,   0f, y, y + h, colorTop, colorBottom);
        gradientArc(buf, mat, x + w - radius, y + h - radius,   radius,   0f,  90f, y, y + h, colorTop, colorBottom);
        gradientArc(buf, mat, x + radius,     y + h - radius,   radius,  90f, 180f, y, y + h, colorTop, colorBottom);
        gradientArc(buf, mat, x + radius,     y + radius,       radius, 180f, 270f, y, y + h, colorTop, colorBottom);

        tess.end();
    }

    // ── Card panel with left accent bar ──────────────────────────

    /**
     * Draws a card background with: rounded rect gradient body, a 3px left
     * accent bar (@code accentColor), a 1px top inner highlight, and a 2px
     * bottom shadow line.
     */
    public static void card(GuiGraphics gfx, int x, int y, int w, int h,
                            float radius, int accentColor) {
        // Main body — subtle gradient
        roundedGradient(gfx, x, y, w, h, radius, 0xE6141E18, 0xE6101814);

        // Top inner highlight — 1px at y+1
        rounded(gfx, x + 2, y + 1, w - 4, 1f, radius / 2f, 0x18FFFFFF);

        // Bottom shadow — 2px at y+h-2
        gfx.fill(x + (int) radius, y + h - 2, x + w - (int) radius, y + h, 0x22000000);

        // Left accent bar — 3px wide, inset by 2px from top/bottom
        int barH = h - 4;
        int barAlpha = (accentColor >> 24) & 0xFF;
        int barTop = accentColor & 0x00FFFFFF | (Math.min(barAlpha + 40, 255) << 24);
        gfx.fill(x + 1, y + 2, x + 4, y + 2 + barH, accentColor);
        // accent bar top highlight
        gfx.fill(x + 1, y + 2, x + 4, y + 3, barTop);
    }

    // ── Slot background — dark inset look ────────────────────────

    /** Draws a dark inset item-slot background with a subtle border. */
    public static void slotBg(GuiGraphics gfx, int x, int y, int size, int borderColor) {
        // Outer border
        gfx.fill(x - 1, y - 1, x + size + 1, y + size + 1, borderColor);
        // Inner dark fill
        gfx.fill(x, y, x + size, y + size, 0xFF252525);
        // Subtle top-left inner shadow
        gfx.fill(x, y, x + size, y + 1, 0x11000000);
        gfx.fill(x, y, x + 1, y + size, 0x11000000);
        // Subtle bottom-right inner highlight
        gfx.fill(x, y + size - 1, x + size, y + size, 0x11FFFFFF);
        gfx.fill(x + size - 1, y, x + size, y + size, 0x11FFFFFF);
    }

    // ── Chevron arrow ────────────────────────────────────────────

    /** Draws a ">>" chevron arrow centered at (cx, cy). Total width ~10px, height ~10px. */
    public static void chevron(GuiGraphics gfx, int cx, int cy, int color) {
        // Right-pointing chevron: two diagonal lines
        // Left head:  \  (top-left to bottom-right)
        // Right head:  \  (same, offset 3px right)
        for (int i = 0; i < 5; i++) {
            gfx.fill(cx - 2 + i, cy - 3 + i, cx - 1 + i, cy - 2 + i, color);
        }
        for (int i = 0; i < 5; i++) {
            gfx.fill(cx + 1 + i, cy - 3 + i, cx + 2 + i, cy - 2 + i, color);
        }
        // Lower halves
        for (int i = 0; i < 5; i++) {
            gfx.fill(cx - 2 + i, cy + 2 - i, cx - 1 + i, cy + 3 - i, color);
        }
        for (int i = 0; i < 5; i++) {
            gfx.fill(cx + 1 + i, cy + 2 - i, cx + 2 + i, cy + 3 - i, color);
        }
    }

    // ── Pill badge ───────────────────────────────────────────────

    /** Draws a fully-rounded pill badge with text centered inside. */
    public static void pillBadge(GuiGraphics gfx, Font font, int x, int y, int w, int h,
                                 int bg, int fg, String text) {
        rounded(gfx, x, y, w, h, h / 2f, bg);
        int tw = font.width(text);
        gfx.drawString(font, text, x + (w - tw) / 2, y + (h - font.lineHeight) / 2, fg);
    }

    // ── Text backdrop ───────────────────────────────────────────

    /** Draws a frosted dark backdrop behind text so it's readable against
     *  any background (netherrack, bright blocks, snow, rain).
     *  Padding: 4px horizontal, 2px vertical. */
    public static void textBackdrop(GuiGraphics gfx, Font font, int x, int y, String text, int bgColor) {
        int tw = font.width(text);
        int th = font.lineHeight;
        rounded(gfx, x - 4, y - 1, tw + 8, th + 2, 4f, bgColor);
    }

    // ── Text wrapping ──────────────────────────────────────────────

    /** Split {@code text} into lines that each fit within {@code maxWidth},
     *  breaking at spaces when possible. */
    public static java.util.List<String> wrapLines(Font font, String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, maxWidth);
            if (line.length() < remaining.length()) {
                int breakAt = line.lastIndexOf(' ');
                if (breakAt > 0) line = line.substring(0, breakAt);
            }
            lines.add(line);
            remaining = remaining.substring(line.length()).trim();
        }
        return lines;
    }

    /** Gradient vertical line — alpha fades linearly from topColor to bottomColor.
     *  Uses multiple fill strips for simplicity; fine for the ~18px connector gaps. */
    public static void vLineGradient(GuiGraphics gfx, float x, float y1, float y2,
                                     float width, int colorTop, int colorBottom) {
        float h = y2 - y1;
        if (h <= 0) return;
        int steps = Math.max(1, Math.min(12, (int) h / 2));
        for (int i = 0; i < steps; i++) {
            float t = (float) i / steps;
            int c = mix(colorTop, colorBottom, t);
            float segY = y1 + t * h;
            float segH = h / steps + 1; // slight overlap to avoid gaps
            gfx.fill((int) x, (int) segY, (int) (x + width), (int) (segY + segH), c);
        }
    }

    // ── Higher-level widgets ─────────────────────────────────────

    /** Rounded card panel with subtle gradient and inner highlight. */
    public static void panel(GuiGraphics gfx, int x, int y, int w, int h, float fade) {
        rounded(gfx, x + 8, y + 12, w, h, 20f, alpha(0x72000000, fade));
        roundedGradient(gfx, x, y, w, h, 20f,
                alpha(0xCE2B2B2B, fade), alpha(0xC7383838, fade));
        rounded(gfx, x + 1, y + 1, w - 2, 42f, 19f, alpha(0x181F0A15, fade));
    }

    /** Pill badge, e.g. for mod-type tags. */
    public static void pill(GuiGraphics gfx, int x, int y, int w, int h,
                            float fade, boolean active) {
        int bg = active ? alpha(0xCC5533DD, fade) : alpha(0xCC333333, fade);
        rounded(gfx, x, y, w, h, h / 2f, bg);
    }

    /** Step-number badge (circular). */
    public static void stepBadge(GuiGraphics gfx, int x, int y, int size,
                                 float fade, boolean active) {
        int bg = active ? alpha(0xFF6644EE, fade) : alpha(0xFF444444, fade);
        rounded(gfx, x, y, size, size, size / 2f, bg);
    }

    // ── Internal vertex helpers ──────────────────────────────────

    private static void vertex(BufferBuilder buf, Matrix4f mat, float x, float y, int color) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        buf.vertex(mat, x, y, 0f).color(r, g, b, a).endVertex();
    }

    private static void gradientVertex(BufferBuilder buf, Matrix4f mat, float x, float y,
                                       float topY, float bottomY, int colorTop, int colorBottom) {
        float t = (y - topY) / (bottomY - topY);
        vertex(buf, mat, x, y, mix(colorTop, colorBottom, t));
    }

    private static void arc(BufferBuilder buf, Matrix4f mat, float cx, float cy,
                            float radius, float startAngle, float endAngle, int color) {
        float step = (endAngle - startAngle) / ARC_SEGMENTS;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double rad = Math.toRadians(startAngle + step * i);
            float x = cx + (float) Math.cos(rad) * radius;
            float y = cy + (float) Math.sin(rad) * radius;
            vertex(buf, mat, x, y, color);
        }
    }

    private static void gradientArc(BufferBuilder buf, Matrix4f mat, float cx, float cy,
                                    float radius, float startAngle, float endAngle,
                                    float topY, float bottomY, int colorTop, int colorBottom) {
        float step = (endAngle - startAngle) / ARC_SEGMENTS;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double rad = Math.toRadians(startAngle + step * i);
            float x = cx + (float) Math.cos(rad) * radius;
            float y = cy + (float) Math.sin(rad) * radius;
            float t = clamp((y - topY) / (bottomY - topY));
            vertex(buf, mat, x, y, mix(colorTop, colorBottom, t));
        }
    }

    private static float clampRadius(float radius, float w, float h) {
        return Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
    }
}
