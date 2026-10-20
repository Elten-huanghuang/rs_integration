package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabRenderer;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 平铺的绑定机器中心：信息卡片、收藏/解绑，以及右键框选自动化黑名单。 */
public final class MachineManagementScreen extends Screen {
    public enum View {
        GUI_ONLY,
        ALL_BOUND
    }

    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFF9DA9B1;
    private static final int SURFACE = 0xB8172026;
    private static final int SURFACE_HOVER = 0xD0253139;
    private static final int DIVIDER = 0x443F505A;
    private static final int BLOCKED_TINT = 0x55E05252;
    private static final int SELECTED_TINT = 0x554A82C8;
    private static final int SELECTION_FILL = 0x443A72C4;
    private static final int SELECTION_BORDER = 0xFF79A9EA;
    private static final int GRID_GAP = 8;
    private static final int TILE_WIDTH = 108;
    private static final int TILE_HEIGHT = 82;
    private static final double DRAG_THRESHOLD = 3.0;

    private final List<BindingInfo> machines = new ArrayList<>();
    private final List<BindingInfo> filtered = new ArrayList<>();
    private final View view;
    private String searchText = "";
    private int scrollRow;
    private EditBox searchBox;
    private Button tasksButton;
    private Button doneButton;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;
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
    }

    @Override
    protected void init() {
        updateLayout();
        refreshMachines();
        searchBox = new EditBox(font, panelX + 12, panelY + 47,
                Math.min(300, panelWidth - 24), 18,
                Component.translatable("rsi.control_center.search"));
        searchBox.setMaxLength(96);
        searchBox.setValue(searchText);
        searchBox.setResponder(value -> {
            searchText = value;
            scrollRow = 0;
            refilter();
        });
        addRenderableWidget(searchBox);
        tasksButton = addRenderableWidget(Button.builder(
                        Component.translatable("rsi.control_center.tasks"),
                        button -> minecraft.setScreen(new CraftProgressScreen()))
                .bounds(panelX + panelWidth - 112, panelY + 7, 100, 20).build());
        doneButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(panelX + panelWidth - 74, panelY + panelHeight - 28, 62, 20).build());
        MachineTabHandler.requestBindingSync();
    }

    @Override
    public void tick() {
        refreshMachines();
    }

    private void updateLayout() {
        panelWidth = Math.min(860, Math.max(320, width - 24));
        panelHeight = Math.min(520, Math.max(240, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        gridX = panelX + 12;
        gridY = panelY + 76;
        gridWidth = panelWidth - 24;
        gridHeight = panelHeight - 112;
    }

    private void refreshMachines() {
        machines.clear();
        for (BindingInfo info : MachineTabHandler.getAllBoundMachinesForManagement()) {
            if (view == View.GUI_ONLY
                    && (!BindingEventHandler.supportsGuiByInfo(info)
                    || MachinePreferenceClient.isBlocked(info))) continue;
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
        renderBackground(graphics);
        UIRenderer.roundedGradient(graphics, panelX, panelY, panelWidth, panelHeight, 9f,
                0xF21B252B, 0xF2121A1F);
        graphics.fill(panelX, panelY + 42, panelX + panelWidth, panelY + 43, DIVIDER);
        graphics.fill(panelX + 12, panelY + 68, panelX + panelWidth - 12, panelY + 69, DIVIDER);
        graphics.drawString(font, title, panelX + 15, panelY + 12, TEXT, true);
        renderSummaryBadges(graphics);
        renderGrid(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFooterHint(graphics);
        if (!selectingBlacklist) renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    private void renderSummaryBadges(GuiGraphics graphics) {
        if (panelWidth < 400) return;
        int right = panelX + panelWidth - 126;
        if (view == View.ALL_BOUND) {
            long blocked = machines.stream()
                    .filter(MachinePreferenceClient::isBlocked)
                    .count();
            String blockedText = Component.translatable(
                    "rsi.control_center.summary.blocked", blocked).getString();
            int blockedWidth = font.width(blockedText) + 14;
            right -= blockedWidth;
            UIRenderer.pillBadge(graphics, font, right, panelY + 9, blockedWidth, 17,
                    blocked > 0 ? 0x66552D31 : 0x55333D43,
                    blocked > 0 ? 0xFFFFA5A5 : 0xFFB8C4CA, blockedText);
            right -= 5;
        }
        String count = filtered.size() + "/" + machines.size();
        int badgeWidth = font.width(count) + 14;
        int badgeX = right - badgeWidth;
        if (badgeX < panelX + 15 + font.width(title.getString()) + 8) return;
        UIRenderer.pillBadge(graphics, font, badgeX, panelY + 9, badgeWidth, 17,
                0x663C5664, 0xFFD9E4EA, count);
    }

    private void renderFooterHint(GuiGraphics graphics) {
        String key = view == View.GUI_ONLY
                ? "rsi.control_center.hint.gui_footer"
                : "rsi.control_center.hint.selection";
        String hint = Component.translatable(key).getString();
        int maxWidth = Math.max(1, panelWidth - 102);
        graphics.drawString(font, font.plainSubstrByWidth(hint, maxWidth),
                panelX + 12, panelY + panelHeight - 20, MUTED, false);
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filtered.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("rsi.control_center.empty"),
                    panelX + panelWidth / 2, panelY + panelHeight / 2, MUTED);
            return;
        }
        int columns = columns();
        int rows = visibleRows();
        int start = Math.min(scrollRow * columns, Math.max(0, filtered.size() - 1));
        int end = Math.min(filtered.size(), start + columns * rows);
        int selectedCount = 0;
        for (int index = start; index < end; index++) {
            int slot = index - start;
            int column = slot % columns;
            int row = slot / columns;
            int x = gridX + column * (TILE_WIDTH + GRID_GAP);
            int y = gridY + row * (TILE_HEIGHT + GRID_GAP);
            BindingInfo info = filtered.get(index);
            boolean selected = selectingBlacklist
                    && selectionDragged()
                    && tileIntersectsSelection(x, y);
            if (selected) selectedCount++;
            renderTile(graphics, info, x, y, mouseX, mouseY, selected);
        }
        if (selectingBlacklist && selectionDragged()) renderSelection(graphics, selectedCount);
        if (filtered.size() > columns * rows) {
            renderScrollbar(graphics, panelX + panelWidth - 7, gridY, gridHeight,
                    columns, rows);
        }
    }

    private void renderTile(GuiGraphics graphics, BindingInfo info,
                            int x, int y, int mouseX, int mouseY, boolean selected) {
        boolean blocked = MachinePreferenceClient.isBlocked(info);
        boolean supportsGui = BindingEventHandler.supportsGuiByInfo(info);
        boolean hovered = !selectingBlacklist
                && inside(mouseX, mouseY, x, y, TILE_WIDTH, TILE_HEIGHT);
        UIRenderer.rounded(graphics, x, y, TILE_WIDTH, TILE_HEIGHT, 6f,
                blocked ? 0xC0441F25 : hovered ? SURFACE_HOVER : SURFACE);
        if (blocked && view == View.ALL_BOUND) {
            graphics.fill(x + 1, y + 1,
                    x + TILE_WIDTH - 1, y + TILE_HEIGHT - 1, BLOCKED_TINT);
        }
        if (selected) {
            graphics.fill(x + 1, y + 1,
                    x + TILE_WIDTH - 1, y + TILE_HEIGHT - 1, SELECTED_TINT);
        }

        ItemStack icon = MachineTabRenderer.resolveIcon(info);
        int iconX = x + 6;
        int iconY = y + 7;
        if (!icon.isEmpty()) InkFluidRenderer.render(graphics, icon, iconX, iconY);

        int textX = x + 27;
        int nameWidth = Math.max(1, x + TILE_WIDTH - 17 - textX);
        String name = font.plainSubstrByWidth(displayName(info), nameWidth);
        graphics.drawString(font, name, textX, y + 7, TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(dimensionLabel(info), TILE_WIDTH - 12),
                x + 6, y + 27, MUTED, false);

        graphics.drawString(font, "XYZ", x + 6, y + 42, 0xFF71808A, false);
        String coords = info.pos().getX() + ", " + info.pos().getY()
                + ", " + info.pos().getZ();
        graphics.drawString(font,
                font.plainSubstrByWidth(coords, TILE_WIDTH - 32),
                x + 28, y + 42, 0xFFD7E1E6, false);

        if (view == View.GUI_ONLY) {
            graphics.drawString(font, MachinePreferenceClient.isFavorite(info) ? "*" : "-",
                    x + TILE_WIDTH - 11, y + 3,
                    MachinePreferenceClient.isFavorite(info) ? 0xFF68A9E8 : 0xFF71808A, false);
        } else {
            graphics.drawString(font, "x",
                    x + TILE_WIDTH - 10, y + 3, 0xFFE07A7A, false);
        }
        if (blocked && view == View.ALL_BOUND) {
            String mark = Component.translatable("rsi.control_center.flag.blocked_short").getString();
            graphics.drawString(font, mark, x + 6, y + TILE_HEIGHT - 15,
                    0xFFFF8B8B, false);
        } else {
            String status = Component.translatable(
                    supportsGui
                            ? "rsi.control_center.status.gui"
                            : "rsi.control_center.status.automation").getString();
            graphics.drawString(font, status, x + 6, y + TILE_HEIGHT - 15,
                    supportsGui
                            ? 0xFF79B88D : MUTED, false);
        }
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        BindingInfo hovered = hoveredMachine(mouseX, mouseY);
        if (hovered == null) return;
        List<Component> lines = new ArrayList<>();
        lines.add(displayComponent(hovered));
        lines.add(Component.literal(hovered.dim() + " " + hovered.pos().toShortString())
                .withStyle(ChatFormatting.DARK_GRAY));
        boolean blocked = MachinePreferenceClient.isBlocked(hovered);
        if (!blocked && BindingEventHandler.supportsGuiByInfo(hovered)) {
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
        int thumbHeight = Math.max(14, height * visible / totalRows);
        int maxScroll = Math.max(1, totalRows - visible);
        int thumbY = y + (height - thumbHeight) * Math.min(scrollRow, maxScroll) / maxScroll;
        graphics.fill(x, y, x + 2, y + height, 0x55384750);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFF718893);
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
            if (view == View.ALL_BOUND && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + TILE_WIDTH - 13, bounds.y() + 1, 12, 12)) {
                if (button == 0) {
                    MachineTabHandler.onUnbind(info);
                    machines.remove(info);
                    refilter();
                }
                return true;
            }
            if (view == View.GUI_ONLY && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + TILE_WIDTH - 13, bounds.y() + 1, 13, 13)) {
                if (button == 0) {
                    MachinePreferenceClient.requestFavoriteToggle(info);
                    refreshMachines();
                }
                return true;
            }
            if (button == 0 && !MachinePreferenceClient.isBlocked(info)
                    && BindingEventHandler.supportsGuiByInfo(info)) {
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
                    MachinePreferenceClient.requestAutomationBlocked(selected, true);
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
        return Math.max(1, (gridWidth + GRID_GAP) / (TILE_WIDTH + GRID_GAP));
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
            int x = gridX + (slot % columns) * (TILE_WIDTH + GRID_GAP);
            int y = gridY + (slot / columns) * (TILE_HEIGHT + GRID_GAP);
            if (inside(mouseX, mouseY, x, y, TILE_WIDTH, TILE_HEIGHT)) {
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
        return left < x + TILE_WIDTH && right > x
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
            int x = gridX + (slot % columns) * (TILE_WIDTH + GRID_GAP);
            int y = gridY + (slot / columns) * (TILE_HEIGHT + GRID_GAP);
            if (tileIntersectsSelection(x, y)) selected.add(filtered.get(index));
        }
        return selected;
    }

    private void renderSelection(GuiGraphics graphics, int selectedCount) {
        int x = (int) Math.floor(Math.min(selectionStartX, selectionCurrentX));
        int y = (int) Math.floor(Math.min(selectionStartY, selectionCurrentY));
        int right = (int) Math.ceil(Math.max(selectionStartX, selectionCurrentX));
        int bottom = (int) Math.ceil(Math.max(selectionStartY, selectionCurrentY));
        int width = Math.max(1, right - x);
        int height = Math.max(1, bottom - y);
        graphics.fill(x, y, right, bottom, SELECTION_FILL);
        graphics.renderOutline(x, y, width, height, SELECTION_BORDER);
        if (selectedCount > 0) {
            String count = Component.translatable(
                    "rsi.control_center.selection_count", selectedCount).getString();
            int badgeWidth = font.width(count) + 10;
            int badgeX = Math.min(right + 4, panelX + panelWidth - badgeWidth - 8);
            int badgeY = Math.max(gridY + 2, y - 15);
            UIRenderer.pillBadge(graphics, font, badgeX, badgeY, badgeWidth, 14,
                    0xCC315E91, 0xFFF1F7FF, count);
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
        return BindingEventHandler.resolveBlockName(
                info.blockKey(), info.blockRegKey(), info.displayStack());
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
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record TileBounds(int x, int y, BindingInfo info) {}
}
