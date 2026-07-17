package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.util.UIRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Locale;

/** Compact right-side HUD for the active server-authoritative craft. */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {

    private static final int MAX_PANEL_WIDTH = 214;
    private static final int MIN_PANEL_WIDTH = 158;
    private static final int SCREEN_MARGIN = 10;
    private static final int CONTENT_PAD = 11;
    private static final int BAR_HEIGHT = 6;

    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFF9DA9B1;
    private static final int DIM = 0xFF71808A;
    private static final int SURFACE = 0xB81B252B;
    private static final int BAR_BACKGROUND = 0xFF253139;
    private static final int DIVIDER = 0x443F505A;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!CraftProgressTracker.hasActive() || !CraftProgressTracker.isVisible()) return;

        CraftProgressSnapshot snapshot = CraftProgressTracker.first();
        if (snapshot == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen instanceof CraftProgressScreen) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int panelWidth = Math.max(MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, screenWidth - SCREEN_MARGIN * 2));
        int innerWidth = panelWidth - CONTENT_PAD * 2;
        int accent = accent(snapshot.result());
        int percent = progressPercent(snapshot);
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        List<FormattedCharSequence> detailLines = limitedLines(font, detail(snapshot), innerWidth, 2);
        CraftProgressPresentation.Selection current =
                CraftProgressPresentation.currentNodes(snapshot, 2);

        int headerHeight = target.isEmpty() ? 27 : 34;
        int progressHeight = 33;
        int detailHeight = detailLines.size() * (font.lineHeight + 1) + 8;
        int stepsHeight = current.nodes().isEmpty() ? 0
                : 16 + current.nodes().size() * 42 + (current.remaining() > 0 ? 11 : 0);
        int footerHeight = cancellable(snapshot.result()) ? 22 : 8;
        int panelHeight = 9 + headerHeight + progressHeight + detailHeight + stepsHeight + footerHeight;

        int x = Math.max(SCREEN_MARGIN, screenWidth - panelWidth - SCREEN_MARGIN);
        int maxY = Math.max(SCREEN_MARGIN, screenHeight - panelHeight - 34);
        int y = Math.max(SCREEN_MARGIN + 18, Math.min(screenHeight / 2 - panelHeight / 2, maxY));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        UIRenderer.roundedGradient(graphics, x, y, panelWidth, panelHeight, 7f,
                0xE81B252B, 0xE8121A1F);
        graphics.fill(x + 1, y + 6, x + 4, y + panelHeight - 6, accent);
        graphics.fill(x + 5, y + panelHeight - 2, x + panelWidth - 6,
                y + panelHeight - 1, 0x33000000);

        int contentX = x + CONTENT_PAD;
        int right = x + panelWidth - CONTENT_PAD;
        int cy = y + 9;
        if (!target.isEmpty()) {
            UIRenderer.slotBg(graphics, contentX, cy, 20, 0xFF42535D);
            graphics.renderItem(target, contentX + 2, cy + 2);
            graphics.renderItemDecorations(font, target, contentX + 2, cy + 2);
        }
        int textX = contentX + (target.isEmpty() ? 0 : 28);
        String targetName = target.isEmpty()
                ? Component.translatable("rsi.progress.target_unknown").getString()
                : target.getHoverName().getString();
        graphics.drawString(font, font.plainSubstrByWidth(targetName, right - textX),
                textX, cy + 1, TEXT, true);
        graphics.drawString(font, title(snapshot.result()), textX, cy + 14, accent, false);
        cy += headerHeight;

        UIRenderer.rounded(graphics, contentX, cy, innerWidth, BAR_HEIGHT,
                BAR_HEIGHT / 2f, BAR_BACKGROUND);
        int fillWidth = Math.round(innerWidth * percent / 100f);
        if (fillWidth > 0) {
            UIRenderer.rounded(graphics, contentX, cy, fillWidth, BAR_HEIGHT,
                    BAR_HEIGHT / 2f, accent);
        }
        cy += BAR_HEIGHT + 6;
        graphics.drawString(font, Component.translatable("rsi.progress.summary",
                snapshot.completedNodes(), snapshot.totalNodes(), snapshot.runningNodes()),
                contentX, cy, MUTED, false);
        String percentText = percent + "%";
        graphics.drawString(font, percentText, right - font.width(percentText), cy, accent, true);
        cy += font.lineHeight + 7;

        graphics.fill(contentX, cy, right, cy + 1, DIVIDER);
        cy += 6;
        for (FormattedCharSequence line : detailLines) {
            graphics.drawString(font, line, contentX, cy, MUTED, false);
            cy += font.lineHeight + 1;
        }
        cy += 2;

        if (!current.nodes().isEmpty()) {
            graphics.drawString(font, Component.translatable("rsi.progress.step.current"),
                    contentX, cy, TEXT, true);
            cy += 15;
            for (CraftProgressSnapshot.NodeProgress node : current.nodes()) {
                renderCurrentStep(graphics, font, node, contentX, cy, innerWidth);
                cy += 42;
            }
            if (current.remaining() > 0) {
                graphics.drawString(font, Component.translatable("rsi.progress.step.more",
                        current.remaining()), contentX + 3, cy, DIM, false);
                cy += 11;
            }
        }

        if (cancellable(snapshot.result())) {
            graphics.fill(contentX, cy, right, cy + 1, DIVIDER);
            cy += 7;
            Component hint = Component.translatable("rsi.progress.manage_hint");
            graphics.drawString(font, hint, contentX, cy, DIM, false);
            UIRenderer.pillBadge(graphics, font, right - 20, cy - 3, 20, 15,
                    0x663C5664, 0xFFD9E4EA, "P");
        }
        RenderSystem.disableBlend();
    }

    private static void renderCurrentStep(GuiGraphics graphics, Font font,
                                          CraftProgressSnapshot.NodeProgress node,
                                          int x, int y, int width) {
        int color = nodeColor(node);
        UIRenderer.rounded(graphics, x, y, width, 38, 5f, SURFACE);
        graphics.fill(x, y + 5, x + 3, y + 33, color);
        ItemStack output = node.displayOutput();
        if (!output.isEmpty()) graphics.renderItem(output, x + 7, y + 5);
        int textX = x + (output.isEmpty() ? 8 : 28);
        int available = Math.max(24, x + width - 7 - textX);
        graphics.drawString(font, font.plainSubstrByWidth(
                CraftProgressPresentation.outputName(node).getString(), available),
                textX, y + 4, TEXT, false);
        Component state = CraftProgressPresentation.state(node);
        Component machine = CraftProgressPresentation.machine(node);
        graphics.drawString(font, font.plainSubstrByWidth(state.getString(), available),
                textX, y + 15, color, false);
        graphics.drawString(font, font.plainSubstrByWidth(machine.getString(), available),
                textX, y + 26, MUTED, false);
    }

    private static int nodeColor(CraftProgressSnapshot.NodeProgress node) {
        if (node.draining()) return 0xFFB89AD7;
        return switch (node.state()) {
            case RUNNING -> 0xFF68A9E8;
            case FAILED -> 0xFFE06C75;
            case READY -> 0xFFE0B35A;
            case BLOCKED -> 0xFF929DA5;
            case SUCCEEDED -> 0xFF67BE7B;
            case CANCELLED, UNKNOWN -> 0xFF71808A;
        };
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
}
