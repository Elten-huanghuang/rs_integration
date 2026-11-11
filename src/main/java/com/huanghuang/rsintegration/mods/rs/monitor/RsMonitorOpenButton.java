package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/** 放在 RS 合成终端原生侧栏内，直接打开其网络的 RS 合成监控器。 */
public final class RsMonitorOpenButton extends MonitorSideButton {
    private final GridScreen screen;

    public RsMonitorOpenButton(BaseScreen<?> screen, GridScreen grid) {
        super(screen);
        this.screen = grid;
    }

    @Override public void onPress() { RsMonitorNavigationClient.openMonitor(screen); }
    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 3, y + 3, x + 13, y + 13, 0xFFB6DFD3);
        graphics.fill(x + 5, y + 5, x + 11, y + 6, 0xFF406C60);
        graphics.fill(x + 5, y + 8, x + 9, y + 9, 0xFF406C60);
        graphics.fill(x + 5, y + 11, x + 11, y + 12, 0xFF406C60);
    }
    @Override protected String getSideButtonTooltip() { return I18n.get("rsi.monitor.open_crafting_monitor"); }
}
