package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.config.CraftingUiColors;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.client.gui.ScrollBarStyle;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.crafting.progress.ExecutionMonitorClient;
import com.huanghuang.rsintegration.util.PinyinUtil;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.huanghuang.rsintegration.machine.client.MachineDisplayNameResolver;
import com.huanghuang.rsintegration.machine.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.machine.client.MachineEntryPolicy;
import com.huanghuang.rsintegration.machine.client.MachineTabHandler;
import com.huanghuang.rsintegration.machine.client.MachineTabRenderer;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 平铺的绑定机器中心：信息卡片、改名、收藏/解绑，以及右键框选自动化黑名单。 */
public final class MachineManagementScreen extends Screen {
    public enum View {
        GUI_ONLY,
        ALL_BOUND
    }

    private static final int BLOCKED_TINT = 0xFFFFF0F1;
    private static final int SELECTED_TINT = 0xFFE5E6F1;
    private static final int SELECTION_FILL = 0x406F6C89;
    private static final int GRID_GAP = 8;
    private static final int MIN_TILE_WIDTH = 132;
    private static final int TILE_HEIGHT = 68;
    private static final double DRAG_THRESHOLD = 3.0;

    private final List<BindingInfo> machines = new ArrayList<>();
    private final List<BindingInfo> filtered = new ArrayList<>();
    private final View view;
    @Nullable
    private final Screen parent;
    private String searchText = "";
    private int scrollRow;
    private EditBox searchBox;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;
    private int tileWidth;
    private int footerHeight;
    private boolean selectingBlacklist;
    private double selectionStartX;
    private double selectionStartY;
    private double selectionCurrentX;
    private double selectionCurrentY;
    @Nullable
    private BindingInfo rightClickStartMachine;

    public MachineManagementScreen(@Nullable Screen parent, View view) {
        super(Component.translatable(view == View.GUI_ONLY
                ? "rsi.control_center.gui_machines"
                : "rsi.control_center.all_machines"));
        this.view = view == null ? View.ALL_BOUND : view;
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        clearSelection();
        updateLayout();
        refreshMachines();
        searchBox = new EditBox(font, panelX + 18, panelY + 91,
                Math.max(1, panelWidth - 36), 12,
                Component.translatable("rsi.control_center.search"));
        searchBox.setBordered(false);
        searchBox.setTextColor(CraftingUiStyle.text());
        searchBox.setTextColorUneditable(CraftingUiStyle.muted());
        searchBox.setHint(Component.translatable("rsi.control_center.search_hint"));
        searchBox.setMaxLength(96);
        searchBox.setValue(searchText);
        searchBox.setResponder(value -> {
            searchText = value;
            scrollRow = 0;
            refilter();
        });
        addRenderableWidget(searchBox);
        int navigationWidth = (panelWidth - 36) / 3;
        addRenderableWidget(new ActionButton(panelX + 12, panelY + 52, navigationWidth, 22,
                Component.translatable("rsi.planning.tasks.open"), false,
                button -> minecraft.setScreen(new PlanningTasksScreen(this))));
        addRenderableWidget(new ActionButton(panelX + 18 + navigationWidth, panelY + 52, navigationWidth, 22,
                Component.translatable("rsi.planning.tasks.executions"), false,
                button -> ExecutionMonitorClient.open()));
        addRenderableWidget(new ActionButton(panelX + 24 + navigationWidth * 2, panelY + 52, navigationWidth, 22,
                Component.translatable("rsi.control_center.machines"), true, button -> {}));
        addRenderableWidget(new ActionButton(panelX + panelWidth - 106,
                panelY + panelHeight - footerHeight + (footerHeight - 22) / 2, 94, 22,
                Component.translatable("gui.back"), false, button -> onClose()));
        MachineTabHandler.requestBindingSync();
    }

    @Override
    public void tick() {
        if (searchBox != null) searchBox.tick();
        refreshMachines();
    }

    private void updateLayout() {
        panelWidth = Math.max(1, Math.min(620, width - 24));
        panelHeight = Math.max(1, Math.min(420, height - 24));
        footerHeight = panelHeight < 228 ? 32 : 40;
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        gridX = panelX + 12;
        gridY = panelY + 112;
        gridWidth = Math.max(1, panelWidth - 28 - ScrollBarStyle.COMPACT_WIDTH);
        gridHeight = Math.max(0, panelHeight - 112 - footerHeight);
        tileWidth = Math.max(1, (gridWidth - (columns() - 1) * GRID_GAP) / columns());
    }

    private void refreshMachines() {
        machines.clear();
        for (BindingInfo info : MachineTabHandler.getAllBoundMachinesForManagement()) {
            if (view == View.GUI_ONLY
                    && !MachineEntryPolicy.isSelectable(info)) continue;
            machines.add(info);
        }
        machines.sort(Comparator
                .comparing(this::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(info -> info.dim().toString())
                .thenComparing(info -> info.pos().asLong()));
        refilter();
    }

    private void refilter() {
        filtered.clear();
        String query = searchText == null ? "" : searchText.trim().toLowerCase();
        for (BindingInfo info : machines) {
            String name = displayName(info);
            String searchable = name + " " + info.displayName() + " "
                    + MachineDisplayNameResolver.defaultDisplayName(info).getString() + " "
                    + info.blockKey() + " " + info.dim() + " " + info.pos().toShortString();
            if (matches(query, searchable, name)) filtered.add(info);
        }
        clampScroll();
    }

    private boolean matches(String query, String searchable, String name) {
        if (query.isEmpty() || searchable.toLowerCase().contains(query)) return true;
        try {
            return PinyinUtil.toPinyin(name).contains(query)
                    || PinyinUtil.toPinyinInitials(name).contains(query);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void clampScroll() {
        int rows = visibleRows();
        int maxStart = Math.max(0, filtered.size() - columns() * rows);
        scrollRow = Math.max(0, Math.min(scrollRow, (maxStart + columns() - 1) / columns()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        searchBox.setTextColor(CraftingUiStyle.text());
        searchBox.setTextColorUneditable(CraftingUiStyle.muted());
        renderBackground(graphics);
        CraftingUiStyle.panel(graphics, panelX, panelY, panelWidth, panelHeight);
        renderMachineIcon(graphics, panelX + 14, panelY + 16);
        graphics.drawString(font, title, panelX + 36, panelY + 13, CraftingUiStyle.text(), false);
        Component hint = Component.translatable(view == View.GUI_ONLY
                ? "rsi.control_center.hint.gui" : "rsi.control_center.hint.all");
        long blocked = view == View.ALL_BOUND ? machines.stream().filter(MachinePreferenceClient::isBlocked).count() : 0;
        String blockedSummary = blocked == 0 ? "" : Component.translatable("rsi.control_center.summary.blocked", blocked).getString();
        int summaryWidth = blocked == 0 ? 0 : font.width(blockedSummary) + 12;
        graphics.drawString(font, CraftingUiStyle.fit(font, hint.getString(), panelWidth - 50 - summaryWidth),
                panelX + 36, panelY + 29, CraftingUiStyle.muted(), false);
        if (blocked > 0) graphics.drawString(font, blockedSummary,
                panelX + panelWidth - 14 - font.width(blockedSummary), panelY + 29, CraftingUiStyle.danger(), false);
        CraftingUiStyle.surface(graphics, panelX + 12, panelY + 84, panelWidth - 24, 22,
                CraftingUiColors.ROW.argb(), searchBox.isFocused() ? CraftingUiStyle.accent() : CraftingUiStyle.border());
        int footerY = panelY + panelHeight - footerHeight;
        graphics.fill(panelX + 12, footerY, panelX + panelWidth - 12, footerY + 1, CraftingUiStyle.border());
        renderSummaryBadges(graphics);
        renderGrid(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFooterHint(graphics);
        if (!selectingBlacklist) renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    private void renderSummaryBadges(GuiGraphics graphics) {
        String count = Component.translatable("rsi.control_center.summary.count",
                filtered.size(), machines.size()).getString();
        int titleEnd = panelX + 36 + font.width(title) + 8;
        int countX = panelX + panelWidth - 14 - font.width(count);
        if (countX >= titleEnd) graphics.drawString(font, count, countX, panelY + 13, CraftingUiStyle.muted(), false);
    }

    private void renderMachineIcon(GuiGraphics graphics, int x, int y) {
        CraftingUiStyle.surface(graphics, x, y, 14, 16, CraftingUiColors.ROW.argb(), CraftingUiStyle.accent());
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                graphics.fill(x + 3 + column * 5, y + 4 + row * 5,
                        x + 6 + column * 5, y + 7 + row * 5, CraftingUiStyle.accent());
            }
        }
    }

    private void renderFooterHint(GuiGraphics graphics) {
        String key = view == View.GUI_ONLY
                ? "rsi.control_center.hint.gui_footer"
                : "rsi.control_center.hint.selection";
        String hint = Component.translatable(key).getString();
        int maxWidth = Math.max(1, panelWidth - 130);
        int y = panelY + panelHeight - footerHeight + (footerHeight - font.lineHeight) / 2;
        graphics.drawString(font, CraftingUiStyle.fit(font, hint, maxWidth), panelX + 14, y, CraftingUiStyle.muted(), false);
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filtered.isEmpty()) {
            Component empty = Component.translatable("rsi.control_center.empty");
            graphics.drawString(font, empty, panelX + (panelWidth - font.width(empty)) / 2,
                    gridY + gridHeight / 2 - font.lineHeight / 2, CraftingUiStyle.muted(), false);
            return;
        }
        int columns = columns();
        int rows = visibleRows();
        int start = Math.min(scrollRow * columns, Math.max(0, filtered.size() - 1));
        int end = Math.min(filtered.size(), start + columns * rows);
        int selectedCount = 0;
        int selectedBlockedCount = 0;
        for (int index = start; index < end; index++) {
            int slot = index - start;
            int column = slot % columns;
            int row = slot / columns;
            int x = gridX + column * (tileWidth + GRID_GAP);
            int y = gridY + row * (TILE_HEIGHT + GRID_GAP);
            BindingInfo info = filtered.get(index);
            boolean selected = selectingBlacklist
                    && selectionDragged()
                    && tileIntersectsSelection(x, y);
            if (selected) {
                selectedCount++;
                if (MachinePreferenceClient.isBlocked(info)) selectedBlockedCount++;
            }
            renderTile(graphics, info, x, y, mouseX, mouseY, selected);
        }
        if (selectingBlacklist && selectionDragged()) {
            boolean willBlock = selectedCount > 0 && selectedBlockedCount < selectedCount;
            renderSelection(graphics, selectedCount, willBlock);
        }
        if (filtered.size() > columns * rows) {
            renderScrollbar(graphics, panelX + panelWidth - 12 - ScrollBarStyle.COMPACT_WIDTH, gridY, gridHeight,
                    columns, rows);
        }
    }

    private void renderTile(GuiGraphics graphics, BindingInfo info,
                            int x, int y, int mouseX, int mouseY, boolean selected) {
        boolean blocked = MachinePreferenceClient.isBlocked(info);
        boolean hovered = !selectingBlacklist
                && inside(mouseX, mouseY, x, y, tileWidth, TILE_HEIGHT);
        int accent = blocked ? CraftingUiStyle.danger() : CraftingUiStyle.accent();
        int background = selected ? SELECTED_TINT : blocked ? BLOCKED_TINT : hovered ? CraftingUiStyle.highlight() : CraftingUiColors.ROW.argb();
        CraftingUiStyle.surface(graphics, x, y, tileWidth, TILE_HEIGHT, background,
                selected || hovered ? CraftingUiStyle.accent() : CraftingUiStyle.border());
        graphics.fill(x, y + 5, x + 2, y + TILE_HEIGHT - 5, accent);
        graphics.fill(x + 8, y + 8, x + 34, y + 34, CraftingUiStyle.canvas());

        ItemStack icon = MachineTabRenderer.resolveIcon(info);
        int iconX = x + 13;
        int iconY = y + 13;
        if (!icon.isEmpty()) InkFluidRenderer.render(graphics, icon, iconX, iconY);

        int textX = x + 42;
        int nameWidth = Math.max(1, tileWidth - 60);
        String name = CraftingUiStyle.fit(font, displayName(info), nameWidth);
        graphics.drawString(font, name, textX, y + 8, CraftingUiStyle.text(), false);
        boolean showBlocked = blocked && view == View.ALL_BOUND;
        String status = showBlocked
                ? Component.translatable("rsi.control_center.flag.blocked_short").getString()
                : dimensionLabel(info);
        graphics.drawString(font, CraftingUiStyle.fit(font, status, tileWidth - 50),
                textX, y + 24, showBlocked ? CraftingUiStyle.danger() : CraftingUiStyle.muted(), false);

        graphics.fill(x + 8, y + 40, x + tileWidth - 8, y + 41, CraftingUiStyle.gridLine());
        graphics.drawString(font, "XYZ", x + 8, y + 49, CraftingUiStyle.accent(), false);
        String coords = info.pos().getX() + ", " + info.pos().getY()
                + ", " + info.pos().getZ();
        graphics.drawString(font,
                CraftingUiStyle.fit(font, coords, tileWidth - 60),
                x + 32, y + 49, CraftingUiStyle.muted(), false);
        if (canRename(info)) renderRenameIcon(graphics, x + tileWidth - 23, y + 45,
                inside(mouseX, mouseY, x + tileWidth - 23, y + 45, 16, 16));

        if (view == View.GUI_ONLY) {
            graphics.drawString(font, MachinePreferenceClient.isFavorite(info) ? "*" : "-",
                    x + tileWidth - 11, y + 3,
                    MachinePreferenceClient.isFavorite(info) ? CraftingUiStyle.warning() : CraftingUiStyle.hint(), false);
        } else {
            graphics.drawString(font, "x",
                    x + tileWidth - 10, y + 3, hovered ? CraftingUiStyle.danger() : CraftingUiStyle.muted(), false);
        }
    }

    private void renderRenameIcon(GuiGraphics graphics, int x, int y, boolean hovered) {
        if (hovered) CraftingUiStyle.surface(graphics, x, y, 16, 16,
                CraftingUiStyle.highlight(), CraftingUiStyle.accent());
        for (int step = 0; step < 6; step++) {
            graphics.fill(x + 4 + step, y + 10 - step,
                    x + 6 + step, y + 12 - step, CraftingUiStyle.accent());
        }
        graphics.fill(x + 3, y + 12, x + 5, y + 13, CraftingUiStyle.text());
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        BindingInfo hovered = hoveredMachine(mouseX, mouseY);
        if (hovered == null) return;
        List<Component> lines = new ArrayList<>();
        lines.add(displayComponent(hovered));
        if (!hovered.customName().isEmpty()) {
            lines.add(MachineDisplayNameResolver.defaultDisplayName(hovered)
                    .copy().withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal(hovered.dim() + " " + hovered.pos().toShortString())
                .withStyle(ChatFormatting.DARK_GRAY));
        boolean blocked = MachinePreferenceClient.isBlocked(hovered);
        if (canRename(hovered)) {
            lines.add(Component.translatable("rsi.control_center.hint.rename")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (MachineEntryPolicy.isSelectable(hovered)) {
            lines.add(Component.translatable("rsi.control_center.hint.open")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (view == View.GUI_ONLY) {
            lines.add(Component.translatable("rsi.control_center.hint.favorite")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("rsi.control_center.hint.unbind")
                    .withStyle(ChatFormatting.RED));
            lines.add(Component.translatable(blocked
                            ? "rsi.control_center.hint.allow_automation"
                            : "rsi.control_center.hint.block_automation")
                    .withStyle(blocked
                            ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        }
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y, int height,
                                 int columns, int rows) {
        int totalRows = Math.max(1, (filtered.size() + columns - 1) / columns);
        int visible = Math.min(rows, totalRows);
        int maxScroll = Math.max(1, totalRows - visible);
        ScrollBarStyle.render(graphics, x, y, ScrollBarStyle.COMPACT_WIDTH, height,
                scrollRow, maxScroll, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (view == View.ALL_BOUND && button == 1
                && inside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            selectingBlacklist = true;
            rightClickStartMachine = hoveredMachine(mouseX, mouseY);
            selectionStartX = clampX(mouseX);
            selectionStartY = clampY(mouseY);
            selectionCurrentX = selectionStartX;
            selectionCurrentY = selectionStartY;
            return true;
        }
        BindingInfo info = hoveredMachine(mouseX, mouseY);
        if (info != null) {
            TileBounds bounds = hoveredTileBounds(mouseX, mouseY);
            if (canRename(info) && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + tileWidth - 23, bounds.y() + 45, 16, 16) && button == 0) {
                minecraft.setScreen(new MachineRenameScreen(this, info));
                return true;
            }
            if (view == View.ALL_BOUND && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + tileWidth - 13, bounds.y() + 1, 12, 12)) {
                if (button == 0) {
                    MachineTabHandler.onUnbind(info);
                    machines.remove(info);
                    refilter();
                }
                return true;
            }
            if (view == View.GUI_ONLY && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + tileWidth - 13, bounds.y() + 1, 13, 13)) {
                if (button == 0) {
                    MachinePreferenceClient.requestFavoriteToggle(info);
                    refreshMachines();
                }
                return true;
            }
            if (button == 0 && MachineEntryPolicy.isSelectable(info)) {
                MachineTabHandler.onClick(info);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (selectingBlacklist && button == 1) {
            selectionCurrentX = clampX(mouseX);
            selectionCurrentY = clampY(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selectingBlacklist && button == 1) {
            selectionCurrentX = clampX(mouseX);
            selectionCurrentY = clampY(mouseY);
            if (!selectionDragged()) {
                BindingInfo info = rightClickStartMachine;
                if (info != null) {
                    MachinePreferenceClient.requestAutomationBlockToggle(info);
                    refreshMachines();
                }
            } else {
                List<BindingInfo> selected = machinesInSelection();
                if (!selected.isEmpty()) {
                    boolean allBlocked = selected.stream()
                            .allMatch(MachinePreferenceClient::isBlocked);
                    MachinePreferenceClient.requestAutomationBlocked(
                            selected, !allBlocked);
                    refreshMachines();
                }
            }
            clearSelection();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (selectingBlacklist) clearSelection();
        if (inside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            scrollRow = Math.max(0, scrollRow - (int) Math.signum(delta));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int columns() {
        return Math.max(1, (gridWidth + GRID_GAP) / (MIN_TILE_WIDTH + GRID_GAP));
    }

    private int visibleRows() {
        return Math.max(1, (gridHeight + GRID_GAP) / (TILE_HEIGHT + GRID_GAP));
    }

    @Nullable
    private BindingInfo hoveredMachine(double mouseX, double mouseY) {
        TileBounds bounds = hoveredTileBounds(mouseX, mouseY);
        return bounds == null ? null : bounds.info();
    }

    @Nullable
    private TileBounds hoveredTileBounds(double mouseX, double mouseY) {
        int columns = columns();
        int rows = visibleRows();
        int start = Math.min(scrollRow * columns, Math.max(0, filtered.size() - 1));
        int end = Math.min(filtered.size(), start + columns * rows);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            int x = gridX + (slot % columns) * (tileWidth + GRID_GAP);
            int y = gridY + (slot / columns) * (TILE_HEIGHT + GRID_GAP);
            if (inside(mouseX, mouseY, x, y, tileWidth, TILE_HEIGHT)) {
                return new TileBounds(x, y, filtered.get(index));
            }
        }
        return null;
    }

    private boolean selectionDragged() {
        return Math.abs(selectionCurrentX - selectionStartX) >= DRAG_THRESHOLD
                || Math.abs(selectionCurrentY - selectionStartY) >= DRAG_THRESHOLD;
    }

    private boolean tileIntersectsSelection(int x, int y) {
        double left = Math.min(selectionStartX, selectionCurrentX);
        double top = Math.min(selectionStartY, selectionCurrentY);
        double right = Math.max(selectionStartX, selectionCurrentX);
        double bottom = Math.max(selectionStartY, selectionCurrentY);
        return left < x + tileWidth && right > x
                && top < y + TILE_HEIGHT && bottom > y;
    }

    private List<BindingInfo> machinesInSelection() {
        int columns = columns();
        int rows = visibleRows();
        int start = Math.min(scrollRow * columns, Math.max(0, filtered.size() - 1));
        int end = Math.min(filtered.size(), start + columns * rows);
        List<BindingInfo> selected = new ArrayList<>();
        for (int index = start; index < end; index++) {
            int slot = index - start;
            int x = gridX + (slot % columns) * (tileWidth + GRID_GAP);
            int y = gridY + (slot / columns) * (TILE_HEIGHT + GRID_GAP);
            if (tileIntersectsSelection(x, y)) selected.add(filtered.get(index));
        }
        return selected;
    }

    private void renderSelection(GuiGraphics graphics, int selectedCount,
                                 boolean willBlock) {
        int x = (int) Math.floor(Math.min(selectionStartX, selectionCurrentX));
        int y = (int) Math.floor(Math.min(selectionStartY, selectionCurrentY));
        int right = (int) Math.ceil(Math.max(selectionStartX, selectionCurrentX));
        int bottom = (int) Math.ceil(Math.max(selectionStartY, selectionCurrentY));
        int width = Math.max(1, right - x);
        int height = Math.max(1, bottom - y);
        graphics.fill(x, y, right, bottom, SELECTION_FILL);
        graphics.renderOutline(x, y, width, height, CraftingUiStyle.accent());
        if (selectedCount > 0) {
            String count = Component.translatable(
                    willBlock
                            ? "rsi.control_center.selection.block"
                            : "rsi.control_center.selection.allow",
                    selectedCount).getString();
            int badgeWidth = font.width(count) + 10;
            int badgeX = Math.max(gridX, Math.min(right + 4, panelX + panelWidth - badgeWidth - 8));
            int badgeY = Math.max(gridY + 2, y - 15);
            CraftingUiStyle.surface(graphics, badgeX, badgeY, badgeWidth, 14,
                    CraftingUiStyle.highlight(), CraftingUiStyle.accent());
            graphics.drawString(font, count, badgeX + 5, badgeY + 3, CraftingUiStyle.text(), false);
        }
    }

    private void clearSelection() {
        selectingBlacklist = false;
        selectionStartX = 0;
        selectionStartY = 0;
        selectionCurrentX = 0;
        selectionCurrentY = 0;
        rightClickStartMachine = null;
    }

    private double clampX(double x) {
        return Math.max(gridX, Math.min(gridX + gridWidth, x));
    }

    private double clampY(double y) {
        return Math.max(gridY, Math.min(gridY + gridHeight, y));
    }

    private String dimensionLabel(BindingInfo info) {
        return "minecraft".equals(info.dim().getNamespace())
                ? info.dim().getPath()
                : info.dim().toString();
    }

    private Component displayComponent(BindingInfo info) {
        return MachineDisplayNameResolver.displayName(info);
    }

    private static boolean canRename(BindingInfo info) {
        return info.bindingBookId() != null && info.endpointId() != null;
    }

    private String displayName(BindingInfo info) {
        return displayComponent(info).getString();
    }

    private static boolean inside(double mouseX, double mouseY,
                                  int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void onClose() {
        clearSelection();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record TileBounds(int x, int y, BindingInfo info) {}
}
