package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/** 原生监控器内固定的同网络合成终端入口。 */
public final class RsMonitorReturnButton extends MonitorSideButton {
    private final CraftingMonitorContainerMenu menu;
    private final MonitorNavigationAccess navigation;

    public RsMonitorReturnButton(BaseScreen<?> screen, CraftingMonitorContainerMenu menu, MonitorNavigationAccess navigation) {
        super(screen);
        this.menu = menu;
        this.navigation = navigation;
    }

    @Override public void onPress() { RsMonitorNavigationClient.returnToTerminal(menu, navigation); }
    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 4, y + 3, x + 12, y + 5, 0xFF64758B);
        graphics.fill(x + 4, y + 5, x + 6, y + 12, 0xFF64758B);
        graphics.fill(x + 5, y + 11, x + 12, y + 13, 0xFF64758B);
        graphics.fill(x + 9, y + 7, x + 13, y + 9, 0xFF64758B);
        graphics.fill(x + 11, y + 6, x + 13, y + 10, 0xFF64758B);
    }
    @Override protected String getSideButtonTooltip() { return I18n.get("rsi.monitor.return_to_terminal"); }
}
