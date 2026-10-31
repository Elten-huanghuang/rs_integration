package com.huanghuang.rsintegration.client.storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 液态经验使用低分辨率循环帧，田园厨房使用小幅木纹与独立纹饰。 */
final class ItemTooltipArt {
    private static final int BASE_WIDTH = 384;
    private static final int BASE_HEIGHT = 192;
    private static final int ATLAS_WIDTH = 256;
    private static final int ATLAS_HEIGHT = 128;
    private static final int LIQUID_WIDTH = 64;
    private static final int LIQUID_HEIGHT = 32;
    private static final int LIQUID_COLUMNS = 4;
    private static final int LIQUID_FRAMES = 32;
    private static final double TAU = Math.PI * 2.0;
    private static final ResourceLocation EXPERIENCE = motifs("experience");
    private static final ResourceLocation FOOD = motifs("food");

    private ItemTooltipArt() {}

    private static ResourceLocation motifs(String name) {
        return new ResourceLocation("rs_integration",
                "textures/gui/disk_tooltip/" + name + "_motifs.png");
    }

    static void draw(GuiGraphics graphics, int left, int top, int width, int height,
                     DiskTooltipBackground.Theme theme, double phase) {
        if (theme.artwork() == DiskTooltipBackground.Artwork.EXPERIENCE) {
            drawLiquid(graphics, left, top, width, height, theme.background(), phase);
            if (width >= 16 && height >= 16) {
                drawExperience(graphics, left, top, width, height, phase);
            }
            return;
        }
        double scale = Math.max(width / (double) BASE_WIDTH, height / (double) BASE_HEIGHT);
        int sourceWidth = Math.min(BASE_WIDTH, Math.max(1, (int) Math.ceil(width / scale)));
        int sourceHeight = Math.min(BASE_HEIGHT, Math.max(1, (int) Math.ceil(height / scale)));
        // 实心木纹先遮住背包，说明文字使用单独的奶油色和浅草绿色。
        graphics.blit(theme.background(), left, top, width, height,
                (BASE_WIDTH - sourceWidth) / 2.0F, (BASE_HEIGHT - sourceHeight) / 2.0F,
                sourceWidth, sourceHeight, BASE_WIDTH, BASE_HEIGHT);
        if (width < 16 || height < 16) return;

        drawFood(graphics, left, top, width, height, phase);
    }

    private static void drawLiquid(GuiGraphics graphics, int left, int top,
                                   int width, int height, ResourceLocation texture, double phase) {
        double position = phase / TAU * 8 * LIQUID_FRAMES;
        int frame = (int) position;
        float mix = (float) (position - frame);
        double scale = Math.max(width / (double) LIQUID_WIDTH, height / (double) LIQUID_HEIGHT);
        int sampleWidth = Math.min(LIQUID_WIDTH, Math.max(1, (int) Math.ceil(width / scale)));
        int sampleHeight = Math.min(LIQUID_HEIGHT, Math.max(1, (int) Math.ceil(height / scale)));
        for (int layer = 0; layer < 2; layer++) {
            int index = (frame + layer) % LIQUID_FRAMES;
            graphics.setColor(1, 1, 1, layer == 0 ? 1 : mix);
            graphics.blit(texture, left, top, width, height,
                    index % LIQUID_COLUMNS * LIQUID_WIDTH + (LIQUID_WIDTH - sampleWidth) / 2.0F,
                    index / LIQUID_COLUMNS * LIQUID_HEIGHT + (LIQUID_HEIGHT - sampleHeight) / 2.0F,
                    sampleWidth, sampleHeight, LIQUID_WIDTH * LIQUID_COLUMNS,
                    LIQUID_HEIGHT * (LIQUID_FRAMES / LIQUID_COLUMNS));
        }
    }

    private static void drawExperience(GuiGraphics graphics, int left, int top,
                                       int width, int height, double phase) {
        int ribbonHeight = Math.max(4, Math.min(34, height / 3));
        sprite(graphics, EXPERIENCE, left + 4, top + height - ribbonHeight - 3,
                width - 8, ribbonHeight, 128, 64, 128, 64,
                (float) (0.36 + Math.sin(phase * 4) * 0.08));

        for (int i = 0; i < 6; i++) {
            double progress = (phase / TAU + i / 6.0) % 1.0;
            int size = Math.min(12, Math.max(4, height / 10));
            int x = left + 3 + (int) ((width - size - 6)
                    * (0.10 + i * 0.14 + Math.sin(phase + i) * 0.025));
            int y = top + 3 + (int) ((height - size - 6) * (1.0 - progress));
            sprite(graphics, EXPERIENCE, x, y, size, size, 128, 0, 64, 64,
                    (float) (Math.pow(Math.sin(progress * Math.PI), 2) * 0.60));
        }
    }

    private static void drawFood(GuiGraphics graphics, int left, int top,
                                 int width, int height, double phase) {
        int dishSize = Math.min(100, Math.min(height - 8, width / 2));
        int dishX = left + width - dishSize - 5;
        int dishY = top + height - dishSize - 3;
        int clothWidth = Math.min(68, width - 8);
        int clothHeight = Math.max(4, Math.min(28, height / 3));
        sprite(graphics, FOOD, left + width - clothWidth - 4,
                top + height - clothHeight - 3, clothWidth, clothHeight,
                128, 64, 128, 64, 0.42F);
        sprite(graphics, FOOD, dishX, dishY, dishSize, dishSize,
                0, 0, 128, 128, 0.75F);

        int sprigSize = Math.max(6, Math.min(38, height - 8));
        sprite(graphics, FOOD, left + 3, top + height - sprigSize - 3,
                sprigSize, sprigSize, 192, 0, 64, 64, 0.34F);
        // 三缕蒸汽错开上升并在两端淡出，循环接缝不会突然跳变。
        int steamSize = Math.max(5, Math.min(36, height / 2));
        for (int i = 0; i < 3; i++) {
            double progress = (phase / TAU * 3 + i / 3.0) % 1.0;
            int x = dishX + (dishSize - steamSize) / 2
                    + (int) (Math.sin(progress * Math.PI * 2 + i) * dishSize * 0.08);
            int y = top + 3 + (int) ((height - steamSize - 6) * (1 - progress) * 0.65);
            sprite(graphics, FOOD, x, y, steamSize, steamSize, 128, 0, 64, 64,
                    (float) (Math.pow(Math.sin(progress * Math.PI), 2) * 0.38));
        }

        // 终端的小型就绪指示灯，保留电子设备的特征。
        int ledSize = Math.max(3, Math.min(5, height / 8));
        graphics.setColor(1, 1, 1, 1);
        int alpha = (int) (100 + (Math.sin(phase * 3) + 1) * 35);
        int ledX = left + width - ledSize - 5;
        graphics.fill(ledX, top + 4, ledX + ledSize, top + 4 + ledSize,
                alpha << 24 | 0xA2D8AE);
    }

    private static void sprite(GuiGraphics graphics, ResourceLocation texture,
                               int x, int y, int width, int height,
                               int u, int v, int sourceWidth, int sourceHeight, float alpha) {
        graphics.setColor(1, 1, 1, alpha);
        graphics.blit(texture, x, y, width, height, (float) u, (float) v,
                sourceWidth, sourceHeight, ATLAS_WIDTH, ATLAS_HEIGHT);
    }
}
