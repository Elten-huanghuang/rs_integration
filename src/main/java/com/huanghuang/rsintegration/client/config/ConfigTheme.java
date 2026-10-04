package com.huanghuang.rsintegration.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

final class ConfigTheme {
    static final int TEXT = 0xFF36323E;
    static final int MUTED = 0xFF79717F;
    static final int BORDER = 0xFFAAA2B3;
    static final int ACCENT = 0xFF8054FF;
    static final int PINK = 0xFFF7EDF3;
    static final int WHITE = 0xFFFFFEFF;
    static final int SURFACE = 0xFFF5F3F7;
    static final int SURFACE_HOVER = 0xFFFBF7FC;
    static final int EDITOR = 0xFF2D2938;
    static final int EDITOR_BORDER = 0xFF5C536B;
    static final int ERROR = 0xFFBA374E;

    private ConfigTheme() {}

    static void frame(GuiGraphics graphics, int x, int y, int width, int height, int fill) {
        graphics.fill(x, y, x + width, y + height, BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        graphics.hLine(x + 1, x + width - 2, y + 1, 0xFFFFFFFF);
        graphics.vLine(x + 1, y + 1, y + height - 2, 0xFFFFFFFF);
    }

    static final class ConfigButton extends Button {
        ConfigButton(int x, int y, int width, int height, Component message, OnPress press) {
            super(x, y, width, height, message, press, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            frame(graphics, getX(), getY(), getWidth(), getHeight(), active && isHoveredOrFocused() ? PINK : WHITE);
            if (active && isHoveredOrFocused()) {
                graphics.hLine(getX() + 1, getX() + getWidth() - 2, getY() + getHeight() - 2, ACCENT);
            }
            var font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8);
            graphics.drawString(font, label,
                    getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - 8) / 2,
                    active ? TEXT : MUTED, false);
        }
    }

    static final class ToggleButton extends Button {
        private final BooleanSupplier enabled;

        ToggleButton(int x, int y, int width, int height, Component message, BooleanSupplier enabled, OnPress press) {
            super(x, y, width, height, message, press, DEFAULT_NARRATION);
            this.enabled = enabled;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean on = enabled.getAsBoolean();
            int x = getX() + 5;
            int y = getY() + 4;
            graphics.fill(x, y, x + 22, y + 10, active && on ? ACCENT : BORDER);
            int knob = x + (on ? 13 : 2);
            graphics.fill(knob, y + 2, knob + 7, y + 8, WHITE);
            graphics.drawString(Minecraft.getInstance().font, getMessage(), getX() + 34, getY() + 5,
                    active ? TEXT : MUTED, false);
            if (active && isHoveredOrFocused()) graphics.hLine(getX() + 4, getX() + getWidth() - 5, getY() + 17, ACCENT);
        }
    }
}
