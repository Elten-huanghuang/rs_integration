package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.config.CraftingUiColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/** 放在原生侧按钮布局内，避免覆盖任务标签和取消按钮。 */
public final class MonitorPlansButton extends MonitorSideButton {
    private final Runnable open;

    public MonitorPlansButton(BaseScreen<?> screen, Runnable open) {
        super(screen);
        this.open = open;
    }

    @Override public void onPress() { open.run(); }
    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        CraftingUiStyle.surface(graphics, x + 3, y + 2, 10, 12, CraftingUiColors.MONITOR_ICON_PAPER.argb(), CraftingUiStyle.accent());
        for (int row = 0; row < 3; row++) {
            graphics.fill(x + 5, y + 5 + row * 3, x + 6, y + 6 + row * 3, CraftingUiStyle.accent());
            graphics.fill(x + 8, y + 5 + row * 3, x + 11, y + 6 + row * 3, CraftingUiStyle.accent());
        }
    }
    @Override protected String getSideButtonTooltip() { return I18n.get("rsi.monitor.plans"); }
}
