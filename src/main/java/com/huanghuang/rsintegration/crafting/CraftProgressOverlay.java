package com.huanghuang.rsintegration.crafting;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Right-side HUD overlay showing running craft progress.
 * Toggled by keybind; hidden entirely when no active craft or visibility off.
 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {

    private static final int PANEL_WIDTH = 146;
    private static final int PANEL_X_OFFSET = 8; // from right edge
    private static final int BAR_H = 10;
    private static final int BG_COLOR = 0xCC1A1A1A;
    private static final int BAR_BG = 0xFF333333;
    private static final int BAR_FILL = 0xFF4CAF50;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!CraftProgressTracker.hasActive() || !CraftProgressTracker.isVisible()) return;

        CraftProgressSnapshot snap = CraftProgressTracker.first();
        if (snap == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return; // hiding behind another screen? keep visible

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = screenW - PANEL_WIDTH - PANEL_X_OFFSET;
        int y = screenH / 2 - 50;
        int rowH = font.lineHeight + 4;

        // Compact panel
        int totalH = 8 + rowH + 6 + BAR_H + 6 + rowH + 8;
        RenderSystem.enableBlend();
        gfx.fill(x, y, x + PANEL_WIDTH, y + totalH, BG_COLOR);
        gfx.renderOutline(x, y, PANEL_WIDTH, totalH, 0xFF555555);

        int cy = y + 6;

        // Title
        String title = snap.isTerminal()
                ? (snap.failedStep() != null ? "Failed" : "Complete")
                : "Crafting";
        gfx.drawString(font, title, x + 6, cy, TITLE_COLOR);
        cy += rowH;

        // Progress bar
        float pct = snap.totalNodes() > 0
                ? (float) snap.completedNodes() / snap.totalNodes() : 0f;
        int barW = PANEL_WIDTH - 16;
        gfx.fill(x + 8, cy, x + 8 + barW, cy + BAR_H, BAR_BG);
        if (pct > 0) gfx.fill(x + 8, cy, x + 8 + (int) (barW * pct), cy + BAR_H, BAR_FILL);
        String pctText = (int) (pct * 100) + "%";
        gfx.drawCenteredString(font, pctText, x + PANEL_WIDTH / 2, cy + 1, TEXT_COLOR);
        cy += BAR_H + 6;

        // Detail line
        String detail;
        if (snap.isTerminal() && snap.failedStep() != null) {
            detail = snap.failedStep();
        } else {
            detail = snap.completedNodes() + "/" + snap.totalNodes() + " nodes";
            if (snap.runningNodes() > 0) detail += "  " + snap.runningNodes() + " running";
        }
        gfx.drawString(font, detail, x + 6, cy, TEXT_COLOR);

        RenderSystem.disableBlend();
    }
}
