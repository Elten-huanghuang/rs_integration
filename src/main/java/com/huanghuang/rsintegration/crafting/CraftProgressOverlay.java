package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.util.UIRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compact right-side HUD for the active server-authoritative craft. */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {

    private static final int MAX_PANEL_WIDTH = 184;
    private static final int MIN_PANEL_WIDTH = 132;
    private static final int SCREEN_MARGIN = 10;
    private static final int CONTENT_PAD = 10;
    private static final int BAR_HEIGHT = 8;
    private static final int BUTTON_WIDTH = 72;
    private static final int BUTTON_HEIGHT = 15;

    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFFABB5BC;
    private static final int BAR_BACKGROUND = 0xB8222C32;
    private static final int BUTTON_TEXT = 0xFFF5EDEE;

    private static int cancelBtnX;
    private static int cancelBtnY;
    private static int cancelBtnW;
    private static int cancelBtnH;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!CraftProgressTracker.hasActive() || !CraftProgressTracker.isVisible()) return;

        CraftProgressSnapshot snapshot = CraftProgressTracker.first();
        if (snapshot == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int panelWidth = Math.max(MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, screenWidth - SCREEN_MARGIN * 2));
        int textWidth = panelWidth - CONTENT_PAD * 2;

        Component title = title(snapshot.result());
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        Component detail = detail(snapshot);
        List<FormattedCharSequence> detailLines = limitedLines(font, detail, textWidth, 2);
        Component nodeSummary = nodeSummary(snapshot);
        boolean showNodeSummary = nodeSummary != null;
        boolean cancellable = cancellable(snapshot.result());

        int lineHeight = font.lineHeight;
        int panelHeight = 10 + lineHeight + (!target.isEmpty() ? lineHeight + 6 : 0)
                + 7 + BAR_HEIGHT + 7 + lineHeight
                + detailLines.size() * (lineHeight + 1)
                + (showNodeSummary ? lineHeight + 3 : 0)
                + (cancellable ? lineHeight + 9 : 3) + 7;
        int x = Math.max(SCREEN_MARGIN, screenWidth - panelWidth - SCREEN_MARGIN);
        int maxY = Math.max(SCREEN_MARGIN, screenHeight - panelHeight - 34);
        int y = Math.max(SCREEN_MARGIN + 18, Math.min(screenHeight / 2 - panelHeight / 2, maxY));

        int accent = accent(snapshot.result());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        UIRenderer.card(graphics, x, y, panelWidth, panelHeight, 6f, accent);

        int contentX = x + CONTENT_PAD;
        int cy = y + 7;
        int percent = progressPercent(snapshot);
        graphics.drawString(font, title, contentX, cy, TEXT, true);
        String percentText = percent + "%";
        graphics.drawString(font, percentText,
                x + panelWidth - CONTENT_PAD - font.width(percentText), cy, accent, true);
        cy += lineHeight + 4;
        if (!target.isEmpty()) {
            graphics.renderItem(target, contentX, cy - 2);
            Component targetName = target.getHoverName().copy().append(
                    target.getCount() > 1 ? Component.literal(" ×" + target.getCount()) : Component.empty());
            graphics.drawString(font, targetName, contentX + 20, cy + 2, TEXT, false);
            cy += lineHeight + 6;
        }
        cy += 3;

        UIRenderer.rounded(graphics, contentX, cy, textWidth, BAR_HEIGHT,
                BAR_HEIGHT / 2f, BAR_BACKGROUND);
        int fillWidth = Math.round(textWidth * (percent / 100f));
        if (fillWidth > 0) {
            UIRenderer.rounded(graphics, contentX, cy, fillWidth, BAR_HEIGHT,
                    BAR_HEIGHT / 2f, accent);
        }
        cy += BAR_HEIGHT + 7;

        Component summary = Component.translatable("rsi.progress.summary",
                snapshot.completedNodes(), snapshot.totalNodes(), snapshot.runningNodes());
        graphics.drawString(font, summary, contentX, cy, TEXT, false);
        cy += lineHeight + 3;

        for (FormattedCharSequence line : detailLines) {
            graphics.drawString(font, line, contentX, cy, MUTED, false);
            cy += lineHeight + 1;
        }
        if (showNodeSummary) {
            cy += 1;
            graphics.drawString(font, nodeSummary, contentX, cy, MUTED, false);
            cy += lineHeight + 2;
        }

        if (cancellable) {
            cy += 3;
            graphics.drawString(font, Component.translatable("rsi.progress.manage_hint"),
                    contentX, cy, MUTED, false);
        }
        clearCancelHitbox();
        RenderSystem.disableBlend();
    }

    static Component title(CraftProgressSnapshot.Result result) {
        return Component.translatable(titleKey(result));
    }

    static String titleKey(CraftProgressSnapshot.Result result) {
        return "rsi.progress.status." + result.name().toLowerCase(Locale.ROOT);
    }

    static int accent(CraftProgressSnapshot.Result result) {
        return switch (result) {
            case RUNNING -> 0xFF68A9E8;
            case WAITING -> 0xFFE0B35A;
            case STOPPING -> 0xFFB89AD7;
            case SUCCEEDED -> 0xFF67BE7B;
            case FAILED -> 0xFFE06C75;
            case CANCELLED -> 0xFF929DA5;
        };
    }

    static boolean cancellable(CraftProgressSnapshot.Result result) {
        return result == CraftProgressSnapshot.Result.RUNNING
                || result == CraftProgressSnapshot.Result.WAITING;
    }

    static Component detail(CraftProgressSnapshot snapshot) {
        CraftProgressSnapshot.Reason reason = snapshot.reason();
        if (reason == CraftProgressSnapshot.Reason.NONE) {
            for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
                if (node.reason() != CraftProgressSnapshot.Reason.NONE) {
                    reason = node.reason();
                    break;
                }
            }
        }
        if (reason != CraftProgressSnapshot.Reason.NONE) {
            return Component.translatable(reason.translationKey());
        }
        return Component.translatable(switch (snapshot.result()) {
            case FAILED -> "rsi.progress.reason.failed_unspecified";
            case CANCELLED -> "rsi.progress.reason.player_cancelled";
            case SUCCEEDED -> "rsi.progress.status.succeeded";
            case STOPPING -> "rsi.progress.status.stopping";
            case WAITING -> "rsi.progress.reason.waiting_materials";
            case RUNNING -> "rsi.progress.reason.none";
        });
    }

    private static Component nodeSummary(CraftProgressSnapshot snapshot) {
        if (snapshot.nodes().isEmpty()) return null;
        int blocked = 0;
        int ready = 0;
        int draining = 0;
        int completedOperations = 0;
        int totalOperations = 0;
        for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
            if (node.state() == CraftProgressSnapshot.NodeState.BLOCKED) blocked++;
            if (node.state() == CraftProgressSnapshot.NodeState.READY) ready++;
            if (node.draining()) draining++;
            completedOperations += node.completedOperations();
            totalOperations += node.totalOperations();
        }
        List<Component> parts = new ArrayList<>();
        if (blocked > 0) parts.add(Component.translatable("rsi.progress.badge.blocked", blocked));
        if (ready > 0) parts.add(Component.translatable("rsi.progress.badge.ready", ready));
        if (draining > 0) parts.add(Component.translatable("rsi.progress.badge.draining", draining));
        if (totalOperations > 1) {
            parts.add(Component.translatable("rsi.progress.summary_operations",
                    completedOperations, totalOperations));
        }
        if (parts.isEmpty()) return null;
        Component result = parts.get(0).copy();
        for (int i = 1; i < parts.size(); i++) {
            result = result.copy().append(Component.literal(" · ")).append(parts.get(i));
        }
        return result;
    }

    private static List<FormattedCharSequence> limitedLines(Font font, Component component,
                                                              int width, int limit) {
        List<FormattedCharSequence> lines = font.split(component, width);
        if (lines.size() <= limit) return lines;
        return List.copyOf(lines.subList(0, limit));
    }

    static int progressPercent(CraftProgressSnapshot snapshot) {
        if (snapshot.result() == CraftProgressSnapshot.Result.SUCCEEDED) return 100;
        if (snapshot.totalNodes() <= 0) return 0;
        return Math.max(0, Math.min(99,
                Math.round(snapshot.completedNodes() * 100f / snapshot.totalNodes())));
    }

    private static boolean isMouseOver(Minecraft minecraft, int screenWidth, int screenHeight,
                                       int x, int y, int width, int height) {
        int mouseX = (int) minecraft.mouseHandler.xpos() * screenWidth
                / minecraft.getWindow().getScreenWidth();
        int mouseY = (int) minecraft.mouseHandler.ypos() * screenHeight
                / minecraft.getWindow().getScreenHeight();
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static void clearCancelHitbox() {
        cancelBtnX = 0;
        cancelBtnY = 0;
        cancelBtnW = 0;
        cancelBtnH = 0;
    }

}
