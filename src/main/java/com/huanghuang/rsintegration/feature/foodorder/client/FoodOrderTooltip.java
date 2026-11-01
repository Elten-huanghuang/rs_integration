package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;

import java.util.ArrayList;
import java.util.List;

/** 直接复用 Forge 提示组件，保留 AppleSkin 等模组的图像、数值修正和自定义渲染。 */
final class FoodOrderTooltip {
    private FoodOrderTooltip() {}

    static List<ClientTooltipComponent> gather(Minecraft minecraft, Font font,
                                               ItemStack stack, int width, int screenHeight) {
        List<Component> nativeLines = Screen.getTooltipFromItem(minecraft, stack).stream()
                .map(FoodOrderTooltip::onPaper).toList();
        List<ClientTooltipComponent> components = new ArrayList<>(ForgeHooksClient.gatherTooltipComponents(
                stack, nativeLines, stack.getTooltipImage(), 0, width + 16, screenHeight, font));
        if (components.isEmpty()) {
            return List.of();
        }
        // 纸页顶部已经展示物品名称，仅去掉原提示的名称，保留模组注入的所有信息。
        if (components.get(0) instanceof ClientTextTooltip) {
            components.remove(0);
        }
        boolean nativeFoodRenderer = components.stream()
                .anyMatch(component -> component.getClass().getName().startsWith("squeek.appleskin."));
        if (!nativeFoodRenderer) {
            FoodOrderFoodInfo fallback = FoodOrderFoodInfo.from(stack, minecraft.player);
            if (fallback != null) {
                components.add(0, fallback);
            }
        }
        return List.copyOf(components);
    }

    static int height(List<ClientTooltipComponent> components, Font font, int width) {
        return components.stream().mapToInt(component -> height(component, font, width)).sum();
    }

    static void render(GuiGraphics graphics, Font font, List<ClientTooltipComponent> components,
                       int x, int y, int width, int height, int scroll) {
        graphics.enableScissor(x, y, x + width, y + height);
        try {
            int top = y - scroll;
            for (ClientTooltipComponent component : components) {
                int rowHeight = height(component, font, width);
                if (top + rowHeight > y && top < y + height) {
                    float scale = scale(component, font, width);
                    var pose = graphics.pose();
                    pose.pushPose();
                    try {
                        pose.translate(x, top, 0);
                        pose.scale(scale, scale, 1);
                        component.renderText(font, 0, 0, pose.last().pose(), graphics.bufferSource());
                        component.renderImage(font, 0, 0, graphics);
                    } finally {
                        pose.popPose();
                    }
                }
                top += rowHeight;
            }
        } finally {
            // 提交组件的文字和图像后才能取消裁剪，防止长提示盖住书签与下单按钮。
            graphics.flush();
            graphics.disableScissor();
            graphics.setColor(1, 1, 1, 1);
        }
    }

    private static int height(ClientTooltipComponent component, Font font, int width) {
        return Math.max(1, (int) Math.ceil(component.getHeight() * scale(component, font, width))) + 2;
    }

    private static float scale(ClientTooltipComponent component, Font font, int width) {
        return Math.min(1F, Math.max(1, width) / (float) Math.max(1, component.getWidth(font)));
    }

    private static Component onPaper(Component line) {
        var color = line.getStyle().getColor();
        return color == null || color.getValue() == 0xFFFFFF
                ? line.copy().withStyle(style -> style.withColor(FoodOrderTheme.TEXT & 0xFFFFFF))
                : line;
    }
}
