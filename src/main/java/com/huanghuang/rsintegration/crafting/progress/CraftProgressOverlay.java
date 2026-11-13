package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.planning.PlanningHudLayout;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressOverlay;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;

import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/** 常驻合成摘要；步骤、机器位置和管理操作保留在 P 界面。 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressOverlay {
    private static final String JEI_RECIPE_SCREEN = "mezz.jei.gui.recipes.RecipesGui";

    private static final int SCREEN_MARGIN = 8;
    private static final int CONTENT_PAD = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_GAP = 4;
    private static final int BAR_HEIGHT = 3;

    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFF9DA9B1;
    private static final int BAR_BACKGROUND = 0xFF253139;

    private CraftProgressOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) return;
        render(minecraft, event.getGuiGraphics());
    }

    /** JEI 配方屏幕在游戏 HUD 之后绘制，进度摘要需排在 JEI 屏幕之上。 */
    @SubscribeEvent
    public static void onRecipeScreenRender(ScreenEvent.Render.Post event) {
        if (!isRecipeViewerScreen(event.getScreen())) return;
        render(Minecraft.getInstance(), event.getGuiGraphics());
    }

    static boolean isRecipeViewerScreen(Screen screen) {
        for (Class<?> type = screen == null ? null : screen.getClass(); type != null; type = type.getSuperclass()) {
            if (JEI_RECIPE_SCREEN.equals(type.getName())) return true;
        }
        return false;
    }

    private static void render(Minecraft minecraft, GuiGraphics graphics) {
        if (minecraft.player == null || minecraft.options.hideGui) return;
        boolean planningVisible = PlanningProgressOverlay.isVisible();
        var planning = planningVisible ? PlanningProgressTracker.current() : null;
        int plans = planningVisible ? PlanningTasksScreen.taskCount() : 0;
        var execution = CraftProgressTracker.isVisible() ? CraftProgressTracker.first() : null;
        int running = (int) CraftProgressTracker.snapshots().stream().filter(task -> !task.isTerminal()).count();
        if (planning == null && plans == 0 && execution == null) return;
        Font font = minecraft.font;
        boolean hasDetail = execution != null && !hudDetail(execution).getString().isBlank();
        var layout = combinedLayout(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(),
                font.lineHeight, planning != null, execution != null, hasDetail);
        if (!layout.visible()) return;
        int x = layout.x(), y = layout.y(), right = x + layout.width() - CONTENT_PAD;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.fill(x, y, x + layout.width(), y + layout.height(), 0xFF596277);
        graphics.fill(x + 1, y + 1, x + layout.width() - 1, y + layout.height() - 1, 0xE5242937);
        int cy = y + CONTENT_PAD;
        if (planning != null) {
            int color = PlanningProgressOverlay.accent(planning.state());
            String text = Component.translatable(PlanningProgressOverlay.titleKey(planning.state())).getString()
                    + " · " + Component.translatable(PlanningProgressOverlay.phaseKey(planning.phase())).getString();
            graphics.drawString(font, fitText(font, text, layout.width() - CONTENT_PAD * 2), x + CONTENT_PAD, cy, color, false);
            cy += font.lineHeight + ROW_GAP;
            graphics.fill(x + CONTENT_PAD, cy, right, cy + 1, 0xFF596277);
            cy += ROW_GAP + 1;
        }
        if (execution != null) {
            ItemStack target = CraftProgressTracker.target(execution.craftId());
            String quantity = hudQuantity(execution, target);
            if (!target.isEmpty()) InkFluidRenderer.render(graphics, target, x + CONTENT_PAD, cy);
            String name = target.isEmpty() ? Component.translatable("rsi.progress.target_unknown").getString() : target.getHoverName().getString();
            graphics.drawString(font, fitText(font, name, layout.width() - CONTENT_PAD * 2 - 22 - font.width(quantity) - ROW_GAP),
                    x + CONTENT_PAD + 22, cy + 3, TEXT, false);
            graphics.drawString(font, quantity, right - font.width(quantity), cy + 3, TEXT, false);
            cy += Math.max(HEADER_HEIGHT, font.lineHeight) + ROW_GAP;
            String status = title(execution.result()).getString();
            String progress = hudProgress(execution).getString();
            if (!progress.isBlank()) status += " · " + progress;
            graphics.drawString(font, fitText(font, status, layout.width() - CONTENT_PAD * 2), x + CONTENT_PAD, cy, accent(execution.result()), false);
            cy += font.lineHeight + ROW_GAP;
            if (hasDetail) {
                graphics.drawString(font, fitText(font, hudDetail(execution).getString(), layout.width() - CONTENT_PAD * 2),
                        x + CONTENT_PAD, cy, MUTED, false);
                cy += font.lineHeight + ROW_GAP;
            }
            graphics.fill(x + CONTENT_PAD, cy, right, cy + BAR_HEIGHT, BAR_BACKGROUND);
            graphics.fill(x + CONTENT_PAD, cy,
                    x + CONTENT_PAD + (right - x - CONTENT_PAD) * hudProgressPercent(execution) / 100, cy + BAR_HEIGHT, accent(execution.result()));
            cy += BAR_HEIGHT + ROW_GAP;
        }
        // 唯一入口行，与计算和执行正文共同定位，禁止再独立叠加第二块 HUD。
        Component hint = hudHint(CraftProgressKeybind.translatedKeyMessage(), plans, running);
        graphics.drawString(font, fitText(font, hint.getString(), layout.width() - CONTENT_PAD * 2), x + CONTENT_PAD, cy, MUTED, false);
        graphics.pose().popPose();
    }

    static PlanningHudLayout combinedLayout(int screenWidth, int screenHeight, int lineHeight,
                                             boolean planning, boolean execution, boolean detail) {
        int contentHeight = CONTENT_PAD * 2 + lineHeight;
        if (planning) contentHeight += lineHeight + ROW_GAP * 2 + 1;
        if (execution) contentHeight += Math.max(HEADER_HEIGHT, lineHeight) + ROW_GAP + lineHeight + ROW_GAP
                + BAR_HEIGHT + ROW_GAP + (detail ? lineHeight + ROW_GAP : 0);
        boolean configured = RSIntegrationConfig.CLIENT_SPEC.isLoaded();
        return PlanningHudLayout.place(screenWidth, screenHeight, 172, 132, contentHeight, SCREEN_MARGIN,
                configured ? RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.get() : RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.getDefault(),
                configured ? RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.get() : RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.getDefault());
    }
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

    static Component hudHint(Component key, int plans, int running) {
        return Component.translatable("rsi.progress.hud.combined", key, plans, running);
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
