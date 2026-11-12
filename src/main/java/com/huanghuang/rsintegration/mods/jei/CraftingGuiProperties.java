package com.huanghuang.rsintegration.mods.jei;

import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

import javax.annotation.Nullable;

/** 保存当前帧的真实面板边界，窗口缩放后由 screen handler 重新计算。 */
record CraftingGuiProperties(Class<? extends Screen> screenClass, int left, int top,
                             int width, int height, int screenWidth, int screenHeight) implements IGuiProperties {
    @Nullable
    static CraftingGuiProperties of(Screen screen, Rect2i area) {
        // JEI 在 Screen.init 之前也会查询界面；此时不能发布宽高为零的属性。
        if (screen.width <= 0 || screen.height <= 0 || area.getWidth() <= 0 || area.getHeight() <= 0) {
            return null;
        }
        return new CraftingGuiProperties(screen.getClass(), area.getX(), area.getY(),
                area.getWidth(), area.getHeight(), screen.width, screen.height);
    }

    @Override public Class<? extends Screen> getScreenClass() { return screenClass; }
    @Override public int getGuiLeft() { return left; }
    @Override public int getGuiTop() { return top; }
    @Override public int getGuiXSize() { return width; }
    @Override public int getGuiYSize() { return height; }
    @Override public int getScreenWidth() { return screenWidth; }
    @Override public int getScreenHeight() { return screenHeight; }
}
