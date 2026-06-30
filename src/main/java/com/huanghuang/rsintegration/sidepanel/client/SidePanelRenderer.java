package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.PanelStack;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Rendering utilities for the side panel.
 * All methods are {@code public static} and receive the data they need
 * as parameters — they hold no mutable state.
 */
public final class SidePanelRenderer {

    private SidePanelRenderer() {}

    // ── Main panel rendering ─────────────────────────────────────

    /**
     * Render the full expanded side panel.
     *
     * @param g               graphics context
     * @param panelX          panel top-left X
     * @param panelY          panel top-left Y
     * @param visibleRows     number of visible grid rows
     * @param scrollRow       current scroll offset (rows)
     * @param networkAvailable whether an RS network is accessible
     * @param networkName     display name of the network
     * @param totalSlotCount  total item types in network
     * @param searchWidget    the search text field (may be null; created lazily by caller)
     * @param searchText      current search bar text
     * @param searchFocused   whether the search bar has keyboard focus
     * @param viewType        item view filter (0=all, 1=non-craftable, 2=craftable)
     * @param sortAsc         sort direction (true=ascending)
     * @param sortMode        sort criterion (0=name, 1=count, 2=id, 3=last_modified)
     * @param searchMode      search mode (0-5, see JEI-linked modes)
     * @param gridSize        grid size preset (0=stretch, 1=small, 2=medium, 3=large)
     * @param mouseX          current mouse X (gui-scaled)
     * @param mouseY          current mouse Y (gui-scaled)
     * @param displayList     filtered/sorted item list to render
     * @param slotAnims       active slot animations (id → anim)
     * @param gridDragging    whether a grid drag operation is in progress
     * @param gridDragKeys    set of UUIDs involved in the current drag
     * @return a {@link PanelRenderResult} with hovered-slot and hovered-side-button indices
     */
    @SuppressWarnings("resource")
    public static PanelRenderResult renderPanel(GuiGraphics g,
                                                 int panelX, int panelY,
                                                 int visibleRows, int scrollRow,
                                                 boolean networkAvailable, String networkName,
                                                 int totalSlotCount,
                                                 net.minecraft.client.gui.components.EditBox searchWidget,
                                                 String searchText, boolean searchFocused,
                                                 int viewType, boolean sortAsc,
                                                 int sortMode, int searchMode, int gridSize,
                                                 int mouseX, int mouseY,
                                                 List<PanelStack> displayList,
                                                 Map<UUID, ? extends SlotAnimProvider> slotAnims,
                                                 boolean gridDragging,
                                                 Set<UUID> gridDragKeys) {

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Font font = Minecraft.getInstance().font;
        int gy = panelY + RSSidePanelClient.HEADER_H;
        int gridH = visibleRows * RSSidePanelClient.SLOT_SIZE;

        // ── 1. Header strip ──────────────────────────────────────
        g.blit(RSSidePanelClient.RS_GRID_TEX, panelX, panelY, 0, 0, RSSidePanelClient.GRID_W, RSSidePanelClient.HEADER_H);

        // ── 2. Row backgrounds ───────────────────────────────────
        for (int row = 0; row < visibleRows; row++) {
            int v = row == 0 ? 19 : (row == visibleRows - 1 ? 55 : 37);
            int ry = gy + row * RSSidePanelClient.SLOT_SIZE;
            g.blit(RSSidePanelClient.RS_GRID_TEX, panelX, ry, 0, v, RSSidePanelClient.GRID_W, RSSidePanelClient.SLOT_SIZE);
        }

        // ── 3. Bottom strip ──────────────────────────────────────
        int by = gy + gridH;
        g.blit(RSSidePanelClient.RS_GRID_TEX, panelX, by, 0, 73, RSSidePanelClient.GRID_W, RSSidePanelClient.BOTTOM_H);

        // ── 4. Header content ────────────────────────────────────
        renderHeaderTitle(g, font, panelX, panelY,
                networkAvailable, networkName, totalSlotCount);

        // Search box
        int sx = panelX + 81, sy = panelY + 7, sw = 82;
        if (searchWidget != null) {
            searchWidget.setX(sx);
            searchWidget.setY(sy);
            searchWidget.setWidth(sw);
            searchWidget.setFocused(searchFocused);
            searchWidget.render(g, mouseX, mouseY, 0);
        }

        // ── 5. Side buttons ──────────────────────────────────────
        int hoveredSideButton = -1;
        int btnX = panelX + RSSidePanelClient.SIDE_BTN_FLOAT_X;
        int sby = panelY + RSSidePanelClient.HEADER_H + 1;
        for (int i = 0; i < 5; i++) {
            int h = renderSideButton(g, btnX, sby + i * RSSidePanelClient.SIDE_BTN_PITCH, i,
                    viewType, sortAsc, sortMode, searchMode, gridSize,
                    mouseX, mouseY);
            if (h >= 0) hoveredSideButton = h;
        }

        // ── 6. Fold button ───────────────────────────────────────
        int foldX = panelX + RSSidePanelClient.GRID_W - 16;
        int foldY = panelY + 2;
        boolean foldHovered = mouseX >= foldX && mouseX < foldX + 14
                && mouseY >= foldY && mouseY < foldY + 14;
        g.blit(RSSidePanelClient.RS_ICONS_TEX, foldX, foldY, foldHovered ? 16 : 0, 128, 14, 14, 256, 256);

        // ── 7. Grid items ────────────────────────────────────────
        int hoveredSlotIndex = -1;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < RSSidePanelClient.COLUMNS; col++) {
                int dIdx = (scrollRow + row) * RSSidePanelClient.COLUMNS + col;
                if (dIdx >= displayList.size()) break;
                PanelStack ps = displayList.get(dIdx);
                ItemStack stack = ps.getStack();

                int itemLeft = panelX + RSSidePanelClient.GRID_ITEM_X;
                int itemTop  = panelY + RSSidePanelClient.HEADER_H;
                int ix = itemLeft + col * RSSidePanelClient.SLOT_SIZE + 1;
                int iy = itemTop  + row * RSSidePanelClient.SLOT_SIZE + 1;

                boolean hovered = mouseX >= ix - 1 && mouseX < ix + 17
                        && mouseY >= iy - 1 && mouseY < iy + 17;
                if (hovered) hoveredSlotIndex = dIdx;

                // Animation overlay
                if (slotAnims != null) {
                    SlotAnimProvider anim = slotAnims.get(ps.getId());
                    if (anim != null && !anim.expired()) {
                        float fade = anim.fade();
                        int animColor = anim.deltaValue() < 0
                                ? ((int) (fade * 0x50) << 24) | 0xFF4444
                                : ((int) (fade * 0x50) << 24) | 0x44FF44;
                        g.fill(ix - 1, iy - 1, ix + 17, iy + 17, animColor);
                    }
                }

                // RS pattern: renderItem + renderItemDecorations + renderQuantity
                try {
                    g.renderItem(stack, ix, iy);
                    g.renderItemDecorations(font, stack, ix, iy, "");

                    String label = null;
                    int labelColor = 0xFFFFFF;
                    if (ps.zeroed && !ps.craftable) {
                        label = "0";
                        labelColor = 0xFF5555;
                    } else if (ps.craftable) {
                        label = net.minecraft.client.resources.language.I18n.get(
                                "gui.refinedstorage.grid.craft");
                    } else if (ps.getCount() > 1) {
                        label = formatCount(ps.getCount());
                    }
                    if (label != null) {
                        renderSlotQuantity(g, font, ix, iy, label, labelColor);
                    }
                } catch (Exception t) {
                    RSIntegrationMod.LOGGER.warn("[RSI-SidePanel] Failed render stack {}: {}",
                            ForgeRegistries.ITEMS.getKey(stack.getItem()), t.toString());
                }

                boolean dragHighlight = gridDragging
                        && gridDragKeys.contains(ps.getId());
                if (dragHighlight || hovered) {
                    var pose = g.pose();
                    pose.pushPose();
                    pose.translate(0, 0, 300);
                    RenderSystem.enableBlend();
                    int hlColor = dragHighlight ? 0x806699CC : 0x60FFFFFF;
                    g.fill(ix, iy, ix + 16, iy + 16, hlColor);
                    pose.popPose();
                }

                // Lock icon (top-left corner)
                if (RSSidePanelClient.isItemLocked(stack)) {
                    renderLockIcon(g, ix + 1, iy + 1);
                }

                // GUI icon for bound machines
                String itemKey = RSSidePanelClient.keyOf(stack);
                if (!itemKey.isEmpty() && com.huanghuang.rsintegration.sidepanel.data.BindingCache.getInstance().hasGui(itemKey)) {
                    int guiIconX = ix + 10;
                    int guiIconY = iy + 10;
                    boolean guiHovered = mouseX >= guiIconX && mouseX < guiIconX + 8
                            && mouseY >= guiIconY && mouseY < guiIconY + 8;
                    renderGuiIcon(g, guiIconX, guiIconY, guiHovered);
                }
            }
        }

        // ── 8. Scrollbar ─────────────────────────────────────────
        int totalRows = (int) Math.ceil(displayList.size() / (double) RSSidePanelClient.COLUMNS);
        renderScrollbar(g, panelX, panelY, visibleRows, scrollRow, totalRows);

        // ── 9. Tooltips ──────────────────────────────────────────
        if (hoveredSlotIndex >= 0 && hoveredSlotIndex < displayList.size()) {
            ItemStack hs = displayList.get(hoveredSlotIndex).getStack();
            if (!hs.isEmpty()) {
                renderItemTooltip(g, font, hs, mouseX, mouseY);

                // Lock icon tooltip
                if (RSSidePanelClient.isItemLocked(hs)) {
                    int col = hoveredSlotIndex % RSSidePanelClient.COLUMNS;
                    int row = hoveredSlotIndex / RSSidePanelClient.COLUMNS - scrollRow;
                    if (row >= 0 && row < visibleRows) {
                        int lix = panelX + RSSidePanelClient.GRID_ITEM_X + col * RSSidePanelClient.SLOT_SIZE + 1 + 1;
                        int liy = panelY + RSSidePanelClient.HEADER_H + row * RSSidePanelClient.SLOT_SIZE + 1 + 1;
                        if (mouseX >= lix && mouseX < lix + 7 && mouseY >= liy && mouseY < liy + 7) {
                            g.renderTooltip(font,
                                    Component.translatable("rsi.side_panel.locked_item"),
                                    mouseX, mouseY);
                        }
                    }
                }

                // GUI icon tooltip — show when hovering the 8×8 icon
                String hsKey = RSSidePanelClient.keyOf(hs);
                if (!hsKey.isEmpty()
                        && com.huanghuang.rsintegration.sidepanel.data.BindingCache.getInstance().hasGui(hsKey)) {
                    int col = hoveredSlotIndex % RSSidePanelClient.COLUMNS;
                    int row = hoveredSlotIndex / RSSidePanelClient.COLUMNS - scrollRow;
                    if (row >= 0 && row < visibleRows) {
                        int gix = panelX + RSSidePanelClient.GRID_ITEM_X + col * RSSidePanelClient.SLOT_SIZE + 1 + 10;
                        int giy = panelY + RSSidePanelClient.HEADER_H + row * RSSidePanelClient.SLOT_SIZE + 1 + 10;
                        if (mouseX >= gix && mouseX < gix + 8
                                && mouseY >= giy && mouseY < giy + 8) {
                            g.renderTooltip(font,
                                    Component.translatable("rsi.side_panel.open_machine"),
                                    mouseX, mouseY);
                        }
                    }
                }
            }
        }
        if (hoveredSideButton >= 0)
            renderSideButtonTooltip(g, font, hoveredSideButton,
                    viewType, sortAsc, sortMode, searchMode, gridSize,
                    mouseX, mouseY);

        return new PanelRenderResult(hoveredSideButton, hoveredSlotIndex);
    }

    // ── Header title ─────────────────────────────────────────────

    /** Render the network-name / "No Network" title in the header. */
    @SuppressWarnings("resource")
    public static void renderHeaderTitle(GuiGraphics g, Font font,
                                          int panelX, int panelY,
                                          boolean networkAvailable,
                                          String networkName,
                                          int totalSlotCount) {
        String title;
        int titleColor;
        if (networkAvailable) {
            title = !networkName.isEmpty() ? networkName : "Refined Storage";
            titleColor = 0xFF7BAAF7;
        } else {
            title = Component.translatable("rsi.side_panel.no_network").getString();
            titleColor = 0xFFFF5555;
        }
        int titleMaxW = 73;
        g.drawString(font, font.plainSubstrByWidth(title, titleMaxW),
                panelX + RSSidePanelClient.GRID_ITEM_X, panelY + 7, titleColor);
    }

    // ── Collapsed bar ────────────────────────────────────────────

    /** Render the collapsed (title-only) bar shown when the panel is hidden. */
    @SuppressWarnings("resource")
    public static void renderCollapsedBar(GuiGraphics g,
                                           int panelX, int panelY,
                                           boolean networkAvailable,
                                           String networkName,
                                           int totalSlotCount) {
        Font font = Minecraft.getInstance().font;
        g.blit(RSSidePanelClient.RS_GRID_TEX, panelX, panelY, 0, 0, RSSidePanelClient.GRID_W, RSSidePanelClient.HEADER_H);

        String title;
        int titleColor;
        if (networkAvailable) {
            title = !networkName.isEmpty() ? networkName : "Refined Storage";
            titleColor = 0xFF7BAAF7;
        } else {
            title = Component.translatable("rsi.side_panel.no_network").getString();
            titleColor = 0xFFFF5555;
        }
        int titleMaxW = 73;
        g.drawString(font, font.plainSubstrByWidth(title, titleMaxW),
                panelX + RSSidePanelClient.GRID_ITEM_X, panelY + 7, titleColor);
        g.drawString(font, "▶", panelX + RSSidePanelClient.GRID_W - 16, panelY + 5, 0xFFAAAAAA);
    }

    // ── Side button ──────────────────────────────────────────────

    /**
     * Render one side button (view type, sort direction, sort mode, search mode, grid size).
     *
     * @return the button index if hovered, -1 otherwise
     */
    public static int renderSideButton(GuiGraphics g,
                                        int bx, int by, int idx,
                                        int viewType, boolean sortAsc,
                                        int sortMode, int searchMode,
                                        int gridSize,
                                        int mouseX, int mouseY) {
        boolean hovered = mouseX >= bx && mouseX < bx + RSSidePanelClient.SIDE_BTN_SIZE
                && mouseY >= by && mouseY < by + RSSidePanelClient.SIDE_BTN_SIZE;

        var p = g.pose();
        p.pushPose();
        p.translate(0, 0, 20);

        int bgV = hovered ? 54 : 16;
        g.blit(RSSidePanelClient.RS_ICONS_TEX, bx, by, 238, bgV, RSSidePanelClient.SIDE_BTN_SIZE, RSSidePanelClient.SIDE_BTN_SIZE, 256, 256);

        int u = 0, v = 0;
        switch (idx) {
            case 0: u = viewType * 16; v = 112; break;
            case 1: u = sortAsc ? 0 : 16; v = 16; break;
            case 2:
                switch (sortMode) {
                    case 0 -> { u = 16; v = 32; }
                    case 1 -> { u = 0; v = 32; }
                    case 2 -> { u = 32; v = 32; }
                    default -> { u = 48; v = 48; }
                }
                break;
            case 3: u = (searchMode % 2 == 1) ? 16 : 0; v = 96; break;
            case 4:
                u = switch (gridSize) { case 0 -> 112; case 1 -> 64; case 2 -> 80; default -> 96; };
                v = 64;
                break;
            default: { p.popPose(); return -1; }
        }
        g.blit(RSSidePanelClient.RS_ICONS_TEX, bx + 1, by + 1, u, v, 16, 16, 256, 256);
        p.popPose();

        return hovered ? idx : -1;
    }

    // ── GUI icon for bound machines ──────────────────────────────

    /** Render a small 8×8 terminal icon indicating a bound machine can be opened. */
    public static void renderGuiIcon(GuiGraphics g, int x, int y, boolean hovered) {
        int bg = hovered ? 0xFF5588BB : 0xFF445566;
        int border = hovered ? 0xFFAACCDD : 0xFF667788;
        int dot = hovered ? 0xFFCCDDEE : 0xFF8899AA;
        g.fill(x, y, x + 8, y + 8, border);
        g.fill(x + 1, y + 1, x + 7, y + 7, bg);
        g.fill(x + 2, y + 2, x + 6, y + 3, dot);
        g.fill(x + 3, y + 4, x + 4, y + 5, dot);
        g.fill(x + 5, y + 4, x + 6, y + 5, dot);
    }

    // ── Scrollbar ────────────────────────────────────────────────

    /** Render the scrollbar thumb. No-op if all rows are visible. */
    public static void renderScrollbar(GuiGraphics g,
                                        int panelX, int panelY,
                                        int visibleRows, int scrollRow,
                                        int totalRows) {
        if (totalRows <= visibleRows) return;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) return;

        int sx = panelX + RSSidePanelClient.SCROLLBAR_X;
        int sy = panelY + RSSidePanelClient.HEADER_H + 2;
        int trackH = visibleRows * RSSidePanelClient.SLOT_SIZE - 4;
        int thumbH = 15;
        int trackAvail = Math.max(1, trackH - thumbH);
        int thumbY = sy + (int) Math.round((double) trackAvail * scrollRow / maxScroll);

        g.blit(RSSidePanelClient.RS_ICONS_TEX, sx, thumbY, 232, 0, 12, 15, 256, 256);
    }

    // ── Tooltips ─────────────────────────────────────────────────

    /** Render the item tooltip with a storage count line appended. */
    @SuppressWarnings("resource")
    public static void renderItemTooltip(GuiGraphics g, Font font,
                                          ItemStack stack, int mx, int my) {
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                Minecraft.getInstance().player,
                Minecraft.getInstance().options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        int stored = stack.getCount();
        if (stored > 0)
            lines.add(Component.literal("§7" +
                    Component.translatable("rsi.side_panel.total", formatCount(stored)).getString()));
        g.renderComponentTooltip(font, lines, mx, my);
    }

    /** Render tooltip for a side button showing current mode. */
    public static void renderSideButtonTooltip(GuiGraphics g, Font font, int idx,
                                                int viewType, boolean sortAsc,
                                                int sortMode, int searchMode,
                                                int gridSize,
                                                int mouseX, int mouseY) {
        String label, mode;
        switch (idx) {
            case 0:
                label = Component.translatable("rsi.side_panel.btn.view_type").getString();
                String vk = switch (viewType) {
                    case 1 -> "rsi.side_panel.btn.view_type.non_craftables";
                    case 2 -> "rsi.side_panel.btn.view_type.craftables";
                    default -> "rsi.side_panel.btn.view_type.all";
                };
                mode = Component.translatable(vk).getString();
                break;
            case 1:
                label = Component.translatable("rsi.side_panel.btn.sort_dir").getString();
                mode = Component.translatable(sortAsc
                        ? "rsi.side_panel.btn.sort_dir.asc"
                        : "rsi.side_panel.btn.sort_dir.desc").getString();
                break;
            case 2:
                label = Component.translatable("rsi.side_panel.btn.sort_type").getString();
                String mk = switch (sortMode) {
                    case 0 -> "rsi.side_panel.btn.sort_type.name";
                    case 1 -> "rsi.side_panel.btn.sort_type.count";
                    case 2 -> "rsi.side_panel.btn.sort_type.id";
                    default -> "rsi.side_panel.btn.sort_type.last_modified";
                };
                mode = Component.translatable(mk).getString();
                break;
            case 3:
                label = Component.translatable("rsi.side_panel.btn.search_mode").getString();
                String sk = switch (searchMode) {
                    case 0 -> "rsi.side_panel.btn.search_mode.normal";
                    case 1 -> "rsi.side_panel.btn.search_mode.normal_auto";
                    case 2 -> "rsi.side_panel.btn.search_mode.jei";
                    case 3 -> "rsi.side_panel.btn.search_mode.jei_auto";
                    case 4 -> "rsi.side_panel.btn.search_mode.jei_2way";
                    default -> "rsi.side_panel.btn.search_mode.jei_2way_auto";
                };
                mode = Component.translatable(sk).getString();
                List<Component> extra = new ArrayList<>();
                extra.add(Component.literal(label));
                extra.add(Component.literal("§7" + mode));
                extra.add(Component.literal(""));
                extra.add(Component.literal("§b" + Component.translatable("rsi.side_panel.search.prefix_mod").getString()));
                extra.add(Component.literal("§b" + Component.translatable("rsi.side_panel.search.prefix_tooltip").getString()));
                g.renderComponentTooltip(font, extra, mouseX, mouseY);
                return;
            case 4:
                label = Component.translatable("rsi.side_panel.btn.grid_size").getString();
                String gk = switch (gridSize) {
                    case 0 -> "rsi.side_panel.btn.grid_size.stretch";
                    case 1 -> "rsi.side_panel.btn.grid_size.small";
                    case 2 -> "rsi.side_panel.btn.grid_size.medium";
                    default -> "rsi.side_panel.btn.grid_size.large";
                };
                mode = Component.translatable(gk).getString();
                break;
            default: return;
        }
        g.renderComponentTooltip(font,
                List.of(Component.literal(label), Component.literal("§7" + mode)),
                mouseX, mouseY);
    }

    // ── Slot quantity label ──────────────────────────────────────

    /**
     * Render a quantity/craft label on a grid slot (bottom-right aligned).
     * Matches RS {@code BaseScreen.renderQuantity} pattern with auto-scale for long labels.
     */
    public static void renderSlotQuantity(GuiGraphics g, Font font,
                                           int ix, int iy,
                                           String label, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 200);

        int textW = font.width(label);
        if (textW > 16) {
            float scale = 14.0F / textW;
            pose.scale(scale, scale, 1);
            g.drawString(font, label,
                    (int) ((ix + 16) / scale - textW),
                    (int) ((iy + 14) / scale - font.lineHeight),
                    color);
        } else {
            g.drawString(font, label,
                    ix + 17 - textW,
                    iy + 14 - font.lineHeight + 2,
                    color);
        }

        pose.popPose();
    }

    // ── Lock icon ─────────────────────────────────────────────────

    /** Render a 6×7 amber padlock icon at (x, y). */
    public static void renderLockIcon(GuiGraphics g, int x, int y) {
        int amber = 0xFFDAA520;
        int amberLight = 0xFFF0D060;
        int keyhole = 0xFF3C2415;

        // Shackle
        g.fill(x + 2, y, x + 4, y + 1, amberLight);
        g.fill(x + 1, y + 1, x + 2, y + 3, amberLight);
        g.fill(x + 4, y + 1, x + 5, y + 3, amberLight);

        // Body
        g.fill(x + 1, y + 2, x + 5, y + 7, amber);

        // Keyhole
        g.fill(x + 2, y + 4, x + 3, y + 6, keyhole);
        g.fill(x + 3, y + 5, x + 4, y + 6, keyhole);

        // Top highlight
        g.fill(x + 2, y + 2, x + 4, y + 3, amberLight);
    }

    // ── Quantity formatter ───────────────────────────────────────

    /** Format a count with K/M/B suffixes. */
    public static String formatCount(int count) {
        if (count >= 1_000_000_000)
            return (count / 1_000_000_000) + "." + ((count / 100_000_000) % 10) + "B";
        if (count >= 1_000_000) {
            if (count >= 100_000_000) return (count / 1_000_000) + "M";
            return (count / 1_000_000) + "." + ((count / 100_000) % 10) + "M";
        }
        if (count >= 1_000) {
            if (count >= 100_000) return (count / 1_000) + "K";
            return (count / 1_000) + "." + ((count / 100) % 10) + "K";
        }
        return String.valueOf(count);
    }

    // ── Result type ──────────────────────────────────────────────

    /** Result returned by {@link #renderPanel}. */
    public static final class PanelRenderResult {
        public final int hoveredSideButton;
        public final int hoveredSlotIndex;

        public PanelRenderResult(int hoveredSideButton, int hoveredSlotIndex) {
            this.hoveredSideButton = hoveredSideButton;
            this.hoveredSlotIndex = hoveredSlotIndex;
        }
    }

    // ── Animation provider interface ─────────────────────────────

    /**
     * Interface for slot animations so the renderer does not depend on
     * {@code PanelDataModel.SlotAnim} directly — both that class and
     * {@code RSSidePanelClient.SlotAnim} can be used.
     */
    public interface SlotAnimProvider {
        /** The signed delta value (positive = added, negative = removed). */
        int deltaValue();
        /** Whether the animation duration has elapsed. */
        boolean expired();
        /** Opacity fade factor (1.0 → 0.0). */
        float fade();
    }
}
