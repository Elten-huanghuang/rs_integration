package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.config.CraftingUiColors;
import com.huanghuang.rsintegration.client.gui.ListScrollState;
import com.huanghuang.rsintegration.client.gui.ListScrollBar;
import com.huanghuang.rsintegration.client.gui.ScrollBarStyle;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 当前步骤与执行诊断，仍绘制在 RS 原生屏幕和菜单内。 */
public final class MonitorDetailsPanel {
    public record ViewState(UUID taskId, int nodeId, List<Integer> history, int listOffset, int offset) {}
    private ViewState pendingView;
    public static final int NATIVE_WIDTH = 254;
    public static final int DETAILS_WIDTH = 166;
    private static final int NATIVE_ROW_HEIGHT = 34;
    private static final int RSI_ROW_HEIGHT = 44;
    private static final int SEARCH_WIDTH = 92;
    private static final int FILTER_WIDTH = 54;
    private static final int CONTROL_GAP = 6;
    private final int height;
    private final int contentWidth;
    private final CraftingMonitorScreen screen;
    private final IntConsumer revealElement;
    private final EditBox search;
    private final int searchWidth;
    private final ActionButton filter;
    private final ActionButton diagnosis;
    private final ActionButton back;
    private final ActionButton stepList;
    private final Deque<Integer> detailHistory = new ArrayDeque<>();
    private MonitorDependencyDetails dependencies;
    private int selectedNodeId = -1;
    private int listOffset;
    private List<MonitorDependencyDetails.Row> detailRows = List.of();
    private MonitorDiagnostics.Group focusedGroup;
    private final ListScrollState scrollState = new ListScrollState();
    private final ListScrollBar scrollBar;
    private final int x, y, width;
    private List<MonitorStepEntry> entries = List.of();
    private List<MonitorStepEntry> filtered = List.of();
    private IGridTab lastTask;
    private UUID lastTaskId;
    private int offset;
    private boolean onlyActive = true;
    private boolean visible;
    private int detailedNodes;
    private int totalNodes;

    public MonitorDetailsPanel(CraftingMonitorScreen screen, int x, int y, int width, int height,
                               Consumer<AbstractWidget> register, IntConsumer revealElement,
                               String query, boolean onlyActive) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.contentWidth = width - ScrollBarStyle.WIDTH - ScrollBarStyle.CONTENT_GAP;
        this.height = height;
        this.revealElement = revealElement;
        this.onlyActive = onlyActive;
        var font = Minecraft.getInstance().font;
        searchWidth = Math.min(SEARCH_WIDTH, width - FILTER_WIDTH - CONTROL_GAP);
        search = new EditBox(font, x + 3, y + 27, searchWidth - 6, 14, Component.translatable("rsi.monitor.search"));
        search.setHint(Component.translatable("rsi.monitor.search_hint").withStyle(style -> style
                .withColor(CraftingUiStyle.hint()).withBold(false)));
        search.setTooltip(Tooltip.create(Component.translatable("rsi.monitor.search")));
        search.setMaxLength(128);
        search.setBordered(false);
        search.setTextColor(CraftingUiStyle.text());
        search.setValue(query);
        search.setResponder(value -> refilter(false));
        register.accept(search);
        filter = new ActionButton(x + searchWidth + CONTROL_GAP, y + 21, FILTER_WIDTH, 21, filterLabel(), false, button -> {
            this.onlyActive = !this.onlyActive;
            button.setMessage(filterLabel());
            refilter(false);
        });
        register.accept(filter);
        diagnosis = new ActionButton(x, y + 21, width, 21, Component.empty(), false, button -> {
            if (!(lastTask instanceof RsiMonitorTask rsi)) return;
            showList();
            focusedGroup = focusedGroup != null ? null : rsi.diagnostics().groups().stream()
                    .filter(group -> group.kind() == rsi.diagnostics().kind() && group.reason() == rsi.diagnostics().reason())
                    .findFirst().orElse(null);
            updateDiagnosisButton();
            refilter(false);
        });
        diagnosis.visible = false;
        register.accept(diagnosis);
        int backWidth = (width - 4) / 2;
        back = new ActionButton(x, y + 58, backWidth, 21, Component.translatable("rsi.monitor.wait.back"), false, button -> {
            if (detailHistory.isEmpty()) showList();
            else selectNode(detailHistory.removeLast(), false);
        });
        stepList = new ActionButton(x + backWidth + 4, y + 58, width - backWidth - 4, 21,
                Component.translatable("rsi.monitor.steps"), false, button -> showList());
        register.accept(back);
        register.accept(stepList);
        scrollBar = new ListScrollBar(x + width - ScrollBarStyle.WIDTH, y + 46, ScrollBarStyle.WIDTH,
                height - 52, scrollState, () -> {
            offset = scrollState.offset();
            scroll(0);
        });
        register.accept(scrollBar);
        layoutControls();
    }

    public String query() { return search.getValue(); }
    public boolean onlyActive() { return onlyActive; }
    public boolean visible() { return visible; }
    public ViewState viewState() { return new ViewState(lastTaskId, selectedNodeId, List.copyOf(detailHistory), listOffset, offset); }
    public void restoreViewState(ViewState state) { pendingView = state; }
    private Component filterLabel() { return Component.translatable(onlyActive ? "rsi.monitor.active_steps" : "rsi.monitor.all_steps"); }

    private int rowHeight() { return selectedNodeId >= 0 ? 32 : lastTask instanceof RsiMonitorTask ? RSI_ROW_HEIGHT : NATIVE_ROW_HEIGHT; }
    private int listTop() { return lastTask instanceof RsiMonitorTask ? 83 : 46; }
    private int controlsTop() { return listTop() - 25; }
    private int visibleRows() { return Math.max(1, (height - listTop() - 6) / rowHeight()); }

    private void layoutControls() {
        search.visible = filter.visible = visible && selectedNodeId < 0;
        back.visible = stepList.visible = visible && selectedNodeId >= 0;
        search.setY(y + controlsTop() + 6);
        filter.setY(y + controlsTop());
        scrollBar.setY(y + listTop());
        scrollBar.setHeight(rowHeight() * visibleRows());
    }

    private void updateDiagnosisButton() {
        diagnosis.visible = visible && lastTask instanceof RsiMonitorTask;
        if (lastTask instanceof RsiMonitorTask rsi) {
            diagnosis.active = focusedGroup != null || !rsi.diagnostics().focusKeys().isEmpty();
            diagnosis.setMessage(focusedGroup == null ? rsi.diagnostics().label()
                    : Component.translatable("rsi.monitor.diagnosis.focused", focusedGroup.keys().size()));
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        search.visible = filter.visible = scrollBar.visible = visible;
        updateDiagnosisButton();
        if (!visible) search.setFocused(false);
        layoutControls();
    }

    public void update(IGridTab task, UUID taskId) {
        if (task == lastTask) return;
        boolean sameTask = Objects.equals(lastTaskId, taskId);
        lastTask = task;
        lastTaskId = taskId;
        if (!sameTask) {
            focusedGroup = null;
            selectedNodeId = -1;
            detailHistory.clear();
            entries = List.of();
        }
        if (task instanceof RsiMonitorTask rsi) {
            entries = MonitorStepEntry.stabilize(entries, MonitorStepEntry.filter(MonitorStepEntry.forRsi(rsi.snapshot()), "", false));
            dependencies = new MonitorDependencyDetails(rsi.snapshot());
            if (selectedNodeId >= 0) detailRows = dependencies.rows(selectedNodeId);
            detailedNodes = rsi.snapshot().nodes().size();
            totalNodes = rsi.snapshot().totalNodes();
            if (focusedGroup != null) {
                var previous = focusedGroup;
                focusedGroup = rsi.diagnostics().groups().stream()
                        .filter(group -> group.kind() == previous.kind() && group.reason() == previous.reason())
                        .findFirst().orElse(null);
            }
        } else {
            focusedGroup = null;
            entries = task == null ? List.of() : MonitorStepEntry.forNative(screen.getElements());
            detailedNodes = totalNodes = entries.size();
        }
        layoutControls();
        updateDiagnosisButton();
        refilter(sameTask);
        if (pendingView != null) {
            var state = pendingView;
            pendingView = null;
            if (Objects.equals(state.taskId(), taskId)) {
                listOffset = state.listOffset();
                if (state.nodeId() >= 0 && task instanceof RsiMonitorTask) {
                    selectNode(state.nodeId(), false);
                    detailHistory.addAll(state.history());
                }
                offset = state.offset();
                scroll(0);
            }
        }
    }

    private void refilter(boolean keepAnchor) {
        String anchor = keepAnchor && offset < filtered.size() ? filtered.get(offset).key() : null;
        filtered = MonitorStepEntry.filterInOrder(entries, search.getValue(), onlyActive);
        if (focusedGroup != null) filtered = filtered.stream().filter(entry -> focusedGroup.keys().contains(entry.key())).toList();
        if (!keepAnchor && selectedNodeId < 0) offset = 0;
        if (anchor != null && selectedNodeId < 0) {
            for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).key().equals(anchor)) { offset = i; break; }
        }
        scroll(0);
    }

    private void scroll(int delta) {
        scrollState.range(selectedNodeId >= 0 ? detailRows.size() : filtered.size(), visibleRows());
        scrollState.offset(offset + delta);
        offset = scrollState.offset();
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || mouseX < x || mouseX >= x + width || mouseY < y + listTop() || mouseY >= y + listTop() + rowHeight() * visibleRows()) return false;
        if (delta != 0) scroll(delta > 0 ? -1 : 1);
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || mouseX < x || mouseX >= x + contentWidth || mouseY < y + listTop() || mouseY >= y + listTop() + rowHeight() * visibleRows()) return false;
        int index = offset + (int) (mouseY - y - listTop()) / rowHeight();
        if (selectedNodeId >= 0) {
            if (index < detailRows.size() && detailRows.get(index).targetNodeId() >= 0) {
                selectNode(detailRows.get(index).targetNodeId(), true);
            }
            return true;
        }
        if (index < filtered.size()) {
            var entry = filtered.get(index);
            if (lastTask instanceof RsiMonitorTask rsi) {
                listOffset = offset;
                detailHistory.clear();
                selectNode(rsi.snapshot().nodes().get(entry.elementIndex()).nodeId(), false);
            }
            int element = lastTask instanceof RsiMonitorTask rsi ? rsi.materialIndex(entry.item()) : entry.elementIndex();
            if (element >= 0) revealElement.accept(element);
        }
        return true;
    }

    private void selectNode(int nodeId, boolean remember) {
        if (remember && selectedNodeId >= 0) detailHistory.addLast(selectedNodeId);
        selectedNodeId = nodeId;
        search.setFocused(false);
        detailRows = dependencies.rows(nodeId);
        offset = 0;
        layoutControls();
        scroll(0);
    }

    private void showList() {
        if (selectedNodeId < 0) return;
        selectedNodeId = -1;
        detailHistory.clear();
        offset = listOffset;
        layoutControls();
        scroll(0);
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return visible && scrollBar.mouseDragged(x, y, button, dx, dy);
    }
    public boolean mouseReleased(double x, double y, int button) { return scrollBar.mouseReleased(x, y, button); }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, IElementDrawers drawers) {
        search.setTextColor(CraftingUiStyle.text());
        search.setHint(Component.translatable("rsi.monitor.search_hint").withStyle(style -> style
                .withColor(CraftingUiStyle.hint()).withBold(false)));
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        if (lastTask instanceof RsiMonitorTask rsi) {
            int categoryWidth = Math.min(width / 2, font.width(rsi.diagnostics().category()));
            String category = CraftingUiStyle.fit(font, rsi.diagnostics().category().getString(), categoryWidth);
            int right = x + contentWidth - (detailedNodes < totalNodes ? 12 : 0);
            int color = switch (rsi.diagnostics().kind()) {
                case ATTENTION -> CraftingUiStyle.danger();
                case RUNNING -> CraftingUiStyle.accent();
                case SUCCEEDED -> CraftingUiStyle.success();
                case CANCELLED -> CraftingUiStyle.muted();
                default -> CraftingUiStyle.warning();
            };
            // 分类和原因同时可见，普通排队不会与需检查步骤使用同一种提示。
            graphics.drawString(font, CraftingUiStyle.fit(font, Component.translatable("rsi.monitor.diagnosis.heading").getString(),
                    right - x - font.width(category) - 8), x, y + 8, CraftingUiStyle.text(), false);
            graphics.drawString(font, category, right - font.width(category), y + 8, color, false);
            graphics.drawString(font, CraftingUiStyle.fit(font, MonitorDiagnostics.timingLabel(rsi.snapshot()).getString(), width),
                    x, y + 45, CraftingUiStyle.muted(), false);
        } else graphics.drawString(font, Component.translatable("rsi.monitor.steps"), x, y + 8, CraftingUiStyle.text(), false);
        if (detailedNodes < totalNodes) graphics.drawString(font, "+", x + contentWidth - font.width("+"), y + 8, CraftingUiStyle.warning(), false);
        if (selectedNodeId < 0) CraftingUiStyle.surface(graphics, x, y + controlsTop(), searchWidth, 21, CraftingUiColors.INPUT.argb(), CraftingUiStyle.border());
        CraftingUiStyle.inset(graphics, x, y + listTop(), contentWidth, rowHeight() * visibleRows());
        if (selectedNodeId >= 0) {
            renderDetailRows(graphics, mouseX, mouseY);
            return;
        }
        for (int row = 0; row < visibleRows(); row++) {
            int rowY = y + listTop() + row * rowHeight();
            graphics.fill(x, rowY + rowHeight() - 1, x + contentWidth, rowY + rowHeight(), CraftingUiStyle.gridLine());
        }
        if (filtered.isEmpty()) {
            var text = Component.translatable(entries.isEmpty() ? "rsi.monitor.no_steps"
                    : search.getValue().isBlank() ? "rsi.monitor.no_active_steps" : "rsi.monitor.no_matches");
            int lineY = y + listTop() + 15;
            for (var line : font.split(text, contentWidth - 10)) {
                graphics.drawString(font, line, x + (contentWidth - font.width(line)) / 2, lineY, CraftingUiStyle.muted(), false);
                lineY += font.lineHeight + 3;
            }
        }
        for (int row = 0; row < visibleRows() && offset + row < filtered.size(); row++) {
            var entry = filtered.get(offset + row);
            int rowY = y + listTop() + row * rowHeight();
            int accent = entry.priority() == 0 ? CraftingUiStyle.danger() : entry.priority() == 1
                    ? CraftingUiStyle.accent() : entry.active() ? CraftingUiStyle.warning() : CraftingUiStyle.success();
            boolean hovered = mouseX >= x && mouseX < x + contentWidth && mouseY >= rowY && mouseY < rowY + rowHeight() - 1;
            MonitorGridStyle stateColor = entry.priority() == 0 ? MonitorGridStyle.BLOCKED
                    : entry.priority() == 1 ? MonitorGridStyle.RUNNING : entry.active() ? MonitorGridStyle.SCHEDULED : MonitorGridStyle.STORED;
            if (lastTask instanceof RsiMonitorTask rsi && entry.elementIndex() >= 0
                    && entry.elementIndex() < rsi.snapshot().nodes().size()) {
                stateColor = MonitorGridStyle.node(rsi.snapshot().nodes().get(entry.elementIndex()));
            }
            MonitorVisualStyle.cell(graphics, x, rowY, contentWidth, rowHeight() - 1, stateColor.color(), hovered);
            graphics.fill(x, rowY + 1, x + 2, rowY + rowHeight() - 3, accent);
            if (!entry.item().isEmpty()) InkFluidRenderer.render(graphics, entry.item(), x + contentWidth - 21, rowY + 5);
            else if (!entry.fluid().isEmpty()) drawers.getFluidDrawer().draw(graphics, x + contentWidth - 21, rowY + 5, entry.fluid());
            int textWidth = contentWidth - 33;
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.title().getString(), textWidth), x + 5, rowY + 2, CraftingUiStyle.text(), false);
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.state().getString(), textWidth), x + 5, rowY + 12, CraftingUiStyle.text(), false);
            if (lastTask instanceof RsiMonitorTask) graphics.drawString(font,
                    CraftingUiStyle.fit(font, entry.machine().getString(), contentWidth - 10), x + 5, rowY + 22, CraftingUiStyle.muted(), false);
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.counts().getString(), contentWidth - 10),
                    x + 5, rowY + rowHeight() - 12, CraftingUiStyle.muted(), false);
            if (entry.completedFraction() >= 0) {
                graphics.fill(x + 2, rowY + rowHeight() - 2, x + contentWidth - 2, rowY + rowHeight() - 1, CraftingUiStyle.canvas());
                graphics.fill(x + 2, rowY + rowHeight() - 2,
                        x + 2 + (int) ((contentWidth - 4) * entry.completedFraction()), rowY + rowHeight() - 1, accent);
            }
        }
    }

    private void renderDetailRows(GuiGraphics graphics, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        for (int row = 0; row < visibleRows() && offset + row < detailRows.size(); row++) {
            var detail = detailRows.get(offset + row);
            int top = y + listTop() + row * rowHeight();
            boolean hovered = mouseX >= x && mouseX < x + contentWidth && mouseY >= top && mouseY < top + rowHeight() - 1;
            int color = detail.targetNodeId() >= 0 ? CraftingUiStyle.accent() : CraftingUiStyle.canvas();
            MonitorVisualStyle.cell(graphics, x, top, contentWidth, rowHeight() - 1, color, hovered);
            int textWidth = contentWidth - (detail.icon().isEmpty() ? 12 : 31);
            graphics.drawString(font, CraftingUiStyle.fit(font, detail.title().getString(), textWidth), x + 5, top + 5, CraftingUiStyle.text(), false);
            graphics.drawString(font, CraftingUiStyle.fit(font, detail.detail().getString(), contentWidth - 12), x + 5, top + 18, CraftingUiStyle.muted(), false);
            if (!detail.icon().isEmpty()) InkFluidRenderer.render(graphics, detail.icon(), x + contentWidth - 21, top + 2);
            if (detail.targetNodeId() >= 0 && detail.icon().isEmpty()) graphics.drawString(font, ">", x + contentWidth - 9, top + 5, CraftingUiStyle.accent(), false);
        }
    }

    public void tooltip(GuiGraphics graphics, int localMouseX, int localMouseY) {
        if (!visible) return;
        int mouseX = localMouseX + screen.getGuiLeft();
        int mouseY = localMouseY + screen.getGuiTop();
        if (mouseX < x || mouseX >= x + contentWidth) return;
        if (mouseY >= y + listTop() && mouseY < y + listTop() + rowHeight() * visibleRows()) {
            int index = offset + (mouseY - y - listTop()) / rowHeight();
            if (selectedNodeId >= 0) {
                if (index < detailRows.size()) {
                    var row = detailRows.get(index);
                    var lines = new ArrayList<Component>(List.of(row.title(), row.detail()));
                    if (row.targetNodeId() >= 0) lines.add(Component.translatable("rsi.monitor.wait.follow_hint"));
                    renderTooltip(graphics, lines, localMouseX, localMouseY);
                }
                return;
            }
            if (index < filtered.size()) renderTooltip(graphics, filtered.get(index).tooltip(), localMouseX, localMouseY);
        } else if (lastTask instanceof RsiMonitorTask rsi && mouseY >= y + 20 && mouseY < y + 42) {
            var lines = new ArrayList<>(rsi.diagnostics().tooltip(rsi.snapshot()));
            if (focusedGroup != null) lines.add(1, focusedGroup.label());
            renderTooltip(graphics, lines, localMouseX, localMouseY);
        } else if (lastTask instanceof RsiMonitorTask rsi && mouseY >= y + 43 && mouseY < y + 56) {
            var lines = new ArrayList<Component>();
            lines.add(MonitorDiagnostics.timingLabel(rsi.snapshot()));
            if (rsi.snapshot().timing().known()) lines.add(Component.translatable("rsi.monitor.timing_hint"));
            renderTooltip(graphics, lines, localMouseX, localMouseY);
        } else if (mouseY >= y && mouseY < y + 20 && detailedNodes < totalNodes) {
            graphics.renderComponentTooltip(Minecraft.getInstance().font,
                    List.of(Component.translatable("rsi.monitor.details_limited", detailedNodes, totalNodes)), localMouseX, localMouseY);
        }
    }

    private void renderTooltip(GuiGraphics graphics, List<Component> lines, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        int maxWidth = Math.max(120, Math.min(300, screen.width - 24));
        graphics.renderTooltip(font, lines.stream().flatMap(line -> font.split(line, maxWidth).stream()).toList(), mouseX, mouseY);
    }
}
