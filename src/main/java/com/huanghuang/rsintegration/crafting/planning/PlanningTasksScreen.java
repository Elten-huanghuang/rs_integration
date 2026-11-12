package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import com.huanghuang.rsintegration.crafting.MachineManagementScreen;
import com.huanghuang.rsintegration.crafting.progress.ExecutionMonitorClient;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.config.CraftingUiColors;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.function.LongConsumer;

/** 只管理计算和预览；打开列表、切换目标不修改机器订单。 */
public final class PlanningTasksScreen extends Screen {
    private final Screen previous;
    private final StorageReference network;
    private final LongConsumer openPlan;
    private final Runnable closePage;
    private List<Row> rows = List.of();
    private Component monitorNotice;
    private Button executionMonitorButton;
    private Button visibilityButton;
    private int paginationCenterX;
    private int page;
    private int rowsPerPage;
    private int panelX, panelY, panelWidth, panelHeight;
    private static final int ROW_HEIGHT = 48;
    private static final int HEADER_HEIGHT = 86;
    private static final int FOOTER_HEIGHT = 40;

    public PlanningTasksScreen(Screen previous) {
        this(previous, null, null, null);
    }

    /** 原生监控器内的计划组件，切页不调用 Minecraft.setScreen。 */
    public PlanningTasksScreen(StorageReference network, LongConsumer openPlan, Runnable closePage) {
        this(null, network, openPlan, closePage);
    }

    private PlanningTasksScreen(Screen previous, StorageReference network,
                                LongConsumer openPlan, Runnable closePage) {
        super(Component.translatable("rsi.planning.tasks"));
        this.previous = previous;
        this.network = network;
        this.openPlan = openPlan;
        this.closePage = closePage;
        var current = PlanningProgressTracker.current();
        if (current != null && network == null) PlanBackgroundClient.detach(current.requestId());
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new PlanningTasksScreen(minecraft.screen));
    }

    public static int taskCount() {
        var ids = new LinkedHashSet<>(PlanBackgroundClient.completedPlans().keySet());
        for (var task : PlanningProgressTracker.tasks()) ids.add(task.requestId());
        return ids.size();
    }

    private List<Row> readRows() {
        var result = new LinkedHashMap<Long, Row>();
        for (var task : PlanningProgressTracker.tasks()) {
            if (network != null) continue;
            result.put(task.requestId(), new Row(task.requestId(), task.recipeId().toString(),
                    PlanningProgressOverlay.titleKey(task.state()), false, !task.terminal(), ItemStack.EMPTY, 0));
        }
        PlanBackgroundClient.completedPlans().forEach((id, plan) -> {
            if (network != null && !network.equals(plan.storageReference())) return;
                result.put(id, new Row(id, plan.targetName(), plan.success() && !plan.executionBlocked()
                        ? "rsi.planning.tasks.ready" : "rsi.planning.tasks.attention", true, true,
                        plan.targetResult(), plan.repeatCount()));
        });
        return result.values().stream().sorted(Comparator.comparingLong(Row::id).reversed()).toList();
    }

    @Override protected void init() {
        clearWidgets();
        rows = readRows();
        panelWidth = Math.max(1, Math.min(420, width - 24));
        int maxHeight = Math.min(390, height - 24);
        rowsPerPage = Math.max(1, (maxHeight - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT);
        panelHeight = HEADER_HEIGHT + FOOTER_HEIGHT + Math.max(1, Math.min(rows.size(), rowsPerPage)) * ROW_HEIGHT;
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        page = Math.min(page, Math.max(0, (rows.size() - 1) / rowsPerPage));
        int left = panelX + 12;
        int contentWidth = panelWidth - 24;
        int navigationWidth = (contentWidth - (closePage == null ? 12 : 6)) / (closePage == null ? 3 : 2);
        addRenderableWidget(new ActionButton(left, panelY + 52, navigationWidth, 22,
                Component.translatable("rsi.planning.tasks.open"), true, button -> {}));
        executionMonitorButton = addRenderableWidget(new ActionButton(left + navigationWidth + 6, panelY + 52, navigationWidth, 22,
                Component.translatable("rsi.planning.tasks.executions"), false,
                button -> openExecutionMonitor()));
        if (monitorNotice != null) executionMonitorButton.setTooltip(Tooltip.create(monitorNotice));
        if (closePage == null) addRenderableWidget(new ActionButton(left + (navigationWidth + 6) * 2,
                panelY + 52, navigationWidth, 22, Component.translatable("rsi.control_center.machines"), false,
                button -> minecraft.setScreen(new MachineManagementScreen(this, MachineManagementScreen.View.ALL_BOUND))));
        int start = page * rowsPerPage;
        for (int i = start; i < Math.min(rows.size(), start + rowsPerPage); i++) {
            Row row = rows.get(i);
            int y = panelY + HEADER_HEIGHT + (i - start) * ROW_HEIGHT;
            addRenderableWidget(new PlanRowButton(left, y, contentWidth,
                    row, button -> {
                    if (row.completed()) {
                            if (openPlan != null) openPlan.accept(row.id());
                            else PlanBackgroundClient.openRequest(row.id(), () -> minecraft.setScreen(this));
                        }
                        else { PlanningProgressTracker.select(row.id()); onClose(); }
                    }, () -> {
                    if (row.completed()) PlanBackgroundClient.dismiss(row.id());
                    else PlanningProgressOverlay.cancel(row.id());
                    init();
                    }));
        }
        int footerButtonWidth = Math.min(94, Math.max(1, (contentWidth - 12) / 3));
        visibilityButton = addRenderableWidget(new ActionButton(left, panelY + panelHeight - 30,
                footerButtonWidth, 22, Component.empty(), false, button -> {
                    PlanningProgressOverlay.toggleVisible();
                    updateVisibilityButton();
                }));
        visibilityButton.setTooltip(Tooltip.create(Component.translatable("rsi.planning.tasks.visibility_hint")));
        updateVisibilityButton();
        int paginationLeft = left + footerButtonWidth + 6;
        int paginationRight = left + contentWidth - footerButtonWidth - 6;
        paginationCenterX = (paginationLeft + paginationRight) / 2;
        if (rows.size() > rowsPerPage) {
            Button back = addRenderableWidget(new ActionButton(paginationLeft, panelY + panelHeight - 30, 20, 22,
                    Component.literal("‹"), false, button -> { page--; init(); }));
            back.active = page > 0;
            back.setTooltip(Tooltip.create(Component.translatable("rsi.planning.tasks.previous")));
            Button next = addRenderableWidget(new ActionButton(paginationRight - 20, panelY + panelHeight - 30, 20, 22,
                    Component.literal("›"), false, button -> { page++; init(); }));
            next.active = (page + 1) * rowsPerPage < rows.size();
            next.setTooltip(Tooltip.create(Component.translatable("rsi.planning.tasks.next")));
        }
        addRenderableWidget(new ActionButton(left + contentWidth - footerButtonWidth,
                panelY + panelHeight - 30, footerButtonWidth, 22,
                Component.translatable(closePage == null ? "gui.back" : "rsi.monitor.back"), false, button -> onClose()));
    }

    private void updateVisibilityButton() {
        visibilityButton.active = RSIntegrationConfig.CLIENT_SPEC.isLoaded();
        visibilityButton.setMessage(Component.translatable(PlanningProgressOverlay.isVisible()
                ? "rsi.planning.tasks.hide" : "rsi.planning.tasks.show"));
    }

    @Override public void tick() {
        updateVisibilityButton();
        List<Row> next = readRows();
        // 图标的 ItemStack 复制不能触发每 tick 重建按钮和丢失键盘焦点。
        if (rows.size() != next.size()) { init(); return; }
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).sameContent(next.get(i))) { init(); return; }
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < panelX || mouseX >= panelX + panelWidth || mouseY < panelY || mouseY >= panelY + panelHeight) return false;
        int next = Math.max(0, Math.min(Math.max(0, (rows.size() - 1) / rowsPerPage), page + (delta < 0 ? 1 : -1)));
        if (delta != 0 && next != page) { page = next; init(); return true; }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    @Override public void onClose() {
        if (closePage != null) closePage.run();
        else minecraft.setScreen(previous);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        CraftingUiStyle.panel(graphics, panelX, panelY, panelWidth, panelHeight);
        CraftingUiStyle.planIcon(graphics, panelX + 14, panelY + 16, CraftingUiStyle.accent());
        graphics.drawString(font, title, panelX + 36, panelY + 13, CraftingUiStyle.text(), false);
        Component hint = monitorNotice != null ? monitorNotice : Component.translatable(network == null
                ? "rsi.planning.tasks.personal_hint" : "rsi.planning.tasks.network_hint");
        graphics.drawString(font, CraftingUiStyle.fit(font, hint.getString(), panelWidth - 50),
                panelX + 36, panelY + 29, monitorNotice == null ? CraftingUiStyle.muted() : CraftingUiStyle.danger(), false);
        Component count = Component.translatable("rsi.planning.tasks.count", rows.size());
        graphics.drawString(font, count, panelX + panelWidth - font.width(count) - 14,
                panelY + 13, CraftingUiStyle.muted(), false);
        graphics.fill(panelX + 12, panelY + panelHeight - FOOTER_HEIGHT,
                panelX + panelWidth - 12, panelY + panelHeight - FOOTER_HEIGHT + 1, CraftingUiStyle.border());
        if (rows.isEmpty()) {
            centered(graphics, Component.translatable("rsi.planning.tasks.empty"), panelY + HEADER_HEIGHT + 6, CraftingUiStyle.text());
            centered(graphics, Component.translatable("rsi.planning.tasks.empty_hint"), panelY + HEADER_HEIGHT + 23, CraftingUiStyle.muted());
        }
        if (rows.size() > rowsPerPage) {
            String pages = (page + 1) + " / " + ((rows.size() + rowsPerPage - 1) / rowsPerPage);
            graphics.drawString(font, pages, paginationCenterX - font.width(pages) / 2,
                    panelY + panelHeight - 23, CraftingUiStyle.muted(), false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        // 托管在原生监控器中时，原版 Tooltip 会排入外层 Screen；这里直接绘制完整条目名称。
        for (var child : children()) if (child instanceof PlanRowButton button && button.isHovered()) {
            Component tooltip = button.cancelHovered(mouseX, mouseY)
                    ? Component.translatable(button.row.completed()
                            ? "rsi.planning.tasks.cancel_plan_hint" : "rsi.planning.tasks.cancel_calculation_hint")
                    : button.getMessage();
            graphics.renderComponentTooltip(font, List.of(tooltip), mouseX, mouseY);
        }
    }

    private void openExecutionMonitor() {
        if (closePage != null) {
            closePage.run();
            return;
        }
        monitorNotice = null;
        executionMonitorButton.setTooltip(null);
        ExecutionMonitorClient.open();
    }

    public void showMonitorNotice(Component message) {
        monitorNotice = message;
        if (executionMonitorButton != null) executionMonitorButton.setTooltip(Tooltip.create(message));
    }

    private void centered(GuiGraphics graphics, Component text, int y, int color) {
        String line = CraftingUiStyle.fit(font, text.getString(), panelWidth - 28);
        graphics.drawString(font, line, panelX + (panelWidth - font.width(line)) / 2, y, color, false);
    }

    private record Row(long id, String name, String stateKey, boolean completed, boolean cancellable,
                       ItemStack icon, int repeatCount) {
        boolean sameContent(Row other) {
            return id == other.id && name.equals(other.name) && stateKey.equals(other.stateKey)
                    && completed == other.completed && cancellable == other.cancellable
                    && repeatCount == other.repeatCount && ItemStack.isSameItemSameTags(icon, other.icon);
        }
    }

    private final class PlanRowButton extends Button {
        private final Row row;
        private final Runnable cancel;

        private PlanRowButton(int x, int y, int width, Row row, OnPress press, Runnable cancel) {
            super(x, y, width, ROW_HEIGHT - 4,
                    Component.literal(row.name()).append(" · ").append(Component.translatable(row.stateKey())),
                    press, DEFAULT_NARRATION);
            this.row = row;
            this.cancel = cancel;
            active = row.completed() || row.cancellable();
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && active && visible && cancelHovered(mouseX, mouseY)) {
                cancel.run();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private boolean cancelHovered(double mouseX, double mouseY) {
            return row.cancellable() && mouseX >= getX() + getWidth() - 28
                    && mouseX < getX() + getWidth() - 4
                    && mouseY >= getY() && mouseY < getY() + getHeight();
        }

        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int accent = row.completed() && row.stateKey().equals("rsi.planning.tasks.ready")
                    ? CraftingUiStyle.success() : row.completed() ? CraftingUiStyle.warning()
                    : row.cancellable() ? CraftingUiStyle.accent() : CraftingUiStyle.warning();
            CraftingUiStyle.surface(graphics, getX(), getY(), getWidth(), getHeight(),
                    active && isHoveredOrFocused() ? CraftingUiColors.ROW_HOVER.argb() : CraftingUiColors.ROW.argb(),
                    active && isHoveredOrFocused() ? CraftingUiStyle.accent() : CraftingUiStyle.border());
            graphics.fill(getX(), getY() + 5, getX() + 2, getY() + getHeight() - 5, accent);
            graphics.fill(getX() + 8, getY() + 8, getX() + 34, getY() + 34, CraftingUiStyle.canvas());
            if (!row.icon().isEmpty()) InkFluidRenderer.render(graphics, row.icon(), getX() + 13, getY() + 13);
            else CraftingUiStyle.planIcon(graphics, getX() + 14, getY() + 13, accent);
            int textX = getX() + 44;
            int textWidth = Math.max(1, getWidth() - 66 - (row.cancellable() ? 28 : 0));
            graphics.drawString(font, CraftingUiStyle.fit(font, row.name(), textWidth), textX, getY() + 8, CraftingUiStyle.text(), false);
            Component description = Component.translatable(row.stateKey()).append(" · ").append(
                    row.completed() ? Component.translatable("rsi.planning.tasks.open_tree", row.repeatCount())
                            : Component.translatable(row.cancellable() ? "rsi.planning.tasks.background" : "rsi.planning.tasks.finished"));
            graphics.drawString(font, CraftingUiStyle.fit(font, description.getString(), textWidth),
                    textX, getY() + 25, accent, false);
            if (row.cancellable()) {
                int actionX = getX() + getWidth() - 28;
                graphics.fill(actionX - 1, getY() + 7, actionX, getY() + getHeight() - 7,
                        CraftingUiStyle.border());
                int color = cancelHovered(mouseX, mouseY) ? CraftingUiStyle.danger() : CraftingUiStyle.muted();
                graphics.drawString(font, "×", actionX + 8, getY() + 15, color, false);
            } else if (active) {
                graphics.drawString(font, "›", getX() + getWidth() - 14, getY() + 17, accent, false);
            }
        }
    }
}
