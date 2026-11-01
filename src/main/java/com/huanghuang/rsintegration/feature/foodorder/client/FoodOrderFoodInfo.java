package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Locale;

/** 没有食物信息模组接管时，使用原版 HUD 图标显示饱食度与实际恢复的饱和度。 */
record FoodOrderFoodInfo(int nutrition, float saturation) implements ClientTooltipComponent {
    private static final ResourceLocation HUD_ICONS = ResourceLocation.parse("minecraft:textures/gui/icons.png");

    @Nullable
    static FoodOrderFoodInfo from(ItemStack stack, @Nullable LivingEntity consumer) {
        try {
            FoodProperties food = stack.getFoodProperties(consumer);
            return food == null ? null : new FoodOrderFoodInfo(food.getNutrition(),
                    food.getNutrition() * food.getSaturationModifier() * 2.0F);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Override
    public int getHeight() {
        return 24;
    }

    @Override
    public int getWidth(Font font) {
        return 14 + Math.max(font.width(nutritionLabel()), font.width(saturationLabel()));
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        graphics.blit(HUD_ICONS, x, y, 52, 27, 9, 9);
        graphics.drawString(font, nutritionLabel(), x + 14, y, FoodOrderTheme.TEXT, false);
        try {
            graphics.setColor(1.0F, 0.75F, 0.25F, 1.0F);
            graphics.blit(HUD_ICONS, x, y + 11, 52, 27, 9, 9);
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        graphics.drawString(font, saturationLabel(), x + 14, y + 11, FoodOrderTheme.TEXT, false);
    }

    private Component nutritionLabel() {
        return Component.translatable("rsi.food_order.food.nutrition", nutrition);
    }

    private Component saturationLabel() {
        return Component.translatable("rsi.food_order.food.saturation",
                String.format(Locale.ROOT, "%.1f", saturation));
    }
}
