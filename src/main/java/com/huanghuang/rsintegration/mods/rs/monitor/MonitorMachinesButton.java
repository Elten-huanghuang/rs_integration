package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.screen.BaseScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MonitorMachinesButton extends MonitorSideButton {
    private static final ItemStack ICON = new ItemStack(Items.PISTON);
    private final Runnable open;

    public MonitorMachinesButton(BaseScreen<?> screen, Runnable open) {
        super(screen);
        this.open = open;
    }

    @Override public void onPress() { open.run(); }
    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) { graphics.renderItem(ICON, x, y); }
    @Override protected String getSideButtonTooltip() { return I18n.get("rsi.control_center.machines"); }
}
