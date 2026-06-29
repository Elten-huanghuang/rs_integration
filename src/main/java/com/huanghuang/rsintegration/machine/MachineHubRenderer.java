package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Renders the Terminal Hub overlay — a grid of machine icons displayed
 * anchored to the RS Grid screen, styled to match the RS dark-terminal aesthetic.
 */
public final class MachineHubRenderer {

    private static final int SLOT_SIZE = 18;
    private static final int COLS = 6;
    private static final int PADDING = 5;
    private static final int TITLE_H = 14;
    private static final int FILTER_H = 14;
    private static final int FOOTER_H = 10;

    // ── Style (dark terminal theme) ──────────────────────────────────
    private static final int PANEL_BORDER  = 0xFF3B4A5C;
    private static final int PANEL_BG      = 0xFF121820;
    private static final int HEADER_BG     = 0xFF1C2834;
    private static final int SEPARATOR     = 0xFF2A3848;
    private static final int FILTER_BG     = 0xFF0A1016;
    private static final int FILTER_BORDER = 0xFF2A3848;
    private static final int SLOT_BG       = 0xFF1A2430;
    private static final int SLOT_HOVER    = 0x30FFFFFF;
    private static final int SCROLL_TRACK  = 0x20FFFFFF;
    private static final int SCROLL_THUMB  = 0x60FFFFFF;
    private static final int TEXT_PRIMARY  = 0xFFE0E8F0;
    private static final int TEXT_DIM      = 0xFF8899AA;

    private MachineHubRenderer() {}

    public static int render(GuiGraphics g, int gridX, int gridY, int gridW, int mouseX, int mouseY) {
        var machines = MachineHub.getMachines();
        var allMachines = MachineHub.getAllMachines();
        if (allMachines.isEmpty()) return -1;

        int rows = Math.max(1, (int) Math.ceil((double) machines.size() / COLS));
        int slotGridW = COLS * SLOT_SIZE;
        int maxVisibleRows = 12;
        int visibleRows = Math.min(rows, maxVisibleRows);
        int visibleGridH = visibleRows * SLOT_SIZE;

        // Ensure panel is wide enough for footer text + config toggle
        var font = Minecraft.getInstance().font;
        int contentMinW = Math.max(slotGridW, 150);
        int totalW = contentMinW + PADDING * 2;
        int totalH = visibleGridH + TITLE_H + FILTER_H + FOOTER_H + PADDING * 3 + 2;

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

        // ── Outer shadow ──────────────────────────────────────────────
        g.fill(x + 2, y + 2, x + totalW + 2, y + totalH + 2, 0x40000000);

        // ── Panel background + border ────────────────────────────────
        g.fill(x, y, x + totalW, y + totalH, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + totalW - 1, y + totalH - 1, PANEL_BG);

        // ── Header ───────────────────────────────────────────────────
        int headerBottom = y + 1 + TITLE_H + PADDING * 2 - 2;
        g.fill(x + 1, y + 1, x + totalW - 1, headerBottom, HEADER_BG);
        g.fill(x + 1, headerBottom, x + totalW - 1, headerBottom + 1, SEPARATOR);

        // Title text (truncated to fit between close button and left edge)
        String title = Component.translatable("rsi.hub.title", allMachines.size()).getString();
        int closeAreaW = 24;
        int titleMaxW = totalW - PADDING * 2 - closeAreaW - 4;
        String truncatedTitle = font.plainSubstrByWidth(title, titleMaxW);
        int titleX = x + PADDING + 2;
        int titleY = y + PADDING + 1;
        g.drawString(font, truncatedTitle, titleX, titleY, TEXT_PRIMARY);

        // Close button (drawn with lines, not text)
        int closeR = 5; // radius
        int closeCx = x + totalW - PADDING - closeR - 3;
        int closeCy = y + PADDING + TITLE_H / 2;
        boolean closeHovered = mouseX >= closeCx - closeR - 2 && mouseX < closeCx + closeR + 2
                && mouseY >= closeCy - closeR - 2 && mouseY < closeCy + closeR + 2;
        MachineHub.setCloseButtonHovered(closeHovered);

        int closeColor = closeHovered ? 0xFFFF5555 : TEXT_DIM;
        g.fill(closeCx - closeR, closeCy - 1, closeCx + closeR + 1, closeCy + 1, closeColor);   // horizontal bar
        g.fill(closeCx - 1, closeCy - closeR, closeCx + 1, closeCy + closeR + 1, closeColor);   // vertical bar

        // ── Filter bar ───────────────────────────────────────────────
        int filterX = x + PADDING;
        int filterY = headerBottom + PADDING - 1;
        int filterW = totalW - PADDING * 2;
        g.fill(filterX, filterY, filterX + filterW, filterY + FILTER_H, FILTER_BORDER);
        g.fill(filterX + 1, filterY + 1, filterX + filterW - 1, filterY + FILTER_H - 1, FILTER_BG);

        String filterText = MachineHub.getFilterText();
        // Count badge (right side of filter) — reserve space
        String countStr = machines.size() + "/" + allMachines.size();
        int countW = font.width(countStr);
        int filterTextMaxW = filterW - 8 - countW - 8;
        if (filterText.isEmpty()) {
            String hint = Component.translatable("rsi.hub.filter_hint").getString();
            g.drawString(font, font.plainSubstrByWidth(hint, filterTextMaxW),
                    filterX + 4, filterY + 3, TEXT_DIM);
        } else {
            String display = font.plainSubstrByWidth(filterText, filterTextMaxW)
                    + (System.currentTimeMillis() % 1000 < 500 ? "|" : " ");
            g.drawString(font, display, filterX + 4, filterY + 3, TEXT_PRIMARY);
        }

        g.drawString(font, countStr, filterX + filterW - countW - 5, filterY + 3, TEXT_DIM);

        // ── Machine grid ─────────────────────────────────────────────
        int gridY_ = filterY + FILTER_H + PADDING;
        int totalRowsHeight = rows * SLOT_SIZE;
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
            int sx = x + PADDING + col * SLOT_SIZE;
            int sy = gridY_ + row * SLOT_SIZE - scrollOffset;

            if (sy + SLOT_SIZE <= gridY_ || sy >= gridY_ + visibleGridH) continue;

            boolean isHovered = mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE;

            MachineInteractType iType = MachineInteractType.fromBlockKey(info.blockKey());
            MachineStatus iStatus = MachineStatusCache.getInstance().get(info);

            // Slot background
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, SLOT_BG);

            // Status-derived accent (left edge indicator)
            int accentColor = switch (iStatus.state()) {
                case HAS_OUTPUT -> 0xFF4488FF;
                case WORKING   -> 0xFFFFAA00;
                case IDLE      -> 0xFF22AA22;
                default        -> 0xFF666666;
            };
            if (iType == MachineInteractType.GUI) accentColor = 0xFF8855CC;
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
                g.fill(sx + 2, sy + SLOT_SIZE - 3, sx + 2 + barW, sy + SLOT_SIZE - 1, 0xFFFFCC00);
            }

            // Output count badge
            if (iType == MachineInteractType.QUICK
                    && iStatus.state() == MachineState.HAS_OUTPUT
                    && !iStatus.outputItem().isEmpty()) {
                int cnt = iStatus.outputItem().getCount();
                String cntStr = cnt > 99 ? "…" : String.valueOf(cnt);
                int tw = font.width(cntStr);
                g.fill(sx + SLOT_SIZE - tw - 3, sy + 1, sx + SLOT_SIZE - 1, sy + 9, 0xCC335599);
                g.drawString(font, cntStr, sx + SLOT_SIZE - tw - 2, sy + 2, 0xFFFFFF);
            }

            // GUI-type indicator (gear dots)
            if (iType == MachineInteractType.GUI) {
                int dx = sx + SLOT_SIZE - 6;
                int dy = sy + 1;
                for (int r = 0; r < 2; r++)
                    for (int c = 0; c < 2; c++)
                        g.fill(dx + c * 3, dy + r * 3, dx + c * 3 + 2, dy + r * 3 + 2, 0xFFBB88EE);
            }

            // Tooltip
            if (isHovered) {
                var tip = new java.util.ArrayList<Component>();
                tip.add(Component.literal(I18n.get(info.displayName())));
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
                    tip.add(Component.literal("§7" + info.dim() + " " + info.pos().toShortString()));
                }
                if (iType == MachineInteractType.QUICK) {
                    if (iStatus.state() == MachineState.HAS_OUTPUT) {
                        tip.add(Component.translatable("rsi.hub.controls.quick_has_output")
                                .withStyle(net.minecraft.ChatFormatting.AQUA));
                    } else {
                        tip.add(Component.translatable("rsi.hub.controls.quick_normal")
                                .withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                } else {
                    tip.add(Component.translatable("rsi.hub.controls.gui")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                g.renderTooltip(font, tip, java.util.Optional.empty(), mouseX, mouseY);
            }
        }

        // ── Scrollbar ────────────────────────────────────────────────
        if (maxScroll > 0) {
            int sbX = x + totalW - 3;
            int sbTop = gridY_;
            int sbH = visibleGridH;
            int thumbH = Math.max(16, sbH * sbH / totalRowsHeight);
            int thumbY = sbTop + (int) ((long) scrollOffset * (sbH - thumbH) / maxScroll);
            g.fill(sbX, sbTop, sbX + 2, sbTop + sbH, SCROLL_TRACK);
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, SCROLL_THUMB);
        }

        // ── Footer ───────────────────────────────────────────────────
        int footerY = gridY_ + visibleGridH + 2;
        g.fill(x + PADDING, footerY, x + totalW - PADDING, footerY + 1, SEPARATOR);
        int footerTextY = footerY + 3;

        // Config checkbox + label
        boolean returnToRs = RSIntegrationConfig.RETURN_TO_RS_AFTER_MACHINE_GUI.get();
        int cbSize = 9;
        int cbX = x + PADDING + 2;
        int cbY = footerTextY - 1;
        String labelText = Component.translatable("rsi.hub.config_return_rs").getString();
        int maxLabelW = totalW - PADDING * 2 - cbSize - 8;
        String truncatedLabel = font.plainSubstrByWidth(labelText, maxLabelW);
        int labelX = cbX + cbSize + 3;
        int labelY = footerTextY;

        boolean toggleHovered = mouseX >= cbX && mouseX < cbX + cbSize + 3 + font.width(truncatedLabel)
                && mouseY >= cbY - 1 && mouseY < cbY + cbSize + 2;

        // Checkbox border
        g.fill(cbX - 1, cbY - 1, cbX + cbSize + 1, cbY + cbSize + 1, toggleHovered ? 0xFF8899AA : 0xFF4A5568);
        g.fill(cbX, cbY, cbX + cbSize, cbY + cbSize, PANEL_BG);
        // Checkbox fill when enabled
        if (returnToRs) {
            int fillColor = toggleHovered ? 0xFF88CC88 : 0xFF55AA55;
            g.fill(cbX + 1, cbY + 1, cbX + cbSize - 1, cbY + cbSize - 1, fillColor);
            // Checkmark (simple cross-hatch)
            g.fill(cbX + 2, cbY + 4, cbX + 4, cbY + 7, 0xFFFFFFFF);
            g.fill(cbX + 4, cbY + 5, cbX + 7, cbY + 2, 0xFFFFFFFF);
        }
        // Label
        int labelColor = toggleHovered ? TEXT_PRIMARY : TEXT_DIM;
        g.drawString(font, truncatedLabel, labelX, labelY, labelColor);

        // Tooltip on hover
        if (toggleHovered) {
            g.renderTooltip(font,
                    List.of(Component.translatable("rsi.hub.config_return_rs_tooltip")),
                    java.util.Optional.empty(), mouseX, mouseY);
        }

        MachineHub.setConfigButtonHovered(toggleHovered);
        MachineHub.setConfigButtonBounds(cbX, cbY - 1, cbSize + 3 + font.width(truncatedLabel), cbSize + 4);

        pose.popPose();

        if (hovered >= 0) MachineHub.setHoveredIndex(hovered);
        return hovered;
    }

    static ItemStack resolveIcon(BindingInfo info) {
        String key = info.blockKey();
        if (key == null || key.isEmpty()) return new ItemStack(Items.CRAFTING_TABLE);
        int sep = key.indexOf("||");
        String descId = sep >= 0 ? key.substring(sep + 2) : key;
        if (descId.startsWith("block.")) {
            String rest = descId.substring(6);
            int dot = rest.indexOf('.');
            if (dot > 0) {
                String regKey = rest.substring(0, dot) + ":" + rest.substring(dot + 1);
                var rl = ResourceLocation.tryParse(regKey);
                if (rl != null) {
                    var block = ForgeRegistries.BLOCKS.getValue(rl);
                    if (block != null) return new ItemStack(block.asItem());
                }
            }
        }
        return new ItemStack(Items.CRAFTING_TABLE);
    }
}
