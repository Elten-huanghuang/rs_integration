package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
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

/** 平铺的绑定机器中心：图标左键打开，星标收藏，X 解绑，右键切换自动合成黑名单。 */
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
    private static final int GRID_GAP = 8;
    private static final int TILE_SIZE = 58;

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
        searchBox = new EditBox(font, panelX + 12, panelY + 47, 210, 18,
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
        RSSidePanelNetworkHandler.sendBindingSyncRequest();
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
        for (BindingInfo info : BindingCache.getInstance().getAll()) {
            if (view == View.GUI_ONLY && !BindingEventHandler.supportsGuiByInfo(info)) continue;
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
        String count = Integer.toString(filtered.size());
        int badgeWidth = font.width(count) + 14;
        int badgeX = panelX + panelWidth - 126 - badgeWidth;
        UIRenderer.pillBadge(graphics, font, badgeX, panelY + 9, badgeWidth, 17,
                0x663C5664, 0xFFD9E4EA, count);
        renderGrid(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHoveredTooltip(graphics, mouseX, mouseY);
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
        for (int index = start; index < end; index++) {
            int slot = index - start;
            int column = slot % columns;
            int row = slot / columns;
            int x = gridX + column * (TILE_SIZE + GRID_GAP);
            int y = gridY + row * (TILE_SIZE + GRID_GAP);
            renderTile(graphics, filtered.get(index), x, y, mouseX, mouseY);
        }
        if (filtered.size() > columns * rows) {
            renderScrollbar(graphics, panelX + panelWidth - 7, gridY, gridHeight,
                    columns, rows);
        }
    }

    private void renderTile(GuiGraphics graphics, BindingInfo info,
                            int x, int y, int mouseX, int mouseY) {
        boolean blocked = MachinePreferenceClient.isBlocked(info);
        boolean hovered = inside(mouseX, mouseY, x, y, TILE_SIZE, TILE_SIZE);
        UIRenderer.rounded(graphics, x, y, TILE_SIZE, TILE_SIZE, 6f,
                blocked ? 0xC0441F25 : hovered ? SURFACE_HOVER : SURFACE);
        if (blocked && view == View.ALL_BOUND) {
            graphics.fill(x + 1, y + 1, x + TILE_SIZE - 1, y + TILE_SIZE - 1, BLOCKED_TINT);
        }
        ItemStack icon = MachineTabRenderer.resolveIcon(info);
        if (!icon.isEmpty()) InkFluidRenderer.render(graphics, icon, x + 21, y + 20);
        if (view == View.GUI_ONLY) {
            graphics.drawString(font, MachinePreferenceClient.isFavorite(info) ? "*" : "-",
                    x + 4, y + 3,
                    MachinePreferenceClient.isFavorite(info) ? 0xFF68A9E8 : 0xFF71808A, false);
        } else {
            graphics.drawString(font, "x", x + TILE_SIZE - 9, y + 3, 0xFFE07A7A, false);
        }
        if (blocked && view == View.ALL_BOUND) {
            String mark = Component.translatable("rsi.control_center.flag.blocked_short").getString();
            graphics.drawCenteredString(font, mark, x + TILE_SIZE / 2, y + TILE_SIZE - 13,
                    0xFFFF8B8B);
        }
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        BindingInfo hovered = hoveredMachine(mouseX, mouseY);
        if (hovered == null) return;
        List<Component> lines = new ArrayList<>();
        lines.add(displayComponent(hovered));
        lines.add(Component.literal(hovered.dim() + " " + hovered.pos().toShortString())
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("rsi.control_center.hint.open")
                .withStyle(ChatFormatting.GRAY));
        if (view == View.GUI_ONLY) {
            lines.add(Component.translatable("rsi.control_center.hint.favorite")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("rsi.control_center.hint.unbind")
                    .withStyle(ChatFormatting.RED));
            lines.add(Component.translatable(MachinePreferenceClient.isBlocked(hovered)
                            ? "rsi.control_center.hint.allow_automation"
                            : "rsi.control_center.hint.block_automation")
                    .withStyle(MachinePreferenceClient.isBlocked(hovered)
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
        BindingInfo info = hoveredMachine(mouseX, mouseY);
        if (info != null) {
            TileBounds bounds = hoveredTileBounds(mouseX, mouseY);
            if (view == View.ALL_BOUND && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + TILE_SIZE - 13, bounds.y() + 1, 12, 12)) {
                if (button == 0) {
                    MachineTabHandler.onUnbind(info);
                    machines.remove(info);
                    refilter();
                }
                return true;
            }
            if (view == View.GUI_ONLY && bounds != null && inside(mouseX, mouseY,
                    bounds.x() + 1, bounds.y() + 1, 13, 13)) {
                if (button == 0) {
                    MachinePreferenceClient.requestFavoriteToggle(info);
                    refreshMachines();
                }
                return true;
            }
            if (button == 0 && BindingEventHandler.supportsGuiByInfo(info)) {
                MachineTabHandler.onClick(info);
                return true;
            }
            if (view == View.ALL_BOUND && button == 1) {
                MachinePreferenceClient.requestAutomationBlockToggle(info);
                refreshMachines();
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            scrollRow = Math.max(0, scrollRow - (int) Math.signum(delta));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int columns() {
        return Math.max(1, (gridWidth + GRID_GAP) / (TILE_SIZE + GRID_GAP));
    }

    private int visibleRows() {
        return Math.max(1, (gridHeight + GRID_GAP) / (TILE_SIZE + GRID_GAP));
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
            int x = gridX + (slot % columns) * (TILE_SIZE + GRID_GAP);
            int y = gridY + (slot / columns) * (TILE_SIZE + GRID_GAP);
            if (inside(mouseX, mouseY, x, y, TILE_SIZE, TILE_SIZE)) {
                return new TileBounds(x, y, filtered.get(index));
            }
        }
        return null;
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
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record TileBounds(int x, int y, BindingInfo info) {}
}
