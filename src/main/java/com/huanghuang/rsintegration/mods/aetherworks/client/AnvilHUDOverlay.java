package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

@OnlyIn(Dist.CLIENT)
public final class AnvilHUDOverlay implements IGuiOverlay {

    public static final AnvilHUDOverlay INSTANCE = new AnvilHUDOverlay();

    private AnvilHUDOverlay() {}

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (mc.screen != null) return;

        BlockEntity anvil = AetherworksHelper.getTargetAnvil();
        if (anvil == null) return;

        ItemStack item = AetherworksHelper.getAnvilSlot(anvil, 0);
        Object recipe = AetherworksHelper.findRecipe(mc.level, item);
        BlockEntity forge = AetherworksHelper.findForge(mc.level, anvil.getBlockPos());

        Font font = mc.font;
        int x = w / 2 + 20;
        int y = h / 2 - 30;
        int color = 0xFFFFFF;
        int goodColor = 0x55FF55;
        int badColor = 0xFF5555;
        int warnColor = 0xFFFF55;
        int lineHeight = 12;
        int gap = 2;

        // Item name
        if (!item.isEmpty()) {
            graphics.drawString(font, item.getHoverName().getString(), x, y, 0xFFAA00);
        } else {
            graphics.drawString(font, Component.translatable("rsi.aetherworks.hud.empty"), x, y, 0xAAAAAA);
        }
        y += lineHeight + gap;

        if (recipe != null) {
            // Progress
            int hits = AetherworksHelper.getAnvilProgress(anvil);
            int total = AetherworksHelper.getRecipeHits(recipe);
            graphics.drawString(font,
                    Component.translatable("rsi.aetherworks.hud.progress", hits, total), x, y, color);
            y += lineHeight;

            // Temperature with recipe range
            if (forge != null) {
                double heat = AetherworksHelper.getForgeHeat(forge);
                int minT = AetherworksHelper.getRecipeTempMin(recipe);
                int maxT = AetherworksHelper.getRecipeTempMax(recipe);
                boolean tempOk = heat >= minT && heat <= maxT;
                graphics.drawString(font,
                        Component.literal(String.format("温度: %.0f°  [%d-%d°]", heat, minT, maxT)),
                        x, y, tempOk ? goodColor : badColor);
                y += lineHeight;

                // Ember
                double ember = AetherworksHelper.getForgeEmber(forge);
                double emberMax = AetherworksHelper.getForgeEmberCapacity(forge);
                int cost = AetherworksHelper.getRecipeEmberPerHit(recipe);
                boolean emberOk = ember >= cost;
                graphics.drawString(font,
                        Component.literal(String.format("余烬: %.0f/%.0f EM (消耗 %d/锤)", ember, emberMax, cost)),
                        x, y, emberOk ? goodColor : badColor);
                y += lineHeight;
            } else {
                graphics.drawString(font, Component.translatable("rsi.aetherworks.warn.no_forge"), x, y, warnColor);
                y += lineHeight;
            }

            // Mistakes
            int mistakes = AetherworksHelper.getAnvilMistakes(anvil);
            if (mistakes > 0) {
                int mcColor = mistakes >= 2 ? badColor : warnColor;
                graphics.drawString(font,
                        Component.translatable("rsi.aetherworks.hud.mistakes", mistakes, 3), x, y, mcColor);
                y += lineHeight;
            }
        } else if (!item.isEmpty()) {
            graphics.drawString(font, Component.translatable("rsi.aetherworks.hud.no_recipe"), x, y, badColor);
            y += lineHeight;
        }

        // Separator + temp range
        y += gap;
        graphics.drawString(font,
                String.format("温控: %d° - %d°", LeverBinder.getTempMin(), LeverBinder.getTempMax()),
                x, y, color);
        y += lineHeight;

        // Lever status
        if (LeverBinder.isBound()) {
            graphics.drawString(font, "● 拉杆已绑定", x, y, goodColor);
        } else {
            graphics.drawString(font, "○ 未绑定拉杆", x, y, 0xAAAAAA);
        }
        y += lineHeight;

        // Auto-refill
        boolean refillOn = LeverBinder.isAutoRefillEnabled();
        graphics.drawString(font, refillOn ? "[自动补货: 开]" : "[自动补货: 关]", x, y, refillOn ? goodColor : 0xAAAAAA);
        y += lineHeight;

        // Auto-hammer
        boolean autoOn = AetherworksClientSetup.isAutoHammerEnabled();
        graphics.drawString(font, autoOn ? "[自动锤炼: 开]" : "[自动锤炼: 关]", x, y, autoOn ? goodColor : 0xAAAAAA);
    }
}
