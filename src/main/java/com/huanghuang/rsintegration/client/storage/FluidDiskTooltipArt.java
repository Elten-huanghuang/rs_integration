package com.huanghuang.rsintegration.client.storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 无限水盘、岩浆盘的空岛底图；普通和展开提示共用一张图。 */
final class FluidDiskTooltipArt {
    static final int TEXTURE_WIDTH = 384;
    static final int TEXTURE_HEIGHT = 216;

    private FluidDiskTooltipArt() {}

    static void draw(GuiGraphics graphics, ResourceLocation texture, int left, int top, int width, int height) {
        int imageWidth = Math.min(width, Math.round(height * (TEXTURE_WIDTH / (float) TEXTURE_HEIGHT)));
        int sourceWidth = Math.min(TEXTURE_WIDTH, Math.max(1,
                Math.round(imageWidth * (TEXTURE_HEIGHT / (float) height))));
        int skyWidth = width - imageWidth;
        graphics.setColor(0.52F, 0.52F, 0.52F, 1.0F);
        // 矮而宽的提示框向左延展天空；较窄的框只裁掉左侧，保留右侧的岛。
        if (skyWidth > 0) {
            graphics.blit(texture, left, top, skyWidth, height,
                    0.0F, 0.0F, 1, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
        graphics.blit(texture, left + skyWidth, top, imageWidth, height,
                TEXTURE_WIDTH - sourceWidth, 0.0F, sourceWidth, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }
}
