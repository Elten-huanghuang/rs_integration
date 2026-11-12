package com.huanghuang.rsintegration.client.storage;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;

/** 共振盘独有的像素外框与轻微老电视信号效果。 */
final class ResonanceTooltipArt {
    static final int HEADER_SPACE = 14;
    static final int SIDE_SPACE = 14;
    static final int BOTTOM_SPACE = 6;
    static final int FRAME_OUTSET = 17;
    static final int HORIZONTAL_SPACE = (FRAME_OUTSET + SIDE_SPACE + 5) * 2;
    private static final int SIDE_WIDTH = 26;
    private static final int SIDE_HEIGHT = 35;
    private static final int TOP_WIDTH = 52;
    private static final int TOP_HEIGHT = 13;
    private static final float FRAME_SCALE = 1.25F;
    static final int TOP_OUTSET = 6;
    private static final int SIDE_TOP_OUTSET = 3;
    private static final int CENTER_RAISE = 3;
    private static final double CRT_SECONDS = 8;
    private static final ResourceLocation LEFT = texture("left");
    private static final ResourceLocation TOP = texture("top");
    private static final ResourceLocation RIGHT = texture("right");

    private ResonanceTooltipArt() {}

    private static ResourceLocation texture(String part) {
        return new ResourceLocation("rs_integration", "textures/gui/disk_tooltip/resonance_frame_" + part + ".png");
    }

    static void reserveFrameSpace(RenderTooltipEvent.Pre event) {
        DiskTooltipBackground.reserveFrameSpace(event, HORIZONTAL_SPACE / 2, HORIZONTAL_SPACE / 2,
                HEADER_SPACE + TOP_OUTSET + 5, BOTTOM_SPACE + 6);
    }

    static int glitchOffset(double time) {
        double cycle = time % CRT_SECONDS;
        double elapsed = cycle >= 5.5 ? cycle - 5.5 : cycle - 1.6;
        return elapsed >= 0 && elapsed < 0.16 ? (elapsed < 0.08 ? 1 : -1) : 0;
    }

    static double glitchFraction(double time) {
        return time % CRT_SECONDS >= 5.5 ? 0.70 : 0.32;
    }

    static void drawCrt(GuiGraphics graphics, int left, int top, int width, int height, double time) {
        if (width <= 2 || height <= 2) return;
        graphics.setColor(1, 1, 1, 1);
        double phase = time % CRT_SECONDS * Math.PI * 2 / CRT_SECONDS;
        int dim = (int) Math.round(6 - Math.sin(phase * 3) * 4 - Math.sin(phase * 11));
        if (glitchOffset(time) != 0) dim += 11;
        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, dim << 24);
        // 扫线以 GUI 像素绘制，低透明度避免高 GUI 缩放时变成过重的黑条。
        for (int y = top + 1; y < top + height - 1; y += 2) {
            graphics.fill(left + 1, y, left + width - 1, y + 1, 0x0C000000);
        }
        // 亮带在循环两端都从边缘淡出，回到顶部时不会突然跳亮。
        double progress = time % CRT_SECONDS / CRT_SECONDS;
        int center = (int) ((height + 16) * progress) - 8;
        for (int dy = -7; dy <= 7; dy++) {
            int y = top + center + dy;
            if (y <= top || y >= top + height - 1) continue;
            int alpha = (int) Math.round(4 * Math.exp(-dy * dy / 18.0));
            if (alpha > 0) graphics.fill(left + 1, y, left + width - 1, y + 1,
                    alpha << 24 | 0xB9F4FF);
        }
    }

    static void drawFrame(GuiGraphics graphics, int left, int top, int width, int height) {
        // 左右是长侧饰，随提示框延展到竖边的三分之二；极小的提示退回细边。
        if (!supportsFrame(width, height)) return;
        graphics.setColor(1, 1, 1, 1);
        int middle = left + (width - scaled(TOP_WIDTH)) / 2;
        // 横饰上移后为中空区域断开连接线，避免横线穿过纹饰。
        connector(graphics, left, middle, top);
        connector(graphics, middle + scaled(TOP_WIDTH), left + width, top);
        graphics.flush();
        // 提交 GUI 填充后重新启用混合，保证纹饰透明像素不会盖住星河。
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        sprite(graphics, LEFT, left - FRAME_OUTSET, top - SIDE_TOP_OUTSET,
                SIDE_WIDTH, SIDE_HEIGHT, scaled(SIDE_WIDTH), sideHeight(height));
        sprite(graphics, TOP, middle, top - SIDE_TOP_OUTSET - CENTER_RAISE,
                TOP_WIDTH, TOP_HEIGHT, scaled(TOP_WIDTH), scaled(TOP_HEIGHT));
        sprite(graphics, RIGHT, left + width - FRAME_OUTSET, top - SIDE_TOP_OUTSET,
                SIDE_WIDTH, SIDE_HEIGHT, scaled(SIDE_WIDTH), sideHeight(height));
    }

    static boolean supportsFrame(int width, int height) {
        return width >= scaled(SIDE_WIDTH) * 2 + scaled(TOP_WIDTH) && height >= scaled(SIDE_HEIGHT);
    }

    static int middleWidth() {
        return scaled(TOP_WIDTH);
    }

    static int sideHeight(int height) {
        return Math.round(height * 2.0F / 3.0F);
    }

    private static int scaled(int value) {
        return Math.round(value * FRAME_SCALE);
    }

    private static void connector(GuiGraphics graphics, int left, int right, int top) {
        graphics.fill(left, top - SIDE_TOP_OUTSET, right, top - 2, 0xFF4B498A);
        graphics.fill(left, top - 2, right, top, 0xFF8F91FF);
    }

    private static void sprite(GuiGraphics graphics, ResourceLocation texture,
                               int x, int y, int sourceWidth, int sourceHeight, int width, int height) {
        graphics.blit(texture, x, y, width, height, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
    }
}
