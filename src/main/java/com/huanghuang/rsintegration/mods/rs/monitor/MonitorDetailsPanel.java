package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
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
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** EC 式当前加工列表，仍绘制在 RS 原生屏幕和菜单内。 */
public final class MonitorDetailsPanel {
    public static final int NATIVE_WIDTH = 254;
    public static final int DETAILS_WIDTH = 166;
    private static final int ROW_HEIGHT = 34;
    private final int visibleRows;
    private final int contentWidth;
    private final CraftingMonitorScreen screen;
    private final IntConsumer revealElement;
    private final EditBox search;
    private final ActionButton filter;
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
        this.visibleRows = Math.max(1, (height - 52) / ROW_HEIGHT);
        this.revealElement = revealElement;
        this.onlyActive = onlyActive;
        var font = Minecraft.getInstance().font;
        search = new EditBox(font, x + 1, y + 24, width - 2, 18, Component.translatable("rsi.monitor.search"));
        search.setHint(Component.translatable("rsi.monitor.search").withStyle(style -> style
                .withColor(CraftingUiStyle.HINT).withBold(false)));
        search.setMaxLength(128);
        search.setBordered(false);
        search.setTextColor(CraftingUiStyle.TEXT);
        search.setValue(query);
        search.setResponder(value -> refilter(false));
        register.accept(search);
        filter = new ActionButton(x + width - 54, y + 4, 54, 17, filterLabel(), false, button -> {
            this.onlyActive = !this.onlyActive;
            button.setMessage(filterLabel());
            refilter(false);
        });
        register.accept(filter);
        scrollBar = new ListScrollBar(x + width - ScrollBarStyle.WIDTH, y + 46, ScrollBarStyle.WIDTH,
                height - 52, scrollState, () -> {
            offset = scrollState.offset();
            scroll(0);
        });
        register.accept(scrollBar);
    }

    public String query() { return search.getValue(); }
    public boolean onlyActive() { return onlyActive; }
    public boolean visible() { return visible; }
    private Component filterLabel() { return Component.translatable(onlyActive ? "rsi.monitor.active_steps" : "rsi.monitor.all_steps"); }

    public void setVisible(boolean visible) {
        this.visible = visible;
        search.visible = filter.visible = scrollBar.visible = visible;
        if (!visible) search.setFocused(false);
    }

    public void update(IGridTab task, UUID taskId) {
        if (task == lastTask) return;
        boolean sameTask = Objects.equals(lastTaskId, taskId);
        lastTask = task;
        lastTaskId = taskId;
        if (task instanceof RsiMonitorTask rsi) {
            entries = MonitorStepEntry.forRsi(rsi.snapshot());
            detailedNodes = rsi.snapshot().nodes().size();
            totalNodes = rsi.snapshot().totalNodes();
        } else {
            entries = task == null ? List.of() : MonitorStepEntry.forNative(screen.getElements());
            detailedNodes = totalNodes = entries.size();
        }
        refilter(sameTask);
    }

    private void refilter(boolean keepAnchor) {
        String anchor = keepAnchor && offset < filtered.size() ? filtered.get(offset).key() : null;
        filtered = MonitorStepEntry.filter(entries, search.getValue(), onlyActive);
        if (!keepAnchor) offset = 0;
        if (anchor != null) {
            for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).key().equals(anchor)) { offset = i; break; }
        }
        scroll(0);
    }

    private void scroll(int delta) {
        scrollState.range(filtered.size(), visibleRows);
        scrollState.offset(offset + delta);
        offset = scrollState.offset();
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || mouseX < x || mouseX >= x + width || mouseY < y + 44 || mouseY >= y + 46 + ROW_HEIGHT * visibleRows) return false;
        if (delta != 0) scroll(delta > 0 ? -1 : 1);
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || mouseX < x || mouseX >= x + contentWidth || mouseY < y + 46 || mouseY >= y + 46 + ROW_HEIGHT * visibleRows) return false;
        int index = offset + (int) (mouseY - y - 46) / ROW_HEIGHT;
        if (index < filtered.size()) {
            var entry = filtered.get(index);
            int element = lastTask instanceof RsiMonitorTask rsi ? rsi.materialIndex(entry.item()) : entry.elementIndex();
            if (element >= 0) revealElement.accept(element);
        }
        return true;
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return visible && scrollBar.mouseDragged(x, y, button, dx, dy);
    }
    public boolean mouseReleased(double x, double y, int button) { return scrollBar.mouseReleased(x, y, button); }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, IElementDrawers drawers) {
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, Component.translatable("rsi.monitor.steps"), x, y + 8, CraftingUiStyle.TEXT, false);
        if (detailedNodes < totalNodes) graphics.drawString(font, "+", x + contentWidth - font.width("+"), y + 8, CraftingUiStyle.WARNING, false);
        CraftingUiStyle.surface(graphics, x - 2, y + 21, width + 4, 21, 0xFFDFE1EA, CraftingUiStyle.BORDER);
        CraftingUiStyle.inset(graphics, x, y + 46, contentWidth, ROW_HEIGHT * visibleRows);
        for (int row = 0; row < visibleRows; row++) {
            int rowY = y + 46 + row * ROW_HEIGHT;
            graphics.fill(x, rowY + ROW_HEIGHT - 1, x + contentWidth, rowY + ROW_HEIGHT, CraftingUiStyle.GRID_LINE);
        }
        if (filtered.isEmpty()) {
            var text = Component.translatable(entries.isEmpty() ? "rsi.monitor.no_steps"
                    : search.getValue().isBlank() ? "rsi.monitor.no_active_steps" : "rsi.monitor.no_matches");
            int lineY = y + 86;
            for (var line : font.split(text, contentWidth - 10)) {
                graphics.drawString(font, line, x + (contentWidth - font.width(line)) / 2, lineY, CraftingUiStyle.MUTED, false);
                lineY += font.lineHeight + 3;
            }
        }
        for (int row = 0; row < visibleRows && offset + row < filtered.size(); row++) {
            var entry = filtered.get(offset + row);
            int rowY = y + 46 + row * ROW_HEIGHT;
            int accent = entry.priority() == 0 ? CraftingUiStyle.DANGER : entry.priority() == 1
                    ? CraftingUiStyle.ACCENT : entry.active() ? CraftingUiStyle.WARNING : CraftingUiStyle.SUCCESS;
            boolean hovered = mouseX >= x && mouseX < x + contentWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 1;
            MonitorGridStyle stateColor = entry.priority() == 0 ? MonitorGridStyle.BLOCKED
                    : entry.priority() == 1 ? MonitorGridStyle.RUNNING : entry.active() ? MonitorGridStyle.SCHEDULED : MonitorGridStyle.STORED;
            if (lastTask instanceof RsiMonitorTask rsi && entry.elementIndex() >= 0
                    && entry.elementIndex() < rsi.snapshot().nodes().size()) {
                stateColor = MonitorGridStyle.node(rsi.snapshot().nodes().get(entry.elementIndex()));
            }
            graphics.fill(x, rowY, x + contentWidth, rowY + ROW_HEIGHT - 1, stateColor.color());
            if (hovered) graphics.fill(x, rowY, x + contentWidth, rowY + 1, CraftingUiStyle.HIGHLIGHT);
            graphics.fill(x, rowY + 1, x + 2, rowY + ROW_HEIGHT - 3, accent);
            if (!entry.item().isEmpty()) InkFluidRenderer.render(graphics, entry.item(), x + contentWidth - 21, rowY + 5);
            else if (!entry.fluid().isEmpty()) drawers.getFluidDrawer().draw(graphics, x + contentWidth - 21, rowY + 5, entry.fluid());
            int textWidth = contentWidth - 33;
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.title().getString(), textWidth), x + 5, rowY + 2, CraftingUiStyle.TEXT, false);
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.state().getString(), textWidth), x + 5, rowY + 12, CraftingUiStyle.TEXT, false);
            graphics.drawString(font, CraftingUiStyle.fit(font, entry.counts().getString(), contentWidth - 10), x + 5, rowY + 22, CraftingUiStyle.MUTED, false);
            if (entry.completedFraction() >= 0) {
                graphics.fill(x + 2, rowY + ROW_HEIGHT - 2, x + contentWidth - 2, rowY + ROW_HEIGHT - 1, CraftingUiStyle.CANVAS);
                graphics.fill(x + 2, rowY + ROW_HEIGHT - 2,
                        x + 2 + (int) ((contentWidth - 4) * entry.completedFraction()), rowY + ROW_HEIGHT - 1, accent);
            }
        }
    }

    public void tooltip(GuiGraphics graphics, int localMouseX, int localMouseY) {
        if (!visible) return;
        int mouseX = localMouseX + screen.getGuiLeft();
        int mouseY = localMouseY + screen.getGuiTop();
        if (mouseX < x || mouseX >= x + contentWidth) return;
        if (mouseY >= y + 46 && mouseY < y + 46 + ROW_HEIGHT * visibleRows) {
            int index = offset + (mouseY - y - 46) / ROW_HEIGHT;
            if (index < filtered.size()) graphics.renderComponentTooltip(Minecraft.getInstance().font,
                    filtered.get(index).tooltip(), localMouseX, localMouseY);
        } else if (mouseY >= y && mouseY < y + 20 && detailedNodes < totalNodes) {
            graphics.renderComponentTooltip(Minecraft.getInstance().font,
                    List.of(Component.translatable("rsi.monitor.details_limited", detailedNodes, totalNodes)), localMouseX, localMouseY);
        }
    }
}
