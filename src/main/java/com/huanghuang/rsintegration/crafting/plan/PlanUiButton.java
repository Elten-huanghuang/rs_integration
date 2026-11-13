package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 和递归树主题共用颜色的按钮，保留原版焦点、提示和无障碍行为。 */
final class PlanUiButton extends Button {
    private boolean primary;
    PlanUiButton primary() { primary = true; return this; }
    PlanUiButton(int x, int y, int width, int height, Component message, OnPress press) {
        super(x, y, width, height, message, press, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), width = getWidth(), height = getHeight();
        PlanUiDraw.rounded(graphics, x, y, width, height, 3,
                (primary && active ? Color.PRIMARY_BORDER : isHoveredOrFocused() ? Color.ACTIVE_BORDER : Color.BORDER).value());
        PlanUiDraw.rounded(graphics, x + 1, y + 1, width - 2, height - 2, 2,
                (primary && active ? Color.PRIMARY : isHoveredOrFocused() ? Color.HOVER : Color.BUTTON).value());
        PlanWorkspaceChrome chrome = new PlanWorkspaceChrome(Minecraft.getInstance().font);
        String label = chrome.fit(getMessage().getString(), width - 10);
        graphics.drawString(Minecraft.getInstance().font, label,
                x + (width - Minecraft.getInstance().font.width(label)) / 2,
                y + (height - Minecraft.getInstance().font.lineHeight) / 2,
                (active ? Color.TEXT : Color.DISABLED).value(), false);
    }
}
