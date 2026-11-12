package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

public final class MonitorDetailsButton extends MonitorSideButton {
    private final Runnable toggle;
    public MonitorDetailsButton(BaseScreen<?> screen, Runnable toggle) { super(screen); this.toggle = toggle; }
    @Override public void onPress() { toggle.run(); }
    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 3, y + 4, x + 4, y + 12, CraftingUiStyle.accent());
        for (int row = 0; row < 2; row++) {
            graphics.fill(x + 4, y + 5 + row * 6, x + 8, y + 6 + row * 6, CraftingUiStyle.accent());
            CraftingUiStyle.surface(graphics, x + 8, y + 3 + row * 6, 6, 5,
                    row == 0 ? MonitorGridStyle.RUNNING.color() : MonitorGridStyle.SCHEDULED.color(), CraftingUiStyle.accent());
        }
    }
    @Override protected String getSideButtonTooltip() { return I18n.get("rsi.monitor.toggle_steps"); }
}
