package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Renders the Terminal Hub overlay — a grid of machine icons displayed
 * anchored to the RS Grid screen rather than full-screen centered.
 */
public final class MachineHubRenderer {

    private static final int SLOT_SIZE = 18;
    private static final int COLS = 6;
    private static final int PADDING = 4;
    private static final int TITLE_H = 12;
    private static final int FILTER_H = 14;
    private static final int FOOTER_H = 10;
    private static final int BG_COLOR = 0xC0101010;
    private static final int HOVER_COLOR = 0x40FFFFFF;

    private MachineHubRenderer() {}

    /**
     * Render the hub overlay anchored to the RS grid screen.
     *
     * @param g        graphics context (in screen-absolute pose)
     * @param gridX    RS grid screen left edge
     * @param gridY    RS grid screen top edge
     * @param gridW    RS grid screen width (used to anchor hub to the right)
     * @param mouseX   mouse X (screen-absolute)
     * @param mouseY   mouse Y (screen-absolute)
     * @return the index of the hovered machine, or -1
     */
    public static int render(GuiGraphics g, int gridX, int gridY, int gridW, int mouseX, int mouseY) {
        var machines = MachineHub.getMachines();
        var allMachines = MachineHub.getAllMachines();
        if (allMachines.isEmpty()) return -1;

        int rows = (int) Math.ceil((double) machines.size() / COLS);
        if (rows < 1) rows = 1;
        int gridW_ = COLS * SLOT_SIZE;
        int gridH = rows * SLOT_SIZE;

        int maxVisibleRows = 12;
        int visibleRows = Math.min(rows, maxVisibleRows);
        int visibleGridH = visibleRows * SLOT_SIZE;

        int totalW = gridW_ + PADDING * 2;
        int totalH = visibleGridH + TITLE_H + FILTER_H + FOOTER_H + PADDING * 3;

        // Anchor to the LEFT of the RS grid (avoids JEI on the right).
        // If there's no room on the left, clamp to the screen edge instead
        // of flipping to the right side.
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int x = gridX - totalW - 4;  // 4px gap from grid left edge
        int y = gridY + 30;          // below the RS tabs/search bar

        // Clamp to screen bounds — never flip to the right/JEI side
        if (x < 2) x = 2;
        if (x + totalW > sw - 2) x = sw - totalW - 2;
        if (y + totalH > sh - 2) y = sh - totalH - 2;
        if (y < 2) y = 2;

        float alpha = MachineHub.getAnimProgress();
        if (alpha <= 0f) return -1;

        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400f);

        // Background
        int bgAlpha = (int) (0xC0 * alpha) << 24;
        g.fill(x, y, x + totalW, y + totalH, bgAlpha | 0x101010);

        var font = Minecraft.getInstance().font;
        int titleColor = (int) (0xFF * alpha) << 24 | 0xFFFFFF;
        int dimColor = (int) (0xFF * alpha) << 24 | 0xAAAAAA;

        // Title line with close button
        int closeSize = 10;
        int closeX = x + totalW - PADDING - closeSize;
        int closeY = y + PADDING;
        boolean closeHovered = mouseX >= closeX && mouseX < closeX + closeSize
                && mouseY >= closeY && mouseY < closeY + closeSize;
        MachineHub.setCloseButtonHovered(closeHovered);

        String title = Component.translatable("rsi.hub.title", allMachines.size()).getString();
        int maxTitleW = closeX - x - PADDING - 4;
        if (font.width(title) > maxTitleW) {
            title = font.plainSubstrByWidth(title, maxTitleW - font.width("…")) + "…";
        }
        g.drawString(font, title, x + PADDING, y + PADDING, titleColor);

        // Close button (×)
        int closeColor = closeHovered ? 0xFFFF4444 : 0xFF888888;
        g.drawString(font, "×", closeX + 1, closeY, closeColor);

        // Filter bar
        int filterY = y + PADDING + TITLE_H;
        String filterText = MachineHub.getFilterText();
        int filterColor = filterText.isEmpty() ? dimColor : titleColor;
        g.fill(x + PADDING, filterY, x + totalW - PADDING, filterY + FILTER_H, 0x30000000 | (bgAlpha & 0xFF000000));
        String filterDisplay = filterText.isEmpty()
                ? Component.translatable("rsi.hub.filter_hint").getString()
                : filterText + (System.currentTimeMillis() % 1000 < 500 ? "_" : " ");
        g.drawString(font, filterDisplay,
                x + PADDING + 2, filterY + 3, filterColor);

        // Machine count
        String countStr = machines.size() + "/" + allMachines.size();
        int countW = font.width(countStr);
        g.drawString(font, countStr, x + totalW - PADDING - countW - 2, filterY + 3, dimColor);

        // Scroll clamping
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

            int borderColor, slotBg;
            if (iType == MachineInteractType.QUICK) {
                switch (iStatus.state()) {
                    case HAS_OUTPUT:
                        borderColor = isHovered ? 0xFF88CCFF : 0xFF4488FF;
                        slotBg = 0xFF223355;
                        break;
                    case WORKING:
                        borderColor = isHovered ? 0xFFFFCC44 : 0xFFFFAA00;
                        slotBg = 0xFF332211;
                        break;
                    case IDLE:
                        borderColor = isHovered ? 0xFF66DD66 : 0xFF22AA22;
                        slotBg = 0xFF113311;
                        break;
                    default:
                        borderColor = isHovered ? 0xFFAAAAAA : 0xFF666666;
                        slotBg = 0xFF222222;
                        break;
                }
            } else {
                borderColor = isHovered ? 0xFFCC99FF : 0xFF8855CC;
                slotBg = 0xFF221133;
            }

            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, slotBg);

            if (isHovered) {
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x30FFFFFF);
                hovered = i;
            }

            g.renderOutline(sx, sy, SLOT_SIZE, SLOT_SIZE, borderColor);

            ItemStack icon = resolveIcon(info);
            if (!icon.isEmpty()) {
                g.renderItem(icon, sx + 1, sy + 1);
                g.renderItemDecorations(font, icon, sx + 1, sy + 1);
            }

            if (iType == MachineInteractType.QUICK
                    && iStatus.state() == MachineState.WORKING
                    && iStatus.maxProgress() > 0) {
                int barW = Math.max(1, (int) ((SLOT_SIZE - 4) * iStatus.progressFraction()));
                g.fill(sx + 2, sy + SLOT_SIZE - 4, sx + 2 + barW, sy + SLOT_SIZE - 2, 0xFFFFCC00);
            }

            if (iType == MachineInteractType.QUICK
                    && iStatus.state() == MachineState.HAS_OUTPUT
                    && !iStatus.outputItem().isEmpty()) {
                int count = iStatus.outputItem().getCount();
                String cnt = count > 99 ? "…" : String.valueOf(count);
                int textW = font.width(cnt);
                g.fill(sx + SLOT_SIZE - textW - 3, sy + 1,
                       sx + SLOT_SIZE - 1, sy + 9, 0xCC3366CC);
                g.drawString(font, cnt, sx + SLOT_SIZE - textW - 2, sy + 2, 0xFFFFFF);
            }

            if (iType == MachineInteractType.GUI) {
                int dotX = sx + SLOT_SIZE - 5;
                int dotY = sy + 2;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        g.fill(dotX + c * 2, dotY + r * 2,
                               dotX + c * 2 + 1, dotY + r * 2 + 1, 0xFFBB88EE);
                    }
                }
            }

            if (isHovered) {
                var tipLines = new java.util.ArrayList<Component>();
                tipLines.add(Component.literal(
                        net.minecraft.client.resources.language.I18n.get(info.displayName())));
                if (iType == MachineInteractType.QUICK) {
                    String stateText = switch (iStatus.state()) {
                        case HAS_OUTPUT -> "§bOutput Ready";
                        case WORKING -> "§6Working " + (int)(iStatus.progressFraction() * 100) + "%";
                        case IDLE -> "§aIdle";
                        case UNKNOWN -> "§7Unknown";
                    };
                    tipLines.add(Component.literal(stateText));
                } else {
                    tipLines.add(Component.literal("§dGUI Machine"));
                }
                if (info.dim() != null) {
                    tipLines.add(Component.literal("§7" + info.dim() + " " + info.pos().toShortString()));
                }
                if (iType == MachineInteractType.QUICK) {
                    if (iStatus.state() == MachineState.HAS_OUTPUT) {
                        tipLines.add(Component.literal("§bL: Collect  §bShift: →RS  §7R: Open"));
                    } else {
                        tipLines.add(Component.literal("§7L: Insert  §7Shift: Fuel  §7R: Open"));
                    }
                } else {
                    tipLines.add(Component.literal("§7L/R: Open GUI"));
                }
                g.renderTooltip(font, tipLines, java.util.Optional.empty(), mouseX, mouseY);
            }
        }

        // Scrollbar
        if (maxScroll > 0) {
            int scrollbarX = x + totalW - 3;
            int scrollbarH = Math.max(16, visibleGridH * visibleGridH / totalRowsHeight);
            int scrollbarY = gridY_ + (int) ((long) scrollOffset * visibleGridH / totalRowsHeight);
            g.fill(scrollbarX, gridY_, scrollbarX + 2, gridY_ + visibleGridH, 0x30FFFFFF);
            g.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarH, 0x80FFFFFF);
        }

        // Footer
        int footerY = gridY_ + visibleGridH + 2;
        String footer = Component.translatable("rsi.hub.machine_count", allMachines.size()).getString();
        g.drawString(font, footer, x + PADDING, footerY, dimColor);

        pose.popPose();

        if (hovered >= 0) {
            MachineHub.setHoveredIndex(hovered);
        }

        return hovered;
    }

    /**
     * Resolves a block item icon from the binding's blockKey.
     * blockKey is a description ID (e.g. "block.goety.dark_altar") or
     * "{prefix}||block.modid.name".  Convert it to a registry key to
     * look up the block, falling back to the crafting table icon.
     */
    static ItemStack resolveIcon(BindingInfo info) {
        String key = info.blockKey();
        if (key == null || key.isEmpty()) return new ItemStack(Items.CRAFTING_TABLE);

        // Strip optional prefix:  "crystal_ritual||block.wizards_reborn.crystal"
        int sep = key.indexOf("||");
        String descId = sep >= 0 ? key.substring(sep + 2) : key;

        // Convert "block.modid.name" → "modid:name"
        if (descId.startsWith("block.")) {
            String rest = descId.substring(6); // "modid.name"
            int dot = rest.indexOf('.');
            if (dot > 0) {
                String registryKey = rest.substring(0, dot) + ":" + rest.substring(dot + 1);
                var rl = ResourceLocation.tryParse(registryKey);
                if (rl != null) {
                    var block = ForgeRegistries.BLOCKS.getValue(rl);
                    if (block != null) return new ItemStack(block.asItem());
                }
            }
        }
        return new ItemStack(Items.CRAFTING_TABLE);
    }
}
