package com.huanghuang.rsintegration.client.storage;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;

/** 点餐终端的开放式外框，只延长直线段，保留徽记、斜纹和左下转折的原始比例。 */
final class OrderTerminalTooltipArt {
    static final int LEFT_SPACE = 14;
    static final int RIGHT_SPACE = 6;
    static final int HEADER_SPACE = 8;
    static final int BOTTOM_SPACE = 6;
    static final int LEFT_MARGIN = LEFT_SPACE + 30;
    static final int RIGHT_MARGIN = RIGHT_SPACE + 5;
    static final int TOP_MARGIN = HEADER_SPACE + 10;
    static final int BOTTOM_MARGIN = BOTTOM_SPACE + 5;
    static final int HORIZONTAL_SPACE = LEFT_MARGIN + RIGHT_MARGIN;
    private static final int TEXTURE_WIDTH = 119;
    private static final int TEXTURE_HEIGHT = 67;
    private static final ResourceLocation FRAME = new ResourceLocation("rs_integration",
            "textures/gui/disk_tooltip/order_terminal_frame.png");

    private OrderTerminalTooltipArt() {}

    static void reserveFrameSpace(RenderTooltipEvent.Pre event) {
        DiskTooltipBackground.reserveFrameSpace(event, LEFT_MARGIN, RIGHT_MARGIN, TOP_MARGIN, BOTTOM_MARGIN);
    }

    static boolean supportsFrame(int width, int height) {
        return width >= 94 && height >= 54;
    }

    static void drawFrame(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!supportsFrame(width, height)) return;
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int x = left - 25;
        int y = top - 5;
        int extension = width - 94;
        int firstExtension = extension / 2;
        int secondExtension = extension - firstExtension;

        // 顶部凸起两侧各延长一段单色横线，使凸起保持居中且不拉宽角饰。
        piece(graphics, x, y, 35, 31, 0, 0, 35, 31);
        if (firstExtension > 0) {
            piece(graphics, x + 35, y, firstExtension, 31, 35, 0, 1, 31);
        }
        piece(graphics, x + 35 + firstExtension, y, 47, 31, 35, 0, 47, 31);
        if (secondExtension > 0) {
            piece(graphics, x + 82 + firstExtension, y, secondExtension, 31, 82, 0, 1, 31);
        }
        piece(graphics, x + 82 + extension, y, 37, 31, 82, 0, 37, 31);

        int footerY = top + height - 27;
        // 竖轨只重复同色的一行，左下折线和收尾始终使用完整原图切片。
        piece(graphics, x + 19, y + 31, 7, footerY - (y + 31), 19, 32, 7, 1);
        piece(graphics, x + 19, footerY, 16, 27, 19, 40, 16, 27);
    }

    private static void piece(GuiGraphics graphics, int x, int y, int width, int height,
                              int u, int v, int sourceWidth, int sourceHeight) {
        graphics.blit(FRAME, x, y, width, height, (float) u, (float) v,
                sourceWidth, sourceHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }
}
