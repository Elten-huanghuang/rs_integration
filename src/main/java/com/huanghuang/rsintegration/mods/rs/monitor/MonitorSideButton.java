package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.widget.sidebutton.SideButton;
import net.minecraft.client.gui.GuiGraphics;

/** 保留 RS 侧栏布局及交互，只统一底板；图标严格限制在内部 16×16 区域。 */
abstract class MonitorSideButton extends SideButton {
    protected MonitorSideButton(BaseScreen<?> screen) { super(screen); }

    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        isHovered = mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
        CraftingUiStyle.raised(graphics, getX(), getY(), width, height,
                isHoveredOrFocused() ? 0xFFE4E2EB : CraftingUiStyle.PANEL, CraftingUiStyle.BORDER);
        renderButtonIcon(graphics, getX() + 1, getY() + 1);
        if (isHovered) screen.renderTooltip(graphics, mouseX, mouseY, getSideButtonTooltip());
    }
}
