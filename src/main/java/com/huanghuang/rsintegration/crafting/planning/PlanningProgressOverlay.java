package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftProgressScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/** Compact actionbar-position panel for plan generation; it has no fake percentage. */
@OnlyIn(Dist.CLIENT)
public final class PlanningProgressOverlay {
    static final int PANEL_WIDTH = 196;
    static final int PANEL_HEIGHT = 27;
    static final int PANEL_Z = 500;
    static final int PANEL_BACKGROUND = 0xFF000000;
    static final int PANEL_BORDER = 0xFF30383D;
    private static final int SCREEN_MARGIN = 10;
    private static final int PAD = 9;
    private static final int BAR_HEIGHT = 3;
    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFFA8B2B9;
    private static final int TRACK = 0xFF26343C;

    private PlanningProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) return;
        render(event.getGuiGraphics(), minecraft);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderScreen(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getScreen() instanceof CraftProgressScreen) return;
        render(event.getGuiGraphics(), minecraft);
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft) {
        PlanningProgressSnapshot snapshot = PlanningProgressTracker.current();
        if (snapshot == null) return;

        if (minecraft.player == null) return;
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int width = Math.min(PANEL_WIDTH, Math.max(132, screenWidth - SCREEN_MARGIN * 2));
        int x = Math.max(SCREEN_MARGIN, (screenWidth - width) / 2);
        int y = panelY(screenHeight);
        int accent = accent(snapshot.state());

        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, PANEL_Z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        graphics.fill(x, y, x + width, y + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + PANEL_HEIGHT - 1,
                PANEL_BACKGROUND);
        graphics.fill(x + 1, y + 1, x + 4, y + PANEL_HEIGHT - 1, accent);

        int left = x + PAD;
        int right = x + width - PAD;
        Component title = Component.translatable(titleKey(snapshot.state()));
        String elapsed = formatElapsed(PlanningProgressTracker.elapsedMillis());
        graphics.drawString(font, elapsed, right - font.width(elapsed), y + 6, MUTED, false);

        Component detail = snapshot.terminal() && !snapshot.detail().getString().isBlank()
                ? snapshot.detail() : Component.translatable(phaseKey(snapshot.phase()));
        String summary = title.getString() + " · " + detail.getString();
        int summaryWidth = Math.max(24, right - left - font.width(elapsed) - 7);
        graphics.drawString(font, font.plainSubstrByWidth(summary, summaryWidth),
                left, y + 6, snapshot.terminal() ? accent : TEXT, true);

        int barY = y + 20;
        int barWidth = Math.max(1, right - left);
        graphics.fill(left, barY, left + barWidth, barY + BAR_HEIGHT, TRACK);
        if (snapshot.terminal()) {
            graphics.fill(left, barY, left + barWidth, barY + BAR_HEIGHT, accent);
        } else {
            renderIndeterminateBar(graphics, left, barY, barWidth, accent,
                    PlanningProgressTracker.elapsedMillis());
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        graphics.pose().popPose();
    }

    private static void renderIndeterminateBar(GuiGraphics graphics, int x, int y, int width,
                                               int accent, long elapsedMillis) {
        int sweepWidth = Math.max(22, width / 3);
        int travel = width + sweepWidth;
        int sweepX = x - sweepWidth + (int) ((elapsedMillis / 7L) % travel);
        graphics.enableScissor(x, y, x + width, y + BAR_HEIGHT);
        graphics.fill(sweepX, y, sweepX + sweepWidth, y + BAR_HEIGHT, accent);
        graphics.disableScissor();
    }

    static int accent(PlanningProgressSnapshot.State state) {
        return switch (state) {
            case ACCEPTED, WARMING_UP, QUEUED -> 0xFFE0B35A;
            case RUNNING -> 0xFF68A9E8;
            case FINALIZING, SUCCEEDED -> 0xFF67BE7B;
            case FAILED, TIMED_OUT -> 0xFFE06C75;
            case CANCELLED, STALE -> 0xFF929DA5;
        };
    }

    static String titleKey(PlanningProgressSnapshot.State state) {
        return "rsi.planning.state." + state.name().toLowerCase(Locale.ROOT);
    }

    static String phaseKey(PlanningProgressSnapshot.Phase phase) {
        return "rsi.planning.phase." + phase.name().toLowerCase(Locale.ROOT);
    }

    static String formatElapsed(long elapsedMillis) {
        if (elapsedMillis < 10_000L) {
            return String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1_000.0);
        }
        return (elapsedMillis / 1_000L) + "s";
    }

    static int panelY(int screenHeight) {
        return Math.max(SCREEN_MARGIN, screenHeight - PANEL_HEIGHT - 65);
    }
}
