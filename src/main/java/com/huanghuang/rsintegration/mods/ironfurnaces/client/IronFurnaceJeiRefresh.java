package com.huanghuang.rsintegration.mods.ironfurnaces.client;

import com.huanghuang.rsintegration.mixin.jei.RecipesGuiMixin;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;

public final class IronFurnaceJeiRefresh {

    private IronFurnaceJeiRefresh() {}

    public static void refreshIfOpen() {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof RecipesGui) {
            ((RecipesGuiMixin) (Object) screen).rsi$invokeUpdateLayout();
        }
    }
}
