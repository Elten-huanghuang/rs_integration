package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** Version-independent content and placement rules for JEI item overlays. */
public final class JeiNetworkOverlayRenderer {
    private JeiNetworkOverlayRenderer() {}

    public static boolean shouldRender() {
        return inventoryEnabled() || shortageEnabled();
    }

    public static void render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        Font font = Minecraft.getInstance().font;
        if (inventoryEnabled() && JeiNetworkItemCache.INSTANCE.isConnected()) {
            long amount = JeiNetworkItemCache.INSTANCE.amount(stack);
            if (amount > 0) drawRightBottom(graphics, font, format(amount), x, y);
        }
        if (shortageEnabled()) {
            var demand = JeiCraftingPlanContext.INSTANCE.demand(stack, JeiNetworkItemCache.INSTANCE);
            if (demand != null) {
                long currentAmount = demand.exactNbt()
                        ? JeiNetworkItemCache.INSTANCE.amount(stack)
                        : JeiNetworkItemCache.INSTANCE.amount(stack.getItem());
                long remaining = demand.remainingMissing(currentAmount);
                if (remaining > 0) {
                    drawLeftTop(graphics, font, "-" + format(remaining), x, y);
                }
            }
        }
    }

    private static boolean inventoryEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_JEI_NETWORK_OVERLAY
                : RSIntegrationConfig.ENABLE_JEI_NETWORK_OVERLAY.get();
    }

    private static boolean shortageEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_JEI_CRAFTING_SHORTAGE_OVERLAY
                : RSIntegrationConfig.ENABLE_JEI_CRAFTING_SHORTAGE_OVERLAY.get();
    }

    private static String format(long amount) {
        if (amount >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", amount / 1_000_000_000D);
        if (amount >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", amount / 1_000_000D);
        if (amount >= 1_000L) return String.format(Locale.ROOT, "%.1fK", amount / 1_000D);
        return Long.toString(amount);
    }

    private static void drawRightBottom(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x + 17, y + 16, 300);
        float scale = RSIntegrationConfig.JEI_NETWORK_OVERLAY_SCALE.get().floatValue();
        graphics.pose().scale(scale, scale, 1F);
        graphics.drawString(font, text, -font.width(text), -7, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static void drawLeftTop(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x + 1, y + 1, 301);
        float configured = RSIntegrationConfig.JEI_CRAFTING_SHORTAGE_OVERLAY_SCALE.get().floatValue();
        float scale = Math.min(configured, 16F / Math.max(1, font.width(text)));
        graphics.pose().scale(scale, scale, 1F);
        graphics.drawString(font, text, 0, 0, 0xFFFF5555, true);
        graphics.pose().popPose();
    }
}
