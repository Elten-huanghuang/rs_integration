package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftCancelPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Right-side HUD overlay showing running craft progress with a Cancel button.
 * Toggled by keybind; hidden entirely when no active craft or visibility off.
 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {

    private static final int PANEL_WIDTH = 146;
    private static final int PANEL_X_OFFSET = 8;
    private static final int BAR_H = 10;
    private static final int BTN_H = 16;
    private static final int BG_COLOR = 0xCC1A1A1A;
    private static final int BAR_BG = 0xFF333333;
    private static final int BAR_FILL = 0xFF4CAF50;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int CANCEL_BG = 0xFF994444;
    private static final int CANCEL_HOVER = 0xFFCC5555;

    // Hitbox for the cancel button — updated every render, read on mouse click
    private static int cancelBtnX, cancelBtnY, cancelBtnW, cancelBtnH;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!CraftProgressTracker.hasActive() || !CraftProgressTracker.isVisible()) return;

        CraftProgressSnapshot snap = CraftProgressTracker.first();
        if (snap == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = screenW - PANEL_WIDTH - PANEL_X_OFFSET;
        int y = screenH / 2 - 50;
        int rowH = font.lineHeight + 4;
        boolean terminal = snap.isTerminal() || snap.failedStep() != null
                || snap.chainState() == CraftProgressSnapshot.STATE_STOPPING;
        boolean hasCancel = !terminal;

        int totalH = 8 + rowH + 6 + BAR_H + 6 + rowH + (hasCancel ? 8 + BTN_H : 0) + 8;
        RenderSystem.enableBlend();
        gfx.fill(x, y, x + PANEL_WIDTH, y + totalH, BG_COLOR);
        gfx.renderOutline(x, y, PANEL_WIDTH, totalH, 0xFF555555);

        int cy = y + 6;

        // Title
        String title = terminal
                ? (snap.failedStep() != null ? "Failed" : "Complete")
                : "Crafting";
        gfx.drawString(font, title, x + 6, cy, TITLE_COLOR);
        cy += rowH;

        // Progress bar
        float pct = snap.totalNodes() > 0
                ? (float) snap.completedNodes() / snap.totalNodes() : 0f;
        int barW = PANEL_WIDTH - 16;
        gfx.fill(x + 8, cy, x + 8 + barW, cy + BAR_H, BAR_BG);
        if (pct > 0) gfx.fill(x + 8, cy, x + 8 + (int) (barW * pct), cy + BAR_H,
                terminal ? 0xFF888888 : BAR_FILL);
        String pctText = (int) (pct * 100) + "%";
        gfx.drawCenteredString(font, pctText, x + PANEL_WIDTH / 2, cy + 1, TEXT_COLOR);
        cy += BAR_H + 6;

        // Detail line
        String detail;
        if (terminal && snap.failedStep() != null) {
            detail = snap.failedStep();
        } else if (terminal) {
            detail = snap.completedNodes() + "/" + snap.totalNodes() + " nodes — done";
        } else {
            detail = snap.completedNodes() + "/" + snap.totalNodes() + " nodes";
            if (snap.runningNodes() > 0) detail += "  " + snap.runningNodes() + " running";
        }
        gfx.drawString(font, detail, x + 6, cy, TEXT_COLOR);
        cy += rowH;

        // Cancel button (only when not terminal)
        if (hasCancel) {
            cy += 4;
            cancelBtnX = x + 12;
            cancelBtnY = cy;
            cancelBtnW = PANEL_WIDTH - 24;
            cancelBtnH = BTN_H;

            int mouseX = (int) mc.mouseHandler.xpos() * screenW / mc.getWindow().getScreenWidth();
            int mouseY = (int) mc.mouseHandler.ypos() * screenH / mc.getWindow().getScreenHeight();
            boolean hover = mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW
                    && mouseY >= cancelBtnY && mouseY <= cancelBtnY + cancelBtnH;
            int bg = hover ? CANCEL_HOVER : CANCEL_BG;
            gfx.fill(cancelBtnX, cancelBtnY, cancelBtnX + cancelBtnW, cancelBtnY + cancelBtnH, bg);
            String label = "Cancel";
            gfx.drawCenteredString(font, label,
                    cancelBtnX + cancelBtnW / 2, cancelBtnY + (BTN_H - font.lineHeight) / 2, 0xFFFFFFFF);
        } else {
            cancelBtnX = cancelBtnY = cancelBtnW = cancelBtnH = 0;
        }

        RenderSystem.disableBlend();
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton event) {
        if (event.getAction() != GLFW.GLFW_PRESS || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (cancelBtnW <= 0 || cancelBtnH <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!CraftProgressTracker.hasActive()) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int mouseX = (int) mc.mouseHandler.xpos() * screenW / mc.getWindow().getScreenWidth();
        int mouseY = (int) mc.mouseHandler.ypos() * screenH / mc.getWindow().getScreenHeight();

        if (mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW
                && mouseY >= cancelBtnY && mouseY <= cancelBtnY + cancelBtnH) {
            CraftProgressSnapshot snap = CraftProgressTracker.first();
            if (snap != null && !snap.isTerminal()) {
                BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new CraftCancelPacket(snap.craftId()));
                CraftProgressTracker.remove(snap.craftId());
            }
        }
    }
}
