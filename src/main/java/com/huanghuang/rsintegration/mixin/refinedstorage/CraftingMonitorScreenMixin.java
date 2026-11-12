package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.crafting.plan.CraftingPlanScreen;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.client.gui.ScrollBarStyle;
import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import com.huanghuang.rsintegration.crafting.plan.PlanScreenHost;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorNavigationAccess;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressTracker;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorMenuAccess;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorPlansButton;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorDetailsButton;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorDetailsPanel;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorGridStyle;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorGridRenderer;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorTaskList;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorScopeState;
import com.huanghuang.rsintegration.mods.rs.monitor.RsiMonitorTask;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorClient;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationClient;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorReturnButton;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.blockentity.craftingmonitor.ICraftingMonitor;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import com.refinedmods.refinedstorage.screen.widget.ScrollbarWidget;
import com.refinedmods.refinedstorage.screen.widget.TabListWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorLayout;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Mixin(value = CraftingMonitorScreen.class, remap = false)
public abstract class CraftingMonitorScreenMixin extends BaseScreen<CraftingMonitorContainerMenu>
        implements PlanScreenHost, MonitorNavigationAccess {
    @Shadow private List<IGridTab> tasks;
    @Shadow @Final private ICraftingMonitor craftingMonitor;
    @Shadow private Button cancelButton;
    @Shadow private Button cancelAllButton;
    @Shadow private IGridTab getCurrentTab() { throw new AssertionError(); }
    @Shadow private void updateScrollbar() { throw new AssertionError(); }
    @Shadow @Final @Mutable private ScrollbarWidget scrollbar;
    @Shadow @Final private IElementDrawers drawers;

    @Unique private List<IGridTab> rsi$nativeTasks = List.of();
    @Unique private final Map<UUID, RsiMonitorTask> rsi$tasks = new HashMap<>();
    @Unique private Screen rsi$page;
    @Unique private MonitorPlansButton rsi$plansButton;
    @Unique private Button rsi$plansAction;
    @Unique private MonitorDetailsPanel rsi$details;
    @Unique private MonitorTaskList rsi$taskList;
    @Unique private MonitorLayout rsi$layout;
    @Unique private boolean rsi$compactDetails;
    @Unique private MonitorScopeState.Scope rsi$lastScope;
    @Unique private long rsi$submittedRequest;
    @Unique private UUID rsi$pendingSelection;
    @Unique private GlobalPos rsi$returnTerminal;

    protected CraftingMonitorScreenMixin(CraftingMonitorContainerMenu menu, int width, int height,
                                         Inventory inventory, Component title) {
        super(menu, width, height, inventory, title);
    }

    /** BaseScreen 在计算居中位置前调用此钩子。窄窗口保留原宽度，用侧按钮切换详情。 */
    @Override protected void onPreInit() {
        rsi$layout = MonitorLayout.forViewport(width, height);
        imageWidth = rsi$layout.width();
        imageHeight = rsi$layout.height();
    }

    @Unique private boolean rsi$detailsReplaceGrid() {
        return rsi$layout.detailsWidth() == 0 && rsi$compactDetails;
    }

    @Unique private void rsi$updateDetailsVisibility() {
        rsi$details.setVisible(rsi$layout.detailsWidth() > 0 || rsi$compactDetails);
        cancelButton.visible = cancelAllButton.visible = rsi$plansAction.visible = !rsi$detailsReplaceGrid();
    }

    @Unique private CraftingMonitorScreen rsi$screen() { return (CraftingMonitorScreen) (Object) this; }
    @Unique private MonitorScopeState.Scope rsi$scope() {
        return rsi$screen().getMenu() instanceof MonitorMenuAccess access ? access.rsi$monitorScope().current() : null;
    }

    @ModifyVariable(method = "setTasks", at = @At("HEAD"), argsOnly = true, require = 1)
    private List<IGridTab> rsi$mergeNativeUpdate(List<IGridTab> incoming) {
        rsi$nativeTasks = List.copyOf(incoming);
        return rsi$mergedTasks();
    }

    @Unique private List<IGridTab> rsi$mergedTasks() {
        var scope = rsi$scope();
        List<UUID> ids = scope == null ? List.of() : scope.crafts();
        rsi$tasks.keySet().retainAll(ids);
        var merged = new ArrayList<>(rsi$nativeTasks);
        for (UUID id : ids) {
            var snapshot = CraftProgressTracker.get(id);
            if (snapshot == null) continue;
            RsiMonitorTask task = rsi$tasks.get(id);
            CraftMaterialView materials = getMenu() instanceof MonitorMenuAccess access
                    ? access.rsi$monitorScope().materials().view(id) : CraftMaterialView.EMPTY;
            if (task == null || !task.snapshot().equals(snapshot) || task.materials() != materials) {
                task = new RsiMonitorTask(CraftProgressTracker.target(id), snapshot, materials);
                rsi$tasks.put(id, task);
            }
            merged.add(task);
        }
        return merged;
    }

    @ModifyVariable(method = "onPostInit", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private int rsi$centerMonitorWithTaskList(int x) {
        leftPos = rsi$layout.mainLeft(width);
        return leftPos;
    }

    @Inject(method = "onPostInit", at = @At("TAIL"), require = 1)
    private void rsi$addPlans(int x, int y, CallbackInfo ci) {
        // 调整窗口或 GUI 比例会再次初始化，不能清掉已经记录的原终端。
        if (rsi$returnTerminal == null) rsi$returnTerminal = RsMonitorNavigationClient.consumePendingTerminal();
        addSideButton(new RsMonitorReturnButton(rsi$screen(), getMenu(), this));
        scrollbar = new ScrollbarWidget(rsi$screen(), rsi$layout.gridRight() + ScrollBarStyle.CONTENT_GAP,
                20, ScrollBarStyle.WIDTH, rsi$layout.gridHeight());
        int footerY = y + rsi$layout.footerY();
        rsi$plansButton = new MonitorPlansButton(rsi$screen(), this::rsi$showPlans);
        rsi$screen().addSideButton(rsi$plansButton);
        // 保留原生按钮的回调，只替换外观及宽度；取消仍经过原来的来源分派。
        Button nativeCancel = cancelButton;
        Button nativeCancelAll = cancelAllButton;
        removeWidget(nativeCancel);
        removeWidget(nativeCancelAll);
        rsi$plansAction = addRenderableWidget(new ActionButton(x + 7, footerY, 76, 20,
                Component.translatable("rsi.monitor.open_plans"), true, button -> rsi$showPlans()));
        cancelButton = addRenderableWidget(new ActionButton(x + 87, footerY, 76, 20,
                Component.translatable("rsi.monitor.cancel_selected"), false, button -> nativeCancel.onPress()));
        cancelAllButton = addRenderableWidget(new ActionButton(x + 167, footerY, 80, 20,
                Component.translatable("rsi.monitor.cancel_all_rs"), false, button -> nativeCancelAll.onPress()));
        if (rsi$page != null) rsi$page.init(Minecraft.getInstance(), rsi$screen().width, rsi$screen().height);
        cancelAllButton.setTooltip(Tooltip.create(Component.translatable("rsi.monitor.cancel_all_rs_hint")));
        cancelButton.setTooltip(Tooltip.create(Component.translatable("rsi.monitor.cancel_selected")));
        String query = rsi$details == null ? "" : rsi$details.query();
        boolean activeOnly = rsi$details == null || rsi$details.onlyActive();
        int detailsX = rsi$layout.detailsWidth() > 0 ? x + rsi$layout.materialWidth() + 7 : x + 7;
        int detailsWidth = rsi$layout.detailsWidth() > 0 ? MonitorDetailsPanel.DETAILS_WIDTH - 14 : imageWidth - 14;
        rsi$details = new MonitorDetailsPanel(rsi$screen(), detailsX, y, detailsWidth, imageHeight,
                widget -> addRenderableWidget(widget), index -> {
                    scrollbar.setOffset(index / 3);
                    if (rsi$detailsReplaceGrid()) { rsi$compactDetails = false; rsi$updateDetailsVisibility(); }
                }, query, activeOnly);
        if (rsi$layout.detailsWidth() == 0) addSideButton(new MonitorDetailsButton(rsi$screen(), () -> {
            rsi$compactDetails = !rsi$compactDetails;
            rsi$updateDetailsVisibility();
        }));
        rsi$updateDetailsVisibility();
        rsi$taskList = new MonitorTaskList(Math.max(1, x - rsi$layout.railWidth() - 4), y, rsi$layout.railWidth(), imageHeight, drawers, id -> {
            if (RsMonitorClient.select(craftingMonitor, id)) {
                scrollbar.setOffset(0);
                updateScrollbar();
            }
        }, widget -> addRenderableWidget(widget));
        // 左边留给任务选择，原生红石和 RSI 功能按钮统一移至右边，避免互相遮挡。
        for (var side : getSideButtons()) side.setX(x + imageWidth + 3);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void rsi$refreshTasks(int x, int y, CallbackInfo ci) {
        var scope = rsi$scope();
        if (rsi$lastScope != null && (scope == null || !Objects.equals(scope.network(), rsi$lastScope.network()))) {
            rsi$setPlanPage(null);
            rsi$pendingSelection = null;
        }
        rsi$lastScope = scope;
        List<IGridTab> merged = rsi$mergedTasks();
        if (!tasks.equals(merged)) tasks = merged;
        if (craftingMonitor.isActiveOnClient() && !tasks.isEmpty() && getCurrentTab() == null) {
            RsMonitorClient.select(craftingMonitor, MonitorTaskList.id(tasks.get(0)));
            scrollbar.setOffset(0);
        }
        if (rsi$pendingSelection != null && rsi$tasks.containsKey(rsi$pendingSelection)) {
            if (RsMonitorClient.select(craftingMonitor, rsi$pendingSelection)) scrollbar.setOffset(0);
            rsi$pendingSelection = null;
        }
        if (rsi$plansButton != null) rsi$plansButton.active = scope != null && scope.network() != null;
        if (rsi$plansAction != null) rsi$plansAction.active = scope != null && scope.network() != null;
        cancelAllButton.setMessage(Component.translatable("rsi.monitor.cancel_all_rs"));
        cancelAllButton.active = !rsi$nativeTasks.isEmpty();
        if (getCurrentTab() instanceof RsiMonitorTask task) {
            cancelButton.active = !task.snapshot().isTerminal()
                    && task.snapshot().result() != CraftProgressSnapshot.Result.STOPPING;
        }
        if (rsi$page != null) rsi$page.tick();
        if (rsi$taskList != null) rsi$taskList.update(craftingMonitor.isActiveOnClient() ? tasks : List.of(),
                craftingMonitor.getTabSelected().orElse(null));
        if (rsi$page == null && rsi$details != null && rsi$details.visible()) rsi$details.update(craftingMonitor.isActiveOnClient() ? getCurrentTab() : null,
                craftingMonitor.getTabSelected().orElse(null));
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$renderMonitorPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, CallbackInfo ci) {
        ci.cancel();
        boolean online = craftingMonitor.isActiveOnClient();
        CraftingUiStyle.raised(graphics, x, y, rsi$screen().getXSize(), rsi$screen().getYSize(),
                CraftingUiStyle.panel(), CraftingUiStyle.border());
        if (rsi$detailsReplaceGrid()) {
            rsi$details.render(graphics, mouseX, mouseY, drawers);
            return;
        }
        CraftingUiStyle.inset(graphics, x + 7, y + 20, rsi$layout.gridRight() - 7, rsi$layout.gridHeight());
        graphics.fill(x + 7, y + 20, x + rsi$layout.gridRight(), y + 20 + rsi$layout.gridHeight(), CraftingUiStyle.gridLine());
        // 网格位置和滚动偏移严格沿用 RS；保留原生及第三方元素的深色文字和 tooltip。
        int offset = scrollbar.getOffset() * 3;
        var elements = rsi$screen().getElements();
        for (int index = 0; index < 15; index++) {
            int cellX = x + 7 + index % 3 * (rsi$layout.cellWidth() + 1);
            int cellY = y + 20 + index / 3 * (rsi$layout.cellHeight() + 1);
            int color = offset + index < elements.size()
                    ? MonitorGridStyle.nativeElement(elements.get(offset + index)).color() : CraftingUiStyle.canvas();
            graphics.fill(cellX, cellY, cellX + rsi$layout.cellWidth(), cellY + rsi$layout.cellHeight(), color);
        }
        int barX = x + rsi$layout.gridRight() + ScrollBarStyle.CONTENT_GAP;
        // 始终画轨道与短滑块；数量、滚轮和拖动继续由 RS 原生 scrollbar 管理。
        ScrollBarStyle.render(graphics, barX, y + 20, ScrollBarStyle.WIDTH, rsi$layout.gridHeight(),
                scrollbar.getOffset(), Math.max(0, (elements.size() + 2) / 3 - 5),
                mouseX >= barX && mouseX < barX + ScrollBarStyle.WIDTH
                        && mouseY >= y + 20 && mouseY < y + 20 + rsi$layout.gridHeight());
        if (rsi$details != null && rsi$details.visible()) {
            graphics.fill(x + rsi$layout.materialWidth(), y + 7, x + rsi$layout.materialWidth() + 1,
                    y + imageHeight - 7, CraftingUiStyle.border());
            rsi$details.render(graphics, mouseX, mouseY, drawers);
        }
    }

    @Inject(method = {"mouseScrolled", "m_6050_"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$scrollSteps(double mouseX, double mouseY, double delta, CallbackInfoReturnable<Boolean> cir) {
        if (rsi$taskList != null && rsi$taskList.mouseScrolled(mouseX, mouseY, delta)) cir.setReturnValue(true);
        else if (rsi$details != null && rsi$details.mouseScrolled(mouseX, mouseY, delta)) cir.setReturnValue(true);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (rsi$taskList != null && rsi$taskList.mouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (rsi$details != null && rsi$details.mouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Inject(method = {"mouseReleased", "m_6348_"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$releaseLists(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        boolean handled = rsi$taskList != null && rsi$taskList.mouseReleased(mouseX, mouseY, button);
        if (rsi$details != null) handled |= rsi$details.mouseReleased(mouseX, mouseY, button);
        if (handled) cir.setReturnValue(true);
    }

    // 纵向列表完全替代顶部标签，包括隐藏标签的点击、提示和翻页按钮。
    @Redirect(method = "onPostInit", at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/screen/widget/TabListWidget;init(I)V"), require = 1)
    private void rsi$skipTopTabsInit(TabListWidget<?> widget, int width) {}

    @Redirect(method = "tick", at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/screen/widget/TabListWidget;update()V"), require = 1)
    private void rsi$skipTopTabsUpdate(TabListWidget<?> widget) {}

    @Redirect(method = {"mouseClicked", "m_6375_"}, at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/screen/widget/TabListWidget;mouseClicked()Z"), require = 1)
    private boolean rsi$skipTopTabsClick(TabListWidget<?> widget) { return false; }

    @Inject(method = {"mouseClicked", "m_6375_"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$selectStep(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (rsi$details != null && rsi$details.mouseClicked(mouseX, mouseY, button)) cir.setReturnValue(true);
        else if (rsi$detailsReplaceGrid() && mouseY >= topPos + 20 && mouseY < topPos + 20 + rsi$layout.gridHeight()) {
            cir.setReturnValue(super.mouseClicked(mouseX, mouseY, button));
        }
    }

    @Inject(method = "renderForeground", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$renderMaterials(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        ci.cancel();
        if (rsi$detailsReplaceGrid()) {
            rsi$details.tooltip(graphics, mouseX, mouseY);
            return;
        }
        graphics.drawString(font, title, 7, 7, CraftingUiStyle.text(), false);
        Component count = Component.translatable("rsi.monitor.task_count", tasks.size());
        graphics.drawString(font, count, rsi$layout.materialWidth() - 7 - font.width(count), 7, CraftingUiStyle.muted(), false);
        var elements = rsi$screen().getElements();
        int offset = scrollbar.getOffset() * 3;
        List<Component> hovered = List.of();
        for (int index = 0; index < 15 && offset + index < elements.size(); index++) {
            int cellX = 7 + index % 3 * (rsi$layout.cellWidth() + 1);
            int cellY = 20 + index / 3 * (rsi$layout.cellHeight() + 1);
            var element = elements.get(offset + index);
            MonitorGridRenderer.drawNative(element, graphics, cellX, cellY, drawers, rsi$layout.cellWidth(), rsi$layout.cellHeight());
            if (mouseX >= cellX && mouseX < cellX + rsi$layout.cellWidth()
                    && mouseY >= cellY && mouseY < cellY + rsi$layout.cellHeight()) hovered = element.getTooltip();
        }
        if (!hovered.isEmpty()) graphics.renderComponentTooltip(font, hovered, mouseX, mouseY);
        if (elements.isEmpty()) {
            boolean online = craftingMonitor.isActiveOnClient();
            boolean waiting = getCurrentTab() instanceof RsiMonitorTask;
            Component heading = Component.translatable(!online ? "rsi.monitor.offline" : waiting ? "rsi.monitor.material.empty"
                    : tasks.isEmpty() ? "rsi.monitor.empty" : "rsi.monitor.select_task");
            Component detail = Component.translatable(!online ? "rsi.monitor.offline_hint"
                    : waiting ? "rsi.monitor.material.empty_hint" : "rsi.monitor.empty_hint");
            int center = rsi$layout.gridRight() / 2;
            int lineY = 76;
            for (Component text : List.of(heading, detail)) {
                for (var line : font.split(text, rsi$layout.materialWidth() - 40)) {
                    graphics.drawString(font, line, center - font.width(line) / 2, lineY, CraftingUiStyle.text(), false);
                    lineY += font.lineHeight + 3;
                }
                lineY += 7;
            }
        }
        if (rsi$details != null) rsi$details.tooltip(graphics, mouseX, mouseY);
    }

    @Inject(method = "lambda$onPostInit$3", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$cancelSelected(Button button, CallbackInfo ci) {
        if (getCurrentTab() instanceof RsiMonitorTask task) {
            ci.cancel();
            RsMonitorClient.cancel(rsi$screen().getMenu(), task.craftId());
        }
    }

    @Unique private void rsi$showPlans() {
        var scope = rsi$scope();
        if (scope == null || scope.network() == null) return;
        rsi$setPlanPage(new PlanningTasksScreen(scope.network(), request -> {
            var plan = PlanBackgroundClient.completedPlans().get(request);
            if (plan != null) rsi$setPlanPage(new CraftingPlanScreen(plan, request, null, this::rsi$showPlans));
        }, () -> rsi$setPlanPage(null)));
    }

    @Override public Screen rsi$planPage() { return rsi$page; }
    @Override public GlobalPos rsi$returnTerminal() { return rsi$returnTerminal; }
    @Override public void rsi$returnTerminal(@Nullable GlobalPos terminal) { rsi$returnTerminal = terminal; }
    @Override public void rsi$openPlans() { rsi$showPlans(); }
    @Override public void rsi$setPlanPage(@Nullable Screen page) {
        if (rsi$page != null) rsi$page.removed();
        rsi$page = page;
        if (page != null) page.init(Minecraft.getInstance(), rsi$screen().width, rsi$screen().height);
    }

    @Override public void rsi$planSubmitted(long requestId) { rsi$submittedRequest = requestId; }
    @Override public void rsi$planAccepted(long requestId, UUID craftId) {
        if (requestId <= 0L || requestId != rsi$submittedRequest) return;
        rsi$submittedRequest = 0L;
        // 用户已经切到另一份计划时不抢页面。
        if (rsi$page instanceof CraftingPlanScreen plan && plan.activeRequestId() != requestId) return;
        rsi$setPlanPage(null);
        rsi$pendingSelection = craftId;
    }
}
