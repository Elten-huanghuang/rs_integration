package com.huanghuang.rsintegration.voidupgrade.client;

import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.handlers.IScreenHandler;
import net.minecraft.client.gui.screens.Screen;

public final class VoidUpgradeJeiScreenHandler implements IScreenHandler<VoidUpgradeScreen> {
    @Override
    public IGuiProperties apply(VoidUpgradeScreen screen) {
        int left = screen.getPanelLeft();
        int top = screen.getPanelTop();
        int width = screen.getPanelWidth();
        int height = screen.getPanelHeight();
        return new IGuiProperties() {
            @Override
            public Class<? extends Screen> getScreenClass() {
                return VoidUpgradeScreen.class;
            }

            @Override
            public int getGuiLeft() {
                return left;
            }

            @Override
            public int getGuiTop() {
                return top;
            }

            @Override
            public int getGuiXSize() {
                return width;
            }

            @Override
            public int getGuiYSize() {
                return height;
            }

            @Override
            public int getScreenWidth() {
                return screen.width;
            }

            @Override
            public int getScreenHeight() {
                return screen.height;
            }
        };
    }
}
