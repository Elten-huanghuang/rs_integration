package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.MaterialNote;
import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 主内容区的材料整页，与树视图切换时保留相机和选中节点。 */
public final class PlanMaterialTable {
    private record Row(IngredientKey key, long needed, long available, long missing, boolean leftover,
                       String sourceKey) {}
    private record Hit(Rect bounds, Row row) {}
    private final Font font;
    private final PlanWorkspaceChrome chrome;
    private final Consumer<IngredientKey> locate;
    private final BiConsumer<ItemStack, Long> bookmark;
    private final Runnable bookmarkAll;
    private List<Row> rows = List.of();
    private List<Row> filtered = List.of();
    private boolean filterDirty = true;
    private final List<Hit> hits = new ArrayList<>();
    private Rect bounds;
    private boolean onlyMissing, incomplete;
    private PlanGraphView graph;
    private final Set<IngredientKey> unresolved = new HashSet<>();
    private String query = "";
    private int scroll, maxScroll;
    private ItemStack hoveredItem = ItemStack.EMPTY;

    public PlanMaterialTable(Font font, Consumer<IngredientKey> locate,
                             BiConsumer<ItemStack, Long> bookmark, Runnable bookmarkAll) {
        this.font = font;
        this.chrome = new PlanWorkspaceChrome(font);
        this.locate = locate;
        this.bookmark = bookmark;
        this.bookmarkAll = bookmarkAll;
    }

    public void update(PlanResponse plan) {
        incomplete = plan.outcome().incomplete();
        if (incomplete) onlyMissing = false;
        if (graph != plan.graph()) {
            graph = plan.graph();
            unresolved.clear();
            if (graph != null && !incomplete) {
                graph.visitUnresolvedDemand((stack, count) -> unresolved.add(IngredientKey.of(stack)));
            }
        }
        List<Row> data = new ArrayList<>();
        var gaps = PlanMaterialBill.draftGaps(plan);
        plan.materials().forEach((key, value) -> data.add(new Row(key, value.needed(),
                value.available(), incomplete ? gaps.getOrDefault(key, 0L) : value.missingCount(), false,
                materialSourceKey(value, unresolved.contains(key)))));
        gaps.forEach((key, quantity) -> {
            if (!plan.materials().containsKey(key)) data.add(new Row(key, 0, -1, quantity, false, "unconfirmed_source"));
        });
        data.sort(Comparator.comparing((Row row) -> row.missing() == 0)
                .thenComparing(row -> row.key().stack(1).getHoverName().getString()));
        plan.leftovers().forEach((key, count) -> data.add(new Row(key, count, 0, 0, true, "leftovers")));
        rows = List.copyOf(data);
        filterDirty = true;
    }

    static String materialSourceKey(PlanResponse.Availability value, boolean unresolved) {
        if (value.missingCount() > 0) return "missing_source";
        // 无图轨迹的需求差额可能来自未展开步骤或替代材料，不能当成已安排生产。
        if (unresolved || value.available() < value.needed()) return "unconfirmed_source";
        return "ready_source";
    }

    public void search(String query) {
        if (!this.query.equals(query)) { this.query = query; scroll = 0; filterDirty = true; }
    }

    public void render(GuiGraphics g, Rect bounds, int mouseX, int mouseY, PlanExtensionState extensions) {
        this.bounds = bounds;
        hits.clear();
        hoveredItem = ItemStack.EMPTY;
        List<MaterialNote> hoveredNotes = List.of();
        chrome.resetActions();
        PlanWorkspaceChrome.panel(g, bounds, Color.BACKGROUND, Color.BORDER);
        int x = bounds.x() + 7, width = bounds.width() - 14;
        chrome.button(g, new Rect(x, bounds.y() + 6, 62, 18), text(incomplete ? "draft_materials" : "all_materials"),
                !onlyMissing, true, mouseX, mouseY, () -> { onlyMissing = false; scroll = 0; filterDirty = true; });
        chrome.button(g, new Rect(x + 66, bounds.y() + 6, 62, 18), text(incomplete ? "only_draft_gaps" : "only_missing"),
                onlyMissing, true, mouseX, mouseY, () -> { onlyMissing = true; scroll = 0; filterDirty = true; });
        chrome.button(g, new Rect(bounds.right() - 105, bounds.y() + 6, 98, 18), text("bookmark_all_short"),
                false, !incomplete && rows.stream().anyMatch(row -> row.missing() > 0), mouseX, mouseY, bookmarkAll);
        boolean wide = width >= 500;
        int nameEnd = x + width * (wide ? 36 : 34) / 100;
        int countWidth = width * 11 / 100;
        int needX = nameEnd, stockX = nameEnd + countWidth, missingX = stockX + countWidth;
        int sourceX = missingX + countWidth;
        int actionX = bounds.right() - (wide ? 64 : 45);
        int headerY = bounds.y() + 48;
        g.fill(x, headerY - 9, x + width, headerY + 14, Color.SURFACE.value());
        label(g, text("column_material"), x + 5, headerY, nameEnd - x - 10, Color.MUTED);
        label(g, text(incomplete ? "settled_source" : "column_need"), needX, headerY, countWidth - 2, Color.MUTED);
        label(g, text(incomplete ? "snapshot_stock" : "column_stock"), stockX, headerY, countWidth - 2, Color.MUTED);
        label(g, text(incomplete ? "draft_gap" : "column_missing"), missingX, headerY, countWidth - 2, Color.MUTED);
        if (wide) label(g, text("column_source"), sourceX, headerY, actionX - sourceX - 4, Color.MUTED);
        int top = headerY + 17, bottom = bounds.bottom() - 19;
        int rowHeight = extensions.hasNotes() ? 40 : 30;
        if (filterDirty) {
            filtered = rows.stream().filter(row -> !onlyMissing || row.missing() > 0)
                .filter(row -> MaterialSearchMatcher.matches(row.key().stack(1).getHoverName().getString(),
                        BuiltInRegistries.ITEM.getKey(row.key().stack(1).getItem()).toString(), query)).toList();
            filterDirty = false;
        }
        List<Row> visible = filtered;
        maxScroll = Math.max(0, visible.size() * rowHeight - Math.max(1, bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (bottom > top) {
            g.enableScissor(x, top, x + width, bottom);
            try {
                int y = top - scroll;
                for (int index = 0; index < visible.size(); index++) {
                    Row row = visible.get(index);
                    if (y + rowHeight > top && y < bottom) {
                        Rect hit = new Rect(x, Math.max(y, top), width, Math.min(y + rowHeight - 2, bottom) - Math.max(y, top));
                        Color fill = hit.contains(mouseX, mouseY) ? Color.HOVER
                                : row.missing() > 0 ? Color.WARNING_SURFACE : index % 2 == 0 ? Color.SURFACE : Color.PANEL;
                        g.fill(x, y, x + width, y + rowHeight - 2, fill.value());
                        ItemStack stack = row.key().stack(1);
                        List<MaterialNote> notes = row.leftover() ? List.of() : extensions.notes(stack);
                        if (hit.contains(mouseX, mouseY) && (mouseX < x + 25 || !notes.isEmpty())) {
                            hoveredItem = stack;
                            hoveredNotes = notes;
                        }
                        InkFluidRenderer.render(g, stack, x + 4, y + 3);
                        label(g, stack.getHoverName().getString(), x + 26, y + 7, nameEnd - x - 30, Color.TEXT);
                        label(g, PlanRenderEngine.formatCompact(row.needed()), needX, y + 7, countWidth - 2, Color.TEXT);
                        label(g, row.leftover() || row.available() < 0 ? "—" : PlanRenderEngine.formatCompact(row.available()), stockX,
                                y + 7, countWidth - 2, Color.MUTED);
                        label(g, row.missing() == 0 ? "—" : PlanRenderEngine.formatCompact(row.missing()),
                                missingX, y + 7, countWidth - 2, row.missing() > 0 ? Color.WARNING : Color.MUTED);
                        if (wide) label(g, text(incomplete ? (row.missing() > 0 ? "draft_gap" : "settled_source") : row.sourceKey()),
                                sourceX, y + 7, actionX - sourceX - 4, row.missing() > 0 ? Color.WARNING
                                        : row.sourceKey().equals("ready_source") ? Color.READY : Color.MUTED);
                        label(g, text(!incomplete && row.missing() > 0 ? "locate_star" : "locate_short"), actionX, y + 7,
                                bounds.right() - actionX - 8, Color.MUTED);
                        if (!notes.isEmpty()) label(g, notes.stream().map(MaterialNote::note)
                                        .filter(note -> !note.isBlank()).collect(Collectors.joining(" | ")),
                                x + 26, y + 23, actionX - x - 30, Color.MUTED);
                        hits.add(new Hit(hit, row));
                    }
                    y += rowHeight;
                }
            } finally { g.disableScissor(); }
        }
        label(g, text(incomplete ? "draft_gap_hint" : "table_hint"),
                x, bounds.bottom() - 13, width, Color.MUTED);
        if (maxScroll > 0 && bottom > top) {
            int trackHeight = bottom - top;
            int thumbHeight = Math.min(trackHeight, Math.max(8, trackHeight * trackHeight / (trackHeight + maxScroll)));
            int thumbY = top + (trackHeight - thumbHeight) * scroll / maxScroll;
            g.fill(bounds.right() - 5, top, bounds.right() - 3, bottom, Color.SURFACE.value());
            g.fill(bounds.right() - 5, thumbY, bounds.right() - 3, thumbY + thumbHeight, Color.ACCENT.value());
        }
        if (visible.isEmpty()) label(g, text(incomplete && rows.isEmpty() ? "no_settled_materials" : "empty"),
                x + 8, top + 6, width - 16, Color.MUTED);
        if (!hoveredItem.isEmpty() && hoveredNotes.isEmpty()) g.renderTooltip(font, hoveredItem, mouseX, mouseY);
        else if (!hoveredItem.isEmpty()) {
            List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), hoveredItem));
            for (MaterialNote note : hoveredNotes) {
                if (!note.note().isBlank()) tooltip.add(Component.literal(note.note()));
                note.tooltipLines().forEach(line -> tooltip.add(Component.literal(line)));
            }
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    public boolean click(double x, double y, int button) {
        if (bounds == null || !bounds.contains(x, y)) return false;
        if (chrome.click(x, y, button)) return true;
        for (Hit hit : hits) if (hit.bounds().contains(x, y)) {
            if (!incomplete && hit.row().missing() > 0 && (button == 1 || button == 2 || x >= bounds.right() - 24)) {
                bookmark.accept(hit.row().key().stack(1), hit.row().missing());
            } else if (button == 0) locate.accept(hit.row().key());
            return true;
        }
        return true;
    }

    public void scroll(double delta) { scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 50))); }
    private void label(GuiGraphics g, String value, int x, int y, int width, Color color) { chrome.label(g, value, x, y, width, color); }
    private static String text(String key) { return Component.translatable("rsi.plan.workspace." + key).getString(); }
}
