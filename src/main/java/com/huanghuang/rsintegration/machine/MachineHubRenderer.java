package com.huanghuang.rsintegration.machine;


import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;


/**
 * Renders the Terminal Hub overlay — a grid of machine icons displayed
 * anchored to the RS Grid screen, styled to match the RS light-gray aesthetic.
 */
public final class MachineHubRenderer {

    private static final int SLOT_SIZE = 18;
    private static final int CELL_GAP  = 5;
    private static final int CELL_SIZE = SLOT_SIZE + CELL_GAP;
    private static final int COLS = 7;
    private static final int PADDING = 8;
    private static final int TITLE_H = 14;
    private static final int FILTER_H = 14;

    // ── Style (RS Classic Light Theme) ───────────────────────────────
    private static final int PANEL_BORDER  = 0xFF373737;
    private static final int PANEL_BG      = 0xFFC6C6C6;
    private static final int HEADER_BG     = 0xFFC6C6C6;

    private static final int FILTER_BG     = 0xFF000000;
    private static final int FILTER_BORDER = 0xFF373737;
    private static final int FILTER_TEXT   = 0xFFFFFFFF;

    private static final int SLOT_BG       = 0xFF8B8B8B;
    private static final int SLOT_HOVER    = 0x80FFFFFF;

    private static final int SCROLL_TRACK  = 0xFF8B8B8B;
    private static final int SCROLL_THUMB  = 0xFF373737;

    private static final int TEXT_PRIMARY  = 0xFF404040;

    private MachineHubRenderer() {}

    public static int render(GuiGraphics g, int gridX, int gridY, int gridW, int mouseX, int mouseY) {
        var machines = MachineHub.getMachines();
        var allMachines = MachineHub.getAllMachines();
        if (allMachines.isEmpty()) return -1;

        int rows = Math.max(1, (int) Math.ceil((double) machines.size() / COLS));
        int slotGridW = COLS * CELL_SIZE - CELL_GAP;
        int maxVisibleRows = 12;
        int visibleRows = Math.min(rows, maxVisibleRows);
        int visibleGridH = visibleRows * CELL_SIZE - CELL_GAP;

        var font = Minecraft.getInstance().font;
        int contentMinW = Math.max(slotGridW, 150);
        int totalW = contentMinW + PADDING * 2;
        int totalH = visibleGridH + TITLE_H + FILTER_H + PADDING * 3;

        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = gridX - totalW - 4 + MachineHub.getDragOffsetX();
        int y = gridY + 30 + MachineHub.getDragOffsetY();
        if (x < 2) x = 2;
        if (x + totalW > sw - 2) x = sw - totalW - 2;
        if (y + totalH > sh - 2) y = sh - totalH - 2;
        if (y < 2) y = 2;

        if (MachineHub.isDragging()) {
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                MachineHub.endDrag();
            } else {
                MachineHub.updateDrag(mouseX, mouseY);
            }
        }
        MachineHub.setHubBounds(x, y, totalW, totalH);

        float alpha = MachineHub.getAnimProgress();
        if (alpha <= 0f) return -1;

        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400f);

        // ── Drop shadow (2-layer, soft) ──────────────────────────────
        g.fill(x + 3, y + 3, x + totalW + 3, y + totalH + 3, 0x18000000);
        g.fill(x + 1, y + 1, x + totalW + 1, y + totalH + 1, 0x38000000);

        // ── Panel background + border ────────────────────────────────
        g.fill(x, y, x + totalW, y + totalH, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + totalW - 1, y + totalH - 1, PANEL_BG);

        // ── Header ───────────────────────────────────────────────────
        int headerBottom = y + 1 + TITLE_H + PADDING * 2 - 2;
        g.fill(x + 1, y + 1, x + totalW - 1, headerBottom, HEADER_BG);

        // Title text
        String title = Component.translatable("rsi.hub.title", allMachines.size()).getString();
        int closeAreaW = 24;
        int titleMaxW = totalW - PADDING * 2 - closeAreaW - 4;
        String truncatedTitle = font.plainSubstrByWidth(title, titleMaxW);
        int titleX = x + PADDING + 2;
        int titleY = y + PADDING + 1;
        g.drawString(font, truncatedTitle, titleX, titleY, TEXT_PRIMARY);

        // Close button (X)
        String xMark = "✕";
        int xW = font.width(xMark);
        int xX = x + totalW - PADDING - xW - 4;
        int xY = y + PADDING + (TITLE_H - font.lineHeight) / 2;
        boolean closeHovered = mouseX >= xX - 2 && mouseX < xX + xW + 2
                && mouseY >= xY - 2 && mouseY < xY + font.lineHeight + 2;
        MachineHub.setCloseButtonHovered(closeHovered);
        int closeColor = closeHovered ? 0xFFFF3333 : TEXT_PRIMARY;
        g.drawString(font, xMark, xX, xY, closeColor);

        // ── Filter bar (RS Black Style) ──────────────────────────────
        int filterX = x + PADDING;
        int filterY = headerBottom;
        int filterW = totalW - PADDING * 2;
        g.fill(filterX, filterY, filterX + filterW, filterY + FILTER_H, FILTER_BORDER);
        g.fill(filterX + 1, filterY + 1, filterX + filterW - 1, filterY + FILTER_H - 1, FILTER_BG);

        String filterText = MachineHub.getFilterText();
        String countStr = machines.size() + "/" + allMachines.size();
        int countW = font.width(countStr);
        int filterTextMaxW = filterW - 8 - countW - 8;

        if (filterText.isEmpty()) {
            String hint = Component.translatable("rsi.hub.filter_hint").getString();
            g.drawString(font, font.plainSubstrByWidth(hint, filterTextMaxW),
                    filterX + 4, filterY + 3, 0xFF666666);
        } else {
            String display = font.plainSubstrByWidth(filterText, filterTextMaxW)
                    + (System.currentTimeMillis() % 1000 < 500 ? "|" : " ");
            g.drawString(font, display, filterX + 4, filterY + 3, FILTER_TEXT);
        }

        g.drawString(font, countStr, filterX + filterW - countW - 5, filterY + 3, 0xFF888888);

        // ── Machine grid ─────────────────────────────────────────────
        int gridY_ = filterY + FILTER_H + PADDING;
        int totalRowsHeight = rows * CELL_SIZE - CELL_GAP;
        int maxScroll = Math.max(0, totalRowsHeight - visibleGridH);
        int scrollOffset = MachineHub.getScrollOffset();
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;
        MachineHub.setScrollOffset(scrollOffset);

        int hovered = -1;

        for (int i = 0; i < machines.size(); i++) {
            BindingInfo info = machines.get(i);
            int col = i % COLS;
            int row = i / COLS;
            int sx = x + PADDING + col * CELL_SIZE;
            int sy = gridY_ + row * CELL_SIZE - scrollOffset;

            if (sy + SLOT_SIZE <= gridY_ || sy >= gridY_ + visibleGridH) continue;

            boolean isHovered = mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE;

            MachineInteractType iType = MachineInteractType.fromBlockKey(info.blockKey());
            MachineStatus iStatus = MachineStatusCache.getInstance().get(info);

            // Slot background
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, SLOT_BG);

            // Status-derived accent
            int accentColor = switch (iStatus.state()) {
                case HAS_OUTPUT -> 0xFF3388FF;
                case WORKING   -> 0xFFFF8800;
                case IDLE      -> 0xFF11AA11;
                default        -> 0xFF999999;
            };
            if (iType == MachineInteractType.GUI) accentColor = 0xFF9944DD;
            g.fill(sx, sy, sx + 2, sy + SLOT_SIZE, accentColor);

            // Hover highlight
            if (isHovered) {
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, SLOT_HOVER);
                hovered = i;
            }

            // Icon
            ItemStack icon = resolveIcon(info);
            if (!icon.isEmpty()) {
                g.renderItem(icon, sx + 1, sy + 1);
                g.renderItemDecorations(font, icon, sx + 1, sy + 1);
            }

            // Working progress bar
            if (iType == MachineInteractType.QUICK
                    && iStatus.state() == MachineState.WORKING
                    && iStatus.maxProgress() > 0) {
                int barW = Math.max(1, (int) ((SLOT_SIZE - 4) * iStatus.progressFraction()));
                g.fill(sx + 2, sy + SLOT_SIZE - 3, sx + 2 + barW, sy + SLOT_SIZE - 1, 0xFFFFB300);
            }

            // Output count badge
            if (iType == MachineInteractType.QUICK
                    && iStatus.state() == MachineState.HAS_OUTPUT
                    && !iStatus.outputItem().isEmpty()) {
                int cnt = iStatus.outputItem().getCount();
                String cntStr = cnt > 99 ? "…" : String.valueOf(cnt);
                int tw = font.width(cntStr);
                g.fill(sx + SLOT_SIZE - tw - 3, sy + 1, sx + SLOT_SIZE - 1, sy + 9, 0xDD224488);
                g.drawString(font, cntStr, sx + SLOT_SIZE - tw - 2, sy + 2, 0xFFFFFF);
            }

            // GUI-type indicator (gear dots)
            if (iType == MachineInteractType.GUI) {
                int dx = sx + SLOT_SIZE - 6;
                int dy = sy + 1;
                for (int r = 0; r < 2; r++)
                    for (int c = 0; c < 2; c++)
                        g.fill(dx + c * 3, dy + r * 3, dx + c * 3 + 2, dy + r * 3 + 2, 0xFFCC77FF);
            }

            // Tooltip
            if (isHovered) {
                var tip = new java.util.ArrayList<Component>();
                Component displayName;
                ItemStack ds = info.displayStack();
                if (ds != null && !ds.isEmpty()) {
                    displayName = com.huanghuang.rsintegration.network.BindingEventHandler
                            .resolveBlockName(info.blockKey(), info.blockRegKey(), ds);
                } else {
                    displayName = Component.literal(I18n.get(info.displayName()));
                }
                tip.add(displayName);
                if (iType == MachineInteractType.QUICK) {
                    String st = switch (iStatus.state()) {
                        case HAS_OUTPUT -> "§b" + Component.translatable("rsi.hub.state.has_output").getString();
                        case WORKING   -> "§6" + Component.translatable("rsi.hub.state.working",
                                (int)(iStatus.progressFraction() * 100)).getString();
                        case IDLE      -> "§a" + Component.translatable("rsi.hub.state.idle").getString();
                        case UNKNOWN   -> "§7" + Component.translatable("rsi.hub.state.unknown").getString();
                    };
                    tip.add(Component.literal(st));
                } else {
                    tip.add(Component.translatable("rsi.hub.type.gui_machine")
                            .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                }
                if (info.dim() != null) {
                    String dimName = I18n.get(info.dim().toString());
                    tip.add(Component.literal("§7" + dimName + " " + info.pos().toShortString()));
                }
                if (iType == MachineInteractType.QUICK) {
                    if (iStatus.state() == MachineState.HAS_OUTPUT) {
                        tip.add(Component.translatable("rsi.hub.controls.quick_has_output")
                                .withStyle(net.minecraft.ChatFormatting.AQUA));
                    } else {
                        tip.add(Component.translatable("rsi.hub.controls.quick_normal")
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                    }
                } else {
                    tip.add(Component.translatable("rsi.hub.controls.gui")
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                }
                MachineHub.isRenderingOurTooltip = true;
                g.renderTooltip(font, tip, java.util.Optional.empty(), mouseX, mouseY);
                MachineHub.isRenderingOurTooltip = false;
            }
        }

        // ── Scrollbar ────────────────────────────────────────────────
        if (maxScroll > 0) {
            int sbX = x + totalW - 4;
            int sbTop = gridY_;
            int sbH = visibleGridH;
            int thumbH = Math.max(16, sbH * sbH / totalRowsHeight);
            int thumbY = sbTop + (int) ((long) scrollOffset * (sbH - thumbH) / maxScroll);
            g.fill(sbX, sbTop, sbX + 3, sbTop + sbH, SCROLL_TRACK);
            g.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, SCROLL_THUMB);
        }

        pose.popPose();

        if (hovered >= 0) MachineHub.setHoveredIndex(hovered);
        return hovered;
    }

    static ItemStack resolveIcon(BindingInfo info) {
        return com.huanghuang.rsintegration.network.BindingEventHandler.resolveBlockIcon(
                info.blockRegKey(), info.blockKey(), info.displayStack());
    }
}
