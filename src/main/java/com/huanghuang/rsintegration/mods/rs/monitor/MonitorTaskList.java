package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.client.gui.ListScrollState;
import com.huanghuang.rsintegration.client.gui.ListScrollBar;
import com.huanghuang.rsintegration.client.gui.ScrollBarStyle;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorTaskAccessor;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** 左侧纵向任务选择，不按物品身份合并订单；选择仍调用原生菜单接口。 */
public final class MonitorTaskList extends AbstractWidget {
    private static final int ROW_HEIGHT = 26;
    private final int visibleRows;
    private final int contentWidth;
    private final ListScrollState scroll = new ListScrollState();
    private final ListScrollBar bar;
    private final Consumer<UUID> select;
    private final IElementDrawers drawers;
    private List<IGridTab> tasks = List.of();
    private UUID selected;

    public MonitorTaskList(int x, int y, int width, int height, IElementDrawers drawers, Consumer<UUID> select, Consumer<AbstractWidget> register) {
        super(x, y, width, height, Component.translatable("rsi.monitor.tasks"));
        visibleRows = Math.max(1, (height - 42) / ROW_HEIGHT);
        // 左栏在最后一行结束，底部操作区只属于中间材料和右侧步骤。
        setHeight(20 + ROW_HEIGHT * visibleRows + 2);
        this.drawers = drawers;
        this.select = select;
        int barWidth = width >= 40 ? ScrollBarStyle.WIDTH : ScrollBarStyle.COMPACT_WIDTH;
        int barX = x + width - 2 - barWidth;
        contentWidth = barX - ScrollBarStyle.CONTENT_GAP - (x + 2);
        bar = new ListScrollBar(barX, y + 20, barWidth, ROW_HEIGHT * visibleRows, scroll, () -> {});
        register.accept(this);
        register.accept(bar);
    }

    public void update(List<IGridTab> tasks, UUID selected) {
        boolean changedSelection = !Objects.equals(this.selected, selected);
        this.tasks = List.copyOf(tasks);
        this.selected = selected;
        scroll.range(tasks.size(), visibleRows);
        if (changedSelection) for (int i = 0; i < tasks.size(); i++) if (Objects.equals(id(tasks.get(i)), selected)) scroll.reveal(i);
    }

    public static UUID id(IGridTab task) {
        return task instanceof RsiMonitorTask rsi ? rsi.craftId() : ((MonitorTaskAccessor) task).rsi$id();
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return bar.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { return bar.mouseReleased(x, y, button); }

    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        CraftingUiStyle.raised(graphics, getX(), getY(), width, height, CraftingUiStyle.PANEL, CraftingUiStyle.BORDER);
        graphics.drawString(font, CraftingUiStyle.fit(font, getMessage().getString(), width - 4), getX() + 3, getY() + 7, CraftingUiStyle.TEXT, false);
        CraftingUiStyle.inset(graphics, getX() + 2, getY() + 20, contentWidth, ROW_HEIGHT * visibleRows);
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll.offset() + row;
            int top = getY() + 20 + row * ROW_HEIGHT;
            boolean current = index < tasks.size() && Objects.equals(id(tasks.get(index)), selected);
            graphics.fill(getX() + 2, top, getX() + 2 + contentWidth, top + ROW_HEIGHT - 1,
                    current ? 0xFFB8DCF2 : CraftingUiStyle.CANVAS);
            graphics.fill(getX() + 2, top + ROW_HEIGHT - 1, getX() + 2 + contentWidth, top + ROW_HEIGHT, CraftingUiStyle.GRID_LINE);
            if (current) {
                graphics.fill(getX() + 2, top, getX() + 4, top + ROW_HEIGHT - 1, CraftingUiStyle.HIGHLIGHT);
                graphics.fill(getX() + 2, top, getX() + 2 + contentWidth, top + 1, CraftingUiStyle.HIGHLIGHT);
            }
            if (index < tasks.size()) tasks.get(index).drawIcon(graphics, getX() + (contentWidth - 16) / 2 + 2, top + 5, drawers.getItemDrawer(), drawers.getFluidDrawer());
        }
        int hovered = indexAt(mouseX, mouseY);
        if (hovered >= 0) tasks.get(hovered).drawTooltip(font, graphics, mouseX, mouseY);
    }

    private int indexAt(double mouseX, double mouseY) {
        if (mouseX < getX() + 2 || mouseX >= getX() + 2 + contentWidth || mouseY < getY() + 20
                || mouseY >= getY() + 20 + ROW_HEIGHT * visibleRows) return -1;
        int index = scroll.offset() + (int) (mouseY - getY() - 20) / ROW_HEIGHT;
        return index < tasks.size() ? index : -1;
    }
    @Override public void onClick(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        if (index >= 0) select.accept(id(tasks.get(index)));
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 滚动条独立接收焦点和拖拽，列表不能截获其点击。
        return mouseX < getX() + 2 + contentWidth && super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        scroll.scroll(delta > 0 ? -1 : delta < 0 ? 1 : 0);
        return true;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
