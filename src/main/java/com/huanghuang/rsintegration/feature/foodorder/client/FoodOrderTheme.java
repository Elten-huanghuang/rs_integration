package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderKind;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 点餐终端的棕色纸页主题。
 *
 * <p>移植自旧附属 1.0.19 的 GuiTheme，保留原版的分层纸页、折角卡片、状态灯和
 * 按钮 hover 竖条；全部用纯色绘制，不依赖额外贴图。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FoodOrderTheme {

    public static final int OUTER = -7046817;
    public static final int PANEL_TOP = -726316;
    public static final int PANEL_BOT = -726316;
    public static final int HEADER_L = -726316;
    public static final int HEADER_R = -726316;
    public static final int ACCENT = -6009263;
    public static final int ACCENT_DK = -8438203;
    public static final int TEXT = -11518670;
    public static final int TEXT_DIM = -8361131;
    public static final int TITLE = -8897214;
    public static final int OK = -11702192;
    public static final int BAD = -6140605;
    public static final int CYAN = -11176598;
    public static final int CARD = -1559;
    public static final int CARD_EDGE = -2570841;
    public static final int LIST_BG = -331040;
    public static final int ROW_SEL = -1453118;
    public static final int ROW_HOVER = -1055797;
    public static final int LINE = -2043466;
    public static final int SLOT_BG = -792627;
    public static final int SLOT_EDGE = -3557483;

    private FoodOrderTheme() {}

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 2, y + 4, x + width + 3, y + height + 4, 0x45000000);
        graphics.fill(x + 2, y - 2, x + width - 2, y + height + 2, OUTER);
        graphics.fill(x - 2, y + 2, x + width + 2, y + height - 2, OUTER);
        graphics.fill(x, y, x + width, y + height, PANEL_TOP);
        graphics.fill(x + 3, y + height - 3, x + width - 3, y + height - 2, CARD_EDGE);
        graphics.fill(x + 3, y + height - 1, x + width - 3, y + height, CARD);
    }

    public static void header(GuiGraphics graphics, Font font, Component title,
                              int x, int y, int width, int height) {
        graphics.fill(x + 8, y, x + 16, y + height - 5, ACCENT);
        graphics.fill(x + 8, y + height - 5, x + 11, y + height - 2, ACCENT);
        graphics.fill(x + 13, y + height - 5, x + 16, y + height - 2, ACCENT);
        graphics.drawString(font, title, x + 23, y + (height - 8) / 2, TITLE, false);
        divider(graphics, x + 23, y + height - 1, width - 32);
    }

    public static void led(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 1, y, x + 3, y + 4, color);
        graphics.fill(x, y + 1, x + 4, y + 3, color);
    }

    public static void card(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 1, y + 2, x + width + 1, y + height + 2, LINE);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, CARD_EDGE);
        graphics.fill(x, y, x + width, y + height, CARD);
        graphics.fill(x + width - 5, y, x + width, y + 5, LINE);
        graphics.fill(x + width - 3, y, x + width, y + 2, PANEL_TOP);
    }

    public static void listFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, LIST_BG);
        graphics.fill(x, y, x + width, y + 1, CARD_EDGE);
        graphics.fill(x, y + height - 1, x + width, y + height, CARD_EDGE);
    }

    public static void slot(GuiGraphics graphics, int x, int y, int size, boolean selected) {
        graphics.fill(x, y, x + size, y + size, selected ? ROW_SEL : SLOT_BG);
        graphics.fill(x + 2, y + size - 1, x + size - 2, y + size,
                selected ? ACCENT : SLOT_EDGE);
    }

    public static void sectionLabel(GuiGraphics graphics, Font font, Component text,
                                    int x, int y, int color) {
        led(graphics, x, y + 2, ACCENT);
        graphics.drawString(font, text, x + 7, y, color, false);
    }

    public static void divider(GuiGraphics graphics, int x, int y, int width) {
        for (int offset = 0; offset < width; offset += 4) {
            graphics.fill(x + offset, y, x + Math.min(width, offset + 2), y + 1, LINE);
        }
    }

    public static void progressBar(GuiGraphics graphics, int x, int y,
                                   int width, int height, int percent) {
        int filled = width * Math.max(0, Math.min(100, percent)) / 100;
        graphics.fill(x, y, x + width, y + height, LINE);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, OK);
        }
    }

    public static void scrollbar(GuiGraphics graphics, int x, int y, int height,
                                 int scroll, int total, int visible) {
        if (total > visible && visible > 0 && height > 0) {
            int maxScroll = Math.max(1, total - visible);
            int barHeight = Math.min(height, Math.max(12, height * visible / total));
            int barY = y + (height - barHeight) * Math.min(Math.max(scroll, 0), maxScroll) / maxScroll;
            graphics.fill(x + 1, y, x + 2, y + height, LINE);
            graphics.fill(x, barY, x + 3, barY + barHeight, ACCENT);
        }
    }

    public static void emptyArt(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 12, y - 7, x + 12, y + 7, CARD_EDGE);
        graphics.fill(x - 11, y - 8, x - 1, y + 5, CARD);
        graphics.fill(x + 1, y - 8, x + 11, y + 5, CARD);
        for (int row = 0; row < 3; row++) {
            graphics.fill(x - 9, y - 5 + row * 3, x - 3, y - 4 + row * 3, LINE);
            graphics.fill(x + 3, y - 5 + row * 3, x + 9, y - 4 + row * 3, LINE);
        }
        graphics.fill(x + 6, y - 8, x + 8, y - 2, ACCENT);
    }

    public static void button(GuiGraphics graphics, Font font, Component message,
                              int x, int y, int width, int height,
                              boolean hovered, boolean active) {
        int border = active && hovered ? ACCENT_DK : CARD_EDGE;
        int fill = !active ? PANEL_TOP : (hovered ? ACCENT : CARD);
        int textColor = !active ? -6517385 : (hovered ? CARD : TEXT);
        graphics.fill(x, y - 1, x + width, y + height + 1, border);
        graphics.fill(x - 1, y, x + width + 1, y + height, border);
        graphics.fill(x, y, x + width, y + height, fill);
        if (active && !hovered) {
            graphics.fill(x + 3, y + 4, x + 5, y + height - 4, ACCENT);
        }

        String text = message.getString();
        if (font.width(text) > width - 14) {
            text = font.plainSubstrByWidth(text, Math.max(0, width - 20)) + "…";
        }
        graphics.drawString(font, text, x + (width - font.width(text)) / 2,
                y + (height - 8) / 2, textColor, false);
    }

    /** 与原版一致的状态颜色：就绪绿、缺料/机器蓝、等待/无配方红、索引灰。 */
    public static int statusColor(FoodOrderKind kind) {
        return switch (kind) {
            case READY -> OK;
            case MISSING, NO_MACHINE -> ACCENT;
            case WAITING, LOCKED, NORECIPE -> BAD;
            case INDEXING -> TEXT_DIM;
        };
    }
}
