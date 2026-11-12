package com.huanghuang.rsintegration.client.storage;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;

/** 按画师拼接的归墟金框：保留顶边开口、独立右框和左下箭头。 */
final class GuixuTooltipArt {
    static final int SIDE_SPACE = 11;
    static final int HEADER_SPACE = 14;
    static final int BOTTOM_SPACE = 13;
    static final int LEFT_MARGIN = SIDE_SPACE + 25;
    static final int RIGHT_MARGIN = SIDE_SPACE + 12;
    static final int HORIZONTAL_SPACE = LEFT_MARGIN + RIGHT_MARGIN;
    private static final ResourceLocation TOP = texture("top");
    private static final ResourceLocation LEFT_TILE = texture("left_tile");
    private static final ResourceLocation LEFT_BOTTOM = texture("left_bottom");
    private static final ResourceLocation RIGHT_TOP = texture("right_top");
    private static final ResourceLocation RIGHT_TILE = texture("right_tile");
    private static final ResourceLocation RIGHT_BOTTOM = texture("right_bottom");

    private GuixuTooltipArt() {}

    private static ResourceLocation texture(String part) {
        return new ResourceLocation("rs_integration", "textures/gui/disk_tooltip/guixu_frame_" + part + ".png");
    }

    static void reserveFrameSpace(RenderTooltipEvent.Pre event) {
        DiskTooltipBackground.reserveFrameSpace(event, LEFT_MARGIN, RIGHT_MARGIN,
                HEADER_SPACE + 16, BOTTOM_SPACE + 15);
    }

    static boolean supportsFrame(int width, int height) {
        return width >= 137 && height >= 57;
    }

    static int leftInset(int y, int height) {
        return Math.max(0, Math.min(3, y - (height - 19)));
    }

    static int rightOutset(int y, int height) {
        return Math.max(0, Math.min(5, Math.round((y - (height - 11)) * 1.25F)));
    }

    static void drawFrame(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!supportsFrame(width, height)) return;
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int x = left - 20;
        int y = top - 11;
        int right = left + width;
        int bottom = top + height;
        drawTop(graphics, x, y, width - 8);
        tileVertical(graphics, LEFT_TILE, x + 11, y + 46, bottom - 22, 9, 16);
        sprite(graphics, LEFT_BOTTOM, x + 11, bottom - 22, 50, 32);
        sprite(graphics, RIGHT_TOP, right - 18, y + 2, 18, 33);
        tileVertical(graphics, RIGHT_TILE, right - 2, y + 35, bottom - 25, 2, 18);
        sprite(graphics, RIGHT_BOTTOM, right - 9, bottom - 25, 16, 25);
    }

    private static void drawTop(GuiGraphics graphics, int x, int y, int targetWidth) {
        int extension = Math.max(0, Math.round((targetWidth - 129) / 4.0F) * 4);
        graphics.blit(TOP, x, y, 70, 46, 0, 0, 70, 46, 129, 46);
        // 只平铺四像素的衔接斜纹；菱形角饰、台阶与右端都保持原图比例。
        for (int offset = 70; offset < 70 + extension; offset += 4) {
            graphics.blit(TOP, x + offset, y, 4, 46, 70, 0, 4, 46, 129, 46);
        }
        graphics.blit(TOP, x + 70 + extension, y, 59, 46, 70, 0, 59, 46, 129, 46);
    }

    private static void tileVertical(GuiGraphics graphics, ResourceLocation texture,
                                     int x, int start, int end, int width, int tileHeight) {
        for (int y = start; y < end; y += tileHeight) {
            int height = Math.min(tileHeight, end - y);
            graphics.blit(texture, x, y, width, height, 0, 0, width, height, width, tileHeight);
        }
    }

    private static void sprite(GuiGraphics graphics, ResourceLocation texture,
                               int x, int y, int width, int height) {
        graphics.blit(texture, x, y, width, height, 0, 0, width, height, width, height);
    }
}
