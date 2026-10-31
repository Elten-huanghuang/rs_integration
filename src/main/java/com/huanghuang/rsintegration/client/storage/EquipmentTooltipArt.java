package com.huanghuang.rsintegration.client.storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 小幅实心底图配合独立光纹，避免为每种设备保存整套动画帧。 */
final class EquipmentTooltipArt {
    private static final int BASE_WIDTH = 384;
    private static final int BASE_HEIGHT = 192;
    private static final int ATLAS_SIZE = 128;
    private static final double TAU = Math.PI * 2;
    private static final ResourceLocation MOTIFS = new ResourceLocation("rs_integration",
            "textures/gui/disk_tooltip/equipment_motifs.png");
    private static final ResourceLocation MANA = new ResourceLocation("rs_integration",
            "textures/magic/mana.png");
    private static final ResourceLocation SOURCE = new ResourceLocation("rs_integration",
            "textures/magic/source.png");

    private EquipmentTooltipArt() {}

    static void draw(GuiGraphics graphics, int left, int top, int width, int height,
                     DiskTooltipBackground.Theme theme, double phase) {
        double scale = Math.max(width / (double) BASE_WIDTH, height / (double) BASE_HEIGHT) * 1.06;
        int sampleWidth = Math.min(BASE_WIDTH, Math.max(1, (int) Math.ceil(width / scale)));
        int sampleHeight = Math.min(BASE_HEIGHT, Math.max(1, (int) Math.ceil(height / scale)));
        graphics.blit(theme.background(), left, top, width, height,
                (float) ((BASE_WIDTH - sampleWidth) * (0.5 + Math.sin(phase) * 0.24)),
                (float) ((BASE_HEIGHT - sampleHeight) * (0.5 + Math.cos(phase) * 0.24)),
                sampleWidth, sampleHeight, BASE_WIDTH, BASE_HEIGHT);
        if (theme.artwork() == DiskTooltipBackground.Artwork.MANA
                || theme.artwork() == DiskTooltipBackground.Artwork.SOURCE) {
            fluid(graphics, left, top, width, height, theme.artwork(), phase);
        }
        if (width < 16 || height < 16) return;

        switch (theme.artwork()) {
            case MANA -> water(graphics, left, top, width, height, theme, phase);
            case SOURCE -> runes(graphics, left, top, width, height, theme, phase);
            case DIMENSION -> signals(graphics, left, top, width, height, theme, phase);
            default -> throw new IllegalArgumentException("不支持的设备背景");
        }
    }

    private static void fluid(GuiGraphics graphics, int left, int top, int width, int height,
                              DiskTooltipBackground.Artwork artwork, double phase) {
        boolean mana = artwork == DiskTooltipBackground.Artwork.MANA;
        ResourceLocation texture = mana ? MANA : SOURCE;
        int frames = mana ? 32 : 20;
        int sequence = mana ? frames : frames * 2 - 2;
        double position = phase / TAU * (mana ? 8 : 6) * sequence;
        int step = (int) position;
        float mix = (float) (position - step);
        // 沿用资源中魔源的往返帧序；原始动画条直接取帧，不复制成新的图集。
        for (int y = 0; y < height; y += 64) {
            for (int x = 0; x < width; x += 64) {
                int tileWidth = Math.min(64, width - x);
                int tileHeight = Math.min(64, height - y);
                float opacity = (float) (0.12 + Math.pow((x + tileWidth * 0.5) / width, 1.6) * 0.19);
                for (int layer = 0; layer < 2; layer++) {
                    int frame = (step + layer) % sequence;
                    if (frame >= frames) frame = sequence - frame;
                    float alpha = layer == 0 ? opacity * (1 - mix) / (1 - opacity * mix) : opacity * mix;
                    graphics.setColor(1, 1, 1, alpha);
                    // 边缘不足一格时裁取相应 UV，保持液体像素比例与接缝一致。
                    graphics.blit(texture, left + x, top + y, tileWidth, tileHeight,
                            0, frame * 16, Math.max(1, (tileWidth + 3) / 4),
                            Math.max(1, (tileHeight + 3) / 4), 16, frames * 16);
                }
            }
        }
    }

    private static void water(GuiGraphics graphics, int left, int top, int width, int height,
                              DiskTooltipBackground.Theme theme, double phase) {
        for (int i = 0; i < 3; i++) {
            double wave = phase + i * 2.1;
            int rippleWidth = Math.max(6, Math.min(width - 6, (int) (width * (0.55 + i * 0.12))));
            int rippleHeight = Math.max(4, Math.min(height - 6, height / 5));
            int x = left + (width - rippleWidth) / 2 + (int) (Math.sin(wave) * width * 0.03);
            int y = top + (int) (height * (0.63 + i * 0.09) + Math.cos(wave) * height * 0.025);
            sprite(graphics, left, top, width, height, x, y, rippleWidth, rippleHeight,
                    0, 0, 128, 32, theme.glowA(), (float) (0.28 + Math.sin(wave) * 0.08));
        }
        for (int i = 0; i < 5; i++) {
            double angle = phase + i * 1.7;
            point(graphics, left, top, width, height,
                    0.12 + i * 0.18 + Math.sin(angle) * 0.025,
                    0.74 + Math.cos(angle) * 0.14, theme.glowB(), 0.50F);
        }
    }

    private static void runes(GuiGraphics graphics, int left, int top, int width, int height,
                              DiskTooltipBackground.Theme theme, double phase) {
        int size = Math.max(6, Math.min(17, height / 7));
        for (int i = 0; i < 6; i++) {
            double progress = (phase / TAU * 2 + i / 6.0) % 1;
            int x = left + (int) (width * (0.57 + i * 0.064 + Math.sin(phase + i) * 0.018));
            int y = top + 3 + (int) ((height - size - 6) * (1 - progress));
            sprite(graphics, left, top, width, height, x, y, size, size,
                    0, 32, 32, 32, i % 2 == 0 ? theme.glowA() : theme.glowB(),
                    (float) (Math.pow(Math.sin(progress * Math.PI), 2) * 0.42));
        }
    }

    private static void signals(GuiGraphics graphics, int left, int top, int width, int height,
                                DiskTooltipBackground.Theme theme, double phase) {
        // 信号沿底部与右侧线路行进，给说明文字留出稳定的阅读区域。
        for (int i = 0; i < 4; i++) {
            double progress = (phase / TAU * 3 + i / 4.0) % 1;
            double x = progress < 0.72 ? 0.08 + progress : 0.80;
            double y = progress < 0.72 ? 0.86 - i * 0.065
                    : 0.86 - i * 0.065 - (progress - 0.72) * 2.1;
            point(graphics, left, top, width, height, x, y,
                    i % 2 == 0 ? theme.glowA() : theme.glowB(),
                    (float) (Math.sin(progress * Math.PI) * 0.65));
        }
    }

    private static void point(GuiGraphics graphics, int left, int top, int width, int height,
                              double x, double y, int color, float alpha) {
        int size = Math.max(3, Math.min(8, height / 14));
        sprite(graphics, left, top, width, height,
                left + (int) (x * width) - size / 2, top + (int) (y * height) - size / 2,
                size, size, 32, 32, 32, 32, color, alpha);
    }

    private static void sprite(GuiGraphics graphics, int left, int top, int width, int height,
                               int x, int y, int spriteWidth, int spriteHeight,
                               int u, int v, int sourceWidth, int sourceHeight, int color, float alpha) {
        x = Math.max(left + 2, Math.min(left + width - spriteWidth - 2, x));
        y = Math.max(top + 2, Math.min(top + height - spriteHeight - 2, y));
        graphics.setColor((color >> 16 & 255) / 255.0F,
                (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, alpha);
        graphics.blit(MOTIFS, x, y, spriteWidth, spriteHeight, (float) u, (float) v,
                sourceWidth, sourceHeight, ATLAS_SIZE, ATLAS_SIZE);
    }
}
