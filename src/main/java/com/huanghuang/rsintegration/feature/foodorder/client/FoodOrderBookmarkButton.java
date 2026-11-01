package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 贴在详情纸页右下方的红色燕尾书签。 */
@OnlyIn(Dist.CLIENT)
final class FoodOrderBookmarkButton extends Button {
    private boolean favorite;

    FoodOrderBookmarkButton(int x, int y, int width, int height, OnPress onPress) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        setFavorite(false);
    }

    void setFavorite(boolean favorite) {
        this.favorite = favorite;
        setMessage(Component.translatable(favorite
                ? "rsi.food_order.favorite.saved" : "rsi.food_order.favorite.label"));
        setTooltip(Tooltip.create(Component.translatable(favorite
                ? "rsi.food_order.favorite.remove" : "rsi.food_order.favorite.add")));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FoodOrderTheme.bookmark(graphics, Minecraft.getInstance().font, getMessage(),
                getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused(), favorite);
    }
}
