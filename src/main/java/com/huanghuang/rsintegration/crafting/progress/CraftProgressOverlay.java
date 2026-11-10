package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.util.UIRenderer;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/** 常驻合成摘要；步骤、机器位置和管理操作保留在 P 界面。 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {

    private static final int MAX_PANEL_WIDTH = 176;
    private static final int MIN_PANEL_WIDTH = 96;
    private static final int SCREEN_MARGIN = 8;
    private static final int RIGHT_MARGIN = 4;
    private static final int CONTENT_PAD = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_GAP = 4;
    private static final int BAR_HEIGHT = 3;
    private static final int MIN_BAR_WIDTH = 24;

    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFF9DA9B1;
    private static final int BAR_BACKGROUND = 0xFF253139;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!CraftProgressTracker.hasActive() || !CraftProgressTracker.isVisible()) return;

        CraftProgressSnapshot snapshot = CraftProgressTracker.first();
        if (snapshot == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int accent = accent(snapshot.result());
        int percent = hudProgressPercent(snapshot);
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        String targetName = target.isEmpty()
                ? Component.translatable("rsi.progress.target_unknown").getString()
                : target.getHoverName().getString();
        String statusText = title(snapshot.result()).getString();
        String quantityText = hudQuantity(snapshot, target);
        String progressText = hudProgress(snapshot).getString();
        String detailText = hudDetail(snapshot).getString();
        String hintText = hudHint(CraftProgressKeybind.translatedKeyMessage()).getString();
        int quantityWidth = font.width(quantityText);
        int headerWidth = font.width(targetName) + (target.isEmpty() ? 0 : 22)
                + (quantityWidth == 0 ? 0 : quantityWidth + ROW_GAP);
        int contentWidth = Math.max(headerWidth, Math.max(
                font.width(statusText) + (progressText.isEmpty() ? 0 : font.width(progressText) + 6),
                font.width(hintText) + MIN_BAR_WIDTH + ROW_GAP));
        HudLayout layout = layout(screenWidth, screenHeight, contentWidth,
                font.lineHeight, !detailText.isBlank());
        if (!layout.visible()) return;
        int x = layout.x();
        int y = layout.y();
        int innerWidth = layout.width() - CONTENT_PAD * 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        UIRenderer.roundedGradient(graphics, x, y, layout.width(), layout.height(), 5f,
                0xE81B252B, 0xE8121A1F);
        graphics.fill(x + 1, y + 6, x + 4, y + layout.height() - 6, accent);

        int contentX = x + CONTENT_PAD;
        int right = x + layout.width() - CONTENT_PAD;
        int cy = y + CONTENT_PAD;
        if (!target.isEmpty()) {
            InkFluidRenderer.render(graphics, target, contentX, cy);
        }
        int textX = contentX + (target.isEmpty() ? 0 : 22);
        int textY = cy + Math.max(0, (HEADER_HEIGHT - font.lineHeight) / 2);
        // 名称过长时只截短名称，始终为目标数量保留空间。
        int nameWidth = right - textX - (quantityWidth == 0 ? 0 : quantityWidth + ROW_GAP);
        graphics.drawString(font, fitText(font, targetName, nameWidth), textX, textY, TEXT, true);
        if (quantityWidth > 0) {
            graphics.drawString(font, quantityText, right - quantityWidth, textY, TEXT, true);
        }
        cy += Math.max(HEADER_HEIGHT, font.lineHeight) + ROW_GAP;

        // 目标数量与步骤/操作进度分开显示，避免混用数量口径。
        int progressWidthLimit = Math.max(0,
                innerWidth - Math.min(font.width(statusText), innerWidth / 2) - 6);
        progressText = fitText(font, progressText, progressWidthLimit);
        int progressWidth = font.width(progressText);
        int statusWidth = innerWidth - (progressWidth == 0 ? 0 : progressWidth + 6);
        graphics.drawString(font, fitText(font, statusText, statusWidth), contentX, cy, accent, false);
        if (progressWidth > 0) {
            graphics.drawString(font, progressText, right - progressWidth, cy, MUTED, false);
        }
        cy += font.lineHeight + ROW_GAP;

        if (!detailText.isBlank()) {
            graphics.drawString(font, fitText(font, detailText, innerWidth), contentX, cy, MUTED, false);
            cy += font.lineHeight + ROW_GAP;
        }

        // 按键提示与进度条共用底行，保留入口而不另加一整行。
        hintText = fitText(font, hintText, innerWidth - MIN_BAR_WIDTH - ROW_GAP);
        int hintWidth = font.width(hintText);
        int barWidth = innerWidth - (hintWidth == 0 ? 0 : hintWidth + ROW_GAP);
        int barY = cy + Math.max(0, (font.lineHeight - BAR_HEIGHT) / 2);
        UIRenderer.rounded(graphics, contentX, barY, barWidth, BAR_HEIGHT,
                BAR_HEIGHT / 2f, BAR_BACKGROUND);
        int fillWidth = Math.round(barWidth * percent / 100f);
        if (fillWidth > 0) {
            UIRenderer.rounded(graphics, contentX, barY, fillWidth, BAR_HEIGHT,
                    BAR_HEIGHT / 2f, accent);
        }
        graphics.drawString(font, hintText, right - hintWidth, cy, MUTED, false);
        RenderSystem.disableBlend();
    }

    static HudLayout layout(int screenWidth, int screenHeight, int contentWidth,
                            int lineHeight, boolean hasDetail) {
        int width = Math.min(Math.max(0, screenWidth - SCREEN_MARGIN - RIGHT_MARGIN),
                Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, contentWidth + CONTENT_PAD * 2)));
        int height = CONTENT_PAD * 2 + Math.max(HEADER_HEIGHT, lineHeight)
                + ROW_GAP + lineHeight + ROW_GAP + Math.max(BAR_HEIGHT, lineHeight)
                + (hasDetail ? lineHeight + ROW_GAP : 0);
        int x = Math.max(0, screenWidth - RIGHT_MARGIN - width);
        int y = Math.max(SCREEN_MARGIN,
                Math.min((screenHeight - height) / 2, screenHeight - SCREEN_MARGIN - height));
        boolean visible = width >= 96 && height <= screenHeight - SCREEN_MARGIN * 2;
        return new HudLayout(x, y, width, height, visible);
    }

    record HudLayout(int x, int y, int width, int height, boolean visible) {}

    private static String fitText(Font font, String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        String ellipsis = "…";
        int available = width - font.width(ellipsis);
        return available < 0 ? "" : font.plainSubstrByWidth(text, available) + ellipsis;
    }

    static String hudQuantity(ItemStack target) {
        return target.isEmpty() ? "" : InkFluidRenderer.compactQuantity(target, target.getCount());
    }

    static String hudQuantity(CraftProgressSnapshot snapshot, ItemStack target) {
        CraftOutputProgress progress = snapshot.outputProgress();
        if (!progress.known()) return hudQuantity(target);
        return CompactQuantityFormat.format(progress.completed()) + "/"
                + CompactQuantityFormat.format(progress.total())
                + (InkFluidSupport.isToken(target) ? " mB" : "");
    }

    static int hudProgressPercent(CraftProgressSnapshot snapshot) {
        CraftOutputProgress progress = snapshot.outputProgress();
        if (!progress.known()) return progressPercent(snapshot);
        int percent = (int) (progress.completed() * 100.0 / progress.total());
        return Math.min(snapshot.result() == CraftProgressSnapshot.Result.SUCCEEDED ? 100 : 99, percent);
    }

    static Component hudHint(Component key) {
        return Component.translatable("rsi.progress.hud.hint", key);
    }

    static Component hudProgress(CraftProgressSnapshot snapshot) {
        if (snapshot.totalNodes() > 1) {
            return Component.translatable("rsi.progress.hud.steps",
                    snapshot.completedNodes(), snapshot.totalNodes());
        }
        if (!snapshot.outputProgress().known() && snapshot.nodes().size() == 1) {
            CraftProgressSnapshot.NodeProgress node = snapshot.nodes().get(0);
            if (node.totalOperations() > 1) {
                return Component.translatable("rsi.progress.summary_operations",
                        node.completedOperations(), node.totalOperations());
            }
        }
        return Component.empty();
    }

    static Component hudDetail(CraftProgressSnapshot snapshot) {
        if (snapshot.result() == CraftProgressSnapshot.Result.FAILED
                || snapshot.result() == CraftProgressSnapshot.Result.WAITING) {
            return detail(snapshot);
        }
        if (snapshot.result() != CraftProgressSnapshot.Result.RUNNING) return Component.empty();
        if (specificReason(snapshot.reason())) {
            return Component.translatable(snapshot.reason().translationKey());
        }
        for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
            if (node.state() != CraftProgressSnapshot.NodeState.SUCCEEDED
                    && node.state() != CraftProgressSnapshot.NodeState.CANCELLED
                    && specificReason(node.reason())) {
                return Component.translatable(node.reason().translationKey());
            }
        }
        return Component.empty();
    }

    private static boolean specificReason(CraftProgressSnapshot.Reason reason) {
        return reason != CraftProgressSnapshot.Reason.NONE && reason != CraftProgressSnapshot.Reason.UNKNOWN;
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
        if (snapshot.result() == CraftProgressSnapshot.Result.FAILED
                && (reason == CraftProgressSnapshot.Reason.NONE || reason == CraftProgressSnapshot.Reason.UNKNOWN)) {
            for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
                if (node.state() == CraftProgressSnapshot.NodeState.FAILED
                        && node.reason() != CraftProgressSnapshot.Reason.NONE
                        && node.reason() != CraftProgressSnapshot.Reason.UNKNOWN) {
                    return Component.translatable(node.reason().translationKey());
                }
            }
            return Component.translatable("rsi.progress.reason.failed_unspecified");
        }
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

    static int progressPercent(CraftProgressSnapshot snapshot) {
        if (snapshot.result() == CraftProgressSnapshot.Result.SUCCEEDED) return 100;
        if (snapshot.totalNodes() <= 0) return 0;
        return Math.max(0, Math.min(99,
                Math.round(snapshot.completedNodes() * 100f / snapshot.totalNodes())));
    }
}
