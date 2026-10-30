package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 使用纸页主题绘制的按钮。 */
@OnlyIn(Dist.CLIENT)
public final class FoodOrderButton extends Button {

    public FoodOrderButton(int x, int y, int width, int height,
                           Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FoodOrderTheme.button(graphics, Minecraft.getInstance().font, getMessage(),
                getX(), getY(), getWidth(), getHeight(), isHovered(), active);
    }
}
