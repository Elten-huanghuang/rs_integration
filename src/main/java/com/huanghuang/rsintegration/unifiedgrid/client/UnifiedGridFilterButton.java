package com.huanghuang.rsintegration.unifiedgrid.client;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.widget.sidebutton.SideButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 使用原版侧按钮布局，窗口缩放时随原版按钮重新排布。 */
public final class UnifiedGridFilterButton extends SideButton {
    private final GridScreen grid;
    public UnifiedGridFilterButton(GridScreen screen) { super(screen); grid = screen; }

    @Override public void onPress() {
        if (grid.getView() instanceof UnifiedGridView view) view.cycleFilter();
    }

    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        if (grid.getView() instanceof UnifiedGridView view)
            graphics.drawCenteredString(Minecraft.getInstance().font, view.filterLabel().getString().substring(0, 1),
                    x + 8, y + 4, 0xFFFFFF);
    }

    @Override protected String getSideButtonTooltip() {
        return grid.getView() instanceof UnifiedGridView view ? view.filterLabel().getString() + "\n"
                + Component.translatable("rs_integration.unified_grid.container_hint").getString() : "";
    }
}
