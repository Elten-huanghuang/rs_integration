package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** 递归树唯一的详情侧栏：查看步骤不会修改路线，选材和换配方复用原服务端规划入口。 */
public final class PlanDetailsPanel {
    private enum Tab { NODE, MATERIALS, ISSUES, STEPS, MACHINES }

    public interface MachineControl {
        MachineSelectionMode mode();
        int selectedIndex();
        void selectMode(MachineSelectionMode mode);
        void selectCandidate(int index);
        void openTarget();
    }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    private record Row(ItemStack stack, String title, String detail, Color color,
                       @Nullable PlanTreeNode target, long shortage, boolean wrapped) {}
    private record RowHit(int top, int bottom, Row row, @Nullable ItemStack variant) {}
    private record Action(int x, int y, int w, int h, Runnable click) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Font font;
    private final PlanWorkspaceChrome chrome;
    private final RecipeCandidatePanel recipes;
    private final Function<PlanTreeNode, PlanTreeNode> candidateContext;
    private final BiConsumer<PlanTreeNode, ItemStack> onMaterial;
    private final Consumer<PlanTreeNode> onNavigate;
    private final BiConsumer<ItemStack, Long> onBookmark;
    private final Runnable bookmarkAll;
    private final BooleanSupplier waiting;
    private final Runnable recheck;
    private final Consumer<ResourceLocation> openRecipe;
    private final MachineControl machines;
    private final EditBox search;
    private final List<Action> actions = new ArrayList<>();
    private final List<RowHit> rowHits = new ArrayList<>();
    private PlanResponse plan;
    private PlanTreeNode root;
    private PlanTreeNode node;
    private PlanNodeSelection selection;
    private Bounds bounds;
    private Tab tab = Tab.NODE;
    private boolean variants;
    private boolean conditions;
    private boolean inputs;
    private boolean keyboardFocus;
    private boolean closed = true;
    private boolean recipePage;
    private Runnable showAllMaterials = () -> {};
    private boolean recipeVisible;
    private int listTop, listBottom, listScroll, listMaxScroll;
    private int nodeHeaderScroll, nodeHeaderMaxScroll;
    private List<Row> materialRows = List.of();
    private List<Row> missingRows = List.of();
    private List<Row> conditionRows = List.of();
    private List<Component> previewFailure = List.of();
    private List<Row> stepRows = List.of();
    private List<Row> machineRows = List.of();
    private List<Row> variantRows = List.of();
    private List<Row> filteredSource;
    private String filteredQuery;
    private List<Row> filteredRows = List.of();
    private record MeasuredRow(Row row, int top, int height, List<String> lines) {}
    private List<Row> measuredSource;
    private List<MeasuredRow> measuredRows = List.of();
    private int measuredWidth, measuredBaseHeight, measuredFullHeight;
    private boolean measuredTallShortages;
    private final Map<Row, Integer> machineIndices = new IdentityHashMap<>();
    private final Map<IngredientKey, PlanTreeNode> firstNodeByKey = new LinkedHashMap<>();
    private Row hoveredRow;
    private boolean hoveredItemIcon;
    private ItemStack hoveredRecipeItem = ItemStack.EMPTY;

    public PlanDetailsPanel(Font font, RecipeCandidatePanel recipes,
                            Function<PlanTreeNode, PlanTreeNode> candidateContext,
                            BiConsumer<PlanTreeNode, ItemStack> onMaterial,
                            Consumer<PlanTreeNode> onNavigate,
                            BiConsumer<ItemStack, Long> onBookmark,
                            Runnable bookmarkAll, BooleanSupplier waiting,
                            Runnable recheck, Consumer<ResourceLocation> openRecipe, MachineControl machines) {
        this.font = font;
        this.chrome = new PlanWorkspaceChrome(font);
        this.recipes = recipes;
        this.candidateContext = candidateContext;
        this.onMaterial = onMaterial;
        this.onNavigate = onNavigate;
        this.onBookmark = onBookmark;
        this.bookmarkAll = bookmarkAll;
        this.waiting = waiting;
        this.recheck = recheck;
        this.openRecipe = openRecipe;
        this.machines = machines;
        search = new EditBox(font, 0, 0, 100, 16, text("search"));
        search.setHint(text("search"));
        search.setMaxLength(100);
        search.setBordered(false);
        search.setResponder(value -> listScroll = 0);
        search.visible = search.active = false;
    }

    public int shortageCount() { return missingRows.size(); }
    public int conditionCount() { return conditionRows.size(); }
    public void setShowAllMaterials(Runnable action) { showAllMaterials = action; }
    public void layout(PlanWorkspaceLayout.Rect rect) { bounds = new Bounds(rect.x(), rect.y(), rect.width(), rect.height()); }
    public void showConditions() { showIssues(); conditions = true; }
    public void showSteps() { showMode(Tab.STEPS); }
    public void showMachines() { showMode(Tab.MACHINES); }
    private void showMode(Tab mode) { closed = false; tab = mode; variants = recipePage = false; resetSearch(); }
    public EditBox searchBox() { return search; }
    public boolean isOpen() { return node != null && !closed; }
    public PlanTreeNode selectedNode() { return node; }
    public boolean contains(double x, double y) { return isOpen() && bounds != null && bounds.contains(x, y); }
    public int contentRight(int fallback) { return isOpen() && bounds != null ? bounds.x() - 8 : fallback; }

    /** 只在计划/库存更新时重建数据，平移和缩放不遍历材料账单。 */
    public void update(PlanResponse plan, PlanTreeNode root, List<Component> warnings) {
        IngredientKey previousKey = node == null ? null : node.key;
        String recipeQuery = recipes.searchBox().getValue();
        this.plan = plan;
        this.root = root;
        previewFailure = List.of();
        node = selection == null ? root : selection.resolve(root);
        selection = PlanNodeSelection.capture(root, node);
        bindRecipes();
        if (node.key.equals(previousKey) && recipeQuery != null) recipes.searchBox().setValue(recipeQuery);
        rebuildRows(warnings);
    }

    public void showPreviewFailure(List<Component> messages) {
        previewFailure = messages.isEmpty() ? List.of(text("switch_failed")) : List.copyOf(messages);
        List<Row> rows = new ArrayList<>(conditionRows);
        for (Component message : previewFailure) rows.add(0, new Row(ItemStack.EMPTY,
                text("switch_failed").getString(), message.getString(), Color.ERROR, null, 0, true));
        conditionRows = List.copyOf(rows);
    }

    public void updateInventory(PlanResponse plan, List<Component> warnings) {
        this.plan = plan;
        rebuildMaterialRows();
    }

    public void open(PlanTreeNode selected) {
        node = selected;
        selection = PlanNodeSelection.capture(root, selected);
        closed = false;
        tab = Tab.NODE;
        recipePage = false;
        variants = false;
        inputs = false;
        resetSearch();
        bindRecipes();
    }

    /** 诊断列表定位只更新选中节点，保留页签、搜索和滚动位置。 */
    public void locate(PlanTreeNode selected) {
        if (node == selected) return;
        node = selected;
        selection = PlanNodeSelection.capture(root, selected);
        // 留在诊断/步骤列表时无需构建候选；返回详情时会重新绑定。
        if (tab == Tab.NODE) bindRecipes();
    }

    private void bindRecipes() {
        if (!isOpen()) return;
        rebuildNodeRows();
        PlanTreeNode context = candidateContext.apply(node);
        Set<String> machines = plan.boundMachineTypes() == null ? Set.of() : plan.boundMachineTypes();
        if (recipePage) recipes.open(context == null ? node : context, machines);
        else recipes.bind(context == null ? node : context, machines);
    }

    private void rebuildNodeRows() {
        List<Row> variants = new ArrayList<>();
        variants.add(new Row(ItemStack.EMPTY, text("auto_material").getString(),
                text("material_scope").getString(), Color.MUTED, null, 0, false));
        for (ItemStack option : node.materialOptions) {
            variants.add(new Row(option, option.getHoverName().getString(),
                    BuiltInRegistries.ITEM.getKey(option.getItem()).toString(),
                    ItemStack.isSameItemSameTags(option, node.displayStack) ? Color.ACCENT : Color.TEXT,
                    null, 0, false));
        }
        variantRows = List.copyOf(variants);
    }

    public void expectMaterial(ItemStack material) {
        if (!material.isEmpty() && selection != null) {
            selection = selection.withMaterial(IngredientKey.of(material));
        }
        variants = false;
        resetSearch();
    }

    public void showIssues() {
        closed = false;
        tab = Tab.ISSUES;
        conditions = false;
        recipePage = false;
        variants = false;
        resetSearch();
    }

    public void close() {
        closed = true;
        recipes.close();
        search.visible = search.active = false;
        search.setFocused(false);
        keyboardFocus = false;
        actions.clear();
        rowHits.clear();
    }

    public void releaseFocus() {
        keyboardFocus = false;
        search.setFocused(false);
        recipes.searchBox().setFocused(false);
    }

    public void tick() {
        if (!isOpen()) return;
        if (search.visible) search.tick();
        if (recipeVisible) recipes.tick();
    }

    public static Bounds layoutBounds(int screenWidth, int screenHeight, int top, int bottom,
                                       int preferredWidth) {
        int margin = Math.min(8, Math.max(0, screenWidth / 20));
        int width = Math.max(1, Math.min(preferredWidth, Math.max(1, (screenWidth - 2 * margin) * 48 / 100)));
        int y = Math.max(0, Math.min(top, screenHeight - 1));
        int end = Math.max(y + 1, Math.min(screenHeight, bottom));
        return new Bounds(Math.max(0, screenWidth - margin - width), y, width, end - y);
    }

    public void layout(int screenWidth, int screenHeight, int top, int bottom) {
        bounds = layoutBounds(screenWidth, screenHeight, top, bottom, PlanUiConfig.panelWidth());
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isOpen() || bounds == null) return;
        actions.clear(); rowHits.clear();
        hoveredRow = null; hoveredItemIcon = false; recipeVisible = false;
        hoveredRecipeItem = ItemStack.EMPTY;
        search.visible = search.active = false;
        recipes.searchBox().visible = recipes.searchBox().active = false;
        int x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        graphics.flush();
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 500);
        try {
            PlanWorkspaceChrome.panel(graphics, new PlanWorkspaceLayout.Rect(x, y, w, h), Color.PANEL, Color.BORDER);
            graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            try {
                String heading = tab == Tab.ISSUES ? text(conditions ? "conditions" : "shortages",
                        conditions ? conditionRows.size() : missingRows.size()).getString()
                        : tab == Tab.STEPS ? text("tab_steps").getString() : tab == Tab.MACHINES ? text("machines").getString()
                        : variants ? text("material_choices").getString() : recipePage ? text("recipes").getString() : text("tab_node").getString();
                boolean roomy = h >= 230;
                chrome.heading(graphics, heading, x + 10, y + 9, w - 42, roomy ? 1.35f : 1);
                int tabY = y + (roomy ? 45 : 33);
                graphics.drawString(font, fit(text(tab == Tab.NODE ? "view_hint" : tab == Tab.ISSUES
                        ? conditions ? "conditions_hint" : "shortages_hint" : "navigate_hint").getString(), w - 16),
                        x + 10, y + (roomy ? 29 : 22), Color.MUTED.value(), false);
                button(graphics, "×", x + w - 22, y + 4, 17, 17, false, true, mouseX, mouseY, this::close);
                int tw = (w - 12) / 3;
                button(graphics, text("tab_node").getString(), x + 5, tabY, tw - 2, 20,
                        tab == Tab.NODE || tab == Tab.STEPS, true, mouseX, mouseY, () -> { tab = Tab.NODE; variants = recipePage = false; resetSearch(); bindRecipes(); });
                button(graphics, text("shortages", missingRows.size()).getString(), x + 5 + tw, tabY, tw - 2, 20,
                        tab == Tab.ISSUES && !conditions, true, mouseX, mouseY, this::showIssues);
                button(graphics, text("conditions", conditionRows.size()).getString(), x + 5 + tw * 2, tabY, tw - 2, 20,
                        tab == Tab.ISSUES && conditions, true, mouseX, mouseY, this::showConditions);
                int top = tabY + 26;
                if (tab == Tab.NODE) renderNode(graphics, top, mouseX, mouseY);
                else {
                    if (tab == Tab.MACHINES) {
                        int modeWidth = Math.max(1, (w - 18) / 3);
                        for (MachineSelectionMode mode : MachineSelectionMode.values()) button(graphics,
                                Component.translatable("rsi.machine_candidate." + mode.name().toLowerCase(Locale.ROOT)).getString(),
                                x + 7 + mode.ordinal() * (modeWidth + 2), top, modeWidth, 18,
                                machines.mode() == mode, !waiting.getAsBoolean(), mouseX, mouseY, () -> machines.selectMode(mode));
                        top += 23;
                    }
                    renderSearch(graphics, top, mouseX, mouseY);
                    listTop = top + 23; listBottom = y + h - 26;
                    renderRows(graphics, tab == Tab.STEPS ? stepRows : tab == Tab.MACHINES ? machineRows
                            : conditions ? conditionRows : missingRows, mouseX, mouseY, false);
                    if (tab == Tab.ISSUES && !conditions) {
                        int half = (w - 18) / 2;
                        button(graphics, Component.translatable("rsi.plan.workspace.bookmark_all_short").getString(), x + 7, y + h - 22, half, 17,
                                true, !missingRows.isEmpty(), mouseX, mouseY, bookmarkAll);
                        button(graphics, text("all_materials").getString(), x + 10 + half, y + h - 22, half, 17,
                                false, true, mouseX, mouseY, showAllMaterials);
                    } else if (tab == Tab.ISSUES) button(graphics, text("recheck").getString(), x + 7, y + h - 22,
                            w - 14, 17, false, !waiting.getAsBoolean(), mouseX, mouseY, recheck);
                    else if (tab == Tab.MACHINES) button(graphics, Component.translatable("rsi.plan.open_machine").getString(),
                            x + 7, y + h - 22, w - 14, 17, false, plan.executionMachineSupportsGui()
                                    && plan.executionDim() != null && !waiting.getAsBoolean(), mouseX, mouseY, machines::openTarget);
                    else {
                        int half = (w - 18) / 2;
                        button(graphics, text("previous_step").getString(), x + 7, y + h - 22, half, 17,
                                false, !stepRows.isEmpty(), mouseX, mouseY, () -> navigateStep(-1));
                        button(graphics, text("next_step").getString(), x + 10 + half, y + h - 22, half, 17,
                                false, !stepRows.isEmpty(), mouseX, mouseY, () -> navigateStep(1));
                    }
                }
            } finally { graphics.disableScissor(); }
            graphics.flush();
            if (recipeVisible) recipes.renderEmbeddedTooltip(graphics, mouseX, mouseY);
            if (!hoveredRecipeItem.isEmpty()) graphics.renderTooltip(font, hoveredRecipeItem, mouseX, mouseY);
            if (hoveredRow != null) {
                if (hoveredItemIcon) graphics.renderTooltip(font, hoveredRow.stack(), mouseX, mouseY);
                else {
                    List<Component> tooltip = new ArrayList<>();
                    tooltip.add(Component.literal(hoveredRow.title()));
                    for (String line : wrap(hoveredRow.detail(), Math.min(320, Math.max(80, w)))) tooltip.add(Component.literal(line));
                    if (hoveredRow.target() != null) tooltip.add(text("locate_hint"));
                    if (hoveredRow.shortage() > 0 && !hoveredRow.stack().isEmpty()) tooltip.add(text("bookmark_hint"));
                    graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
                }
            }
        } finally { graphics.flush(); graphics.pose().popPose(); }
    }

    private void renderNode(GuiGraphics graphics, int top, int mouseX, int mouseY) {
        int x = bounds.x() + 7, w = bounds.width() - 14;
        int headerActions = actions.size();
        if (variants || recipePage) {
            button(graphics, text("back_step").getString(), x, top, w, 17, false, true, mouseX, mouseY,
                    () -> { variants = recipePage = false; resetSearch(); });
            top += 22;
            if (variants) {
                renderSearch(graphics, top, mouseX, mouseY);
                listTop = top + 23; listBottom = bounds.y() + bounds.height() - 6;
                renderRows(graphics, variantRows, mouseX, mouseY, true);
            } else if (node.containsCycle()) drawWrapped(graphics, text("cycle").getString(), x, top + 4, w, Color.ERROR);
            else {
                recipeVisible = true;
                recipes.layoutEmbedded(bounds.x() + 2, top, bounds.width() - 4,
                        Math.max(1, bounds.y() + bounds.height() - top - 4));
                recipes.render(graphics, mouseX, mouseY);
            }
            return;
        }
        boolean hasCycle = node.containsCycle();
        boolean hasConditions = !node.warnings.isEmpty() || !node.hints.isEmpty()
                || node.prerequisiteBlocked || node.parallelPlan != null;
        int visibleHeight = Math.max(1, bounds.y() + bounds.height() - top - 6);
        int recipeHeight = Math.max(24, Math.min(100, visibleHeight - 20));
        int controlsHeight = 78 + (!previewFailure.isEmpty() ? 22 : 0)
                + (node.depth > 0 && !hasCycle ? 23 : 0) + (hasConditions ? 23 : 0);
        int previewHeight = node.step == null
                ? wrap(text("stock_hint").getString(), w).size() * (font.lineHeight + 2) + 4 : recipeHeight + 17;
        nodeHeaderMaxScroll = Math.max(0, controlsHeight + previewHeight - visibleHeight);
        nodeHeaderScroll = Math.max(0, Math.min(nodeHeaderScroll, nodeHeaderMaxScroll));
        int clipTop = top;
        top -= nodeHeaderScroll;
        graphics.enableScissor(bounds.x() + 1, clipTop, bounds.x() + bounds.width() - 1, bounds.y() + bounds.height() - 1);
        try {
            InkFluidRenderer.render(graphics, node.displayStack, x, top + 3);
            graphics.drawString(font, fit(node.displayStack.getHoverName().getString(), w - 26), x + 23, top + 2, Color.TEXT.value(), false);
            graphics.drawString(font, fit(text("quantity", node.amount, node.step == null ? 0 : node.productionAmount).getString(), w - 26),
                    x + 23, top + 14, Color.MUTED.value(), false);
            top += 30;
            int half = (w - 4) / 2;
            button(graphics, text("locate_short").getString(), x, top, half, 18, false, true, mouseX, mouseY, () -> onNavigate.accept(node));
            button(graphics, "JEI", x + half + 4, top, half, 18, false, node.step != null, mouseX, mouseY, () -> openRecipe.accept(node.step.recipeId()));
            top += 23;
            if (!previewFailure.isEmpty()) {
                button(graphics, text("switch_failed").getString(), x, top, w, 18, true, true, mouseX, mouseY, this::showConditions);
                top += 22;
            }
            boolean canChoose = node.materialLockKey != null && node.materialOptions.size() > 1;
            button(graphics, text("material_choices").getString(), x, top, half, 20, false, canChoose, mouseX, mouseY,
                    () -> { variants = true; recipePage = false; resetSearch(); });
            button(graphics, text("recipes").getString(), x + half + 4, top, half, 20, false, !hasCycle, mouseX, mouseY,
                    () -> { recipePage = true; variants = false; resetSearch(); bindRecipes(); });
            top += 25;
            if (node.depth > 0 && !hasCycle) {
                boolean stock = recipes.stockOnlySelected();
                button(graphics, text("craft").getString(), x, top, half, 18, !stock, !waiting.getAsBoolean(), mouseX, mouseY, recipes::resumeCrafting);
                button(graphics, text("stock").getString(), x + half + 4, top, half, 18, stock, !waiting.getAsBoolean(),
                        mouseX, mouseY, () -> { if (!stock) recipes.selectNoRecipe(); });
                top += 23;
            }
            if (hasConditions) {
                button(graphics, text(node.prerequisiteBlocked ? "blocked" : "step_conditions").getString(), x, top, w, 18,
                        node.prerequisiteBlocked, true, mouseX, mouseY, () -> { showConditions(); scrollToCondition(node); });
                top += 23;
            }
            if (node.step == null) {
                drawWrapped(graphics, text("stock_hint").getString(), x, top + 2, w, Color.MUTED);
            } else {
                graphics.drawString(font, text("current_recipe"), x, top + 2, Color.MUTED.value(), false);
                int recipeY = top + 17;
                PlanWorkspaceChrome.panel(graphics, new PlanWorkspaceLayout.Rect(x, recipeY, w, recipeHeight), Color.BACKGROUND, Color.BORDER);
                if (recipeY + recipeHeight > clipTop && recipeY < bounds.y() + bounds.height()) {
                    ItemStack hovered = recipes.renderCurrentRecipe(graphics, node, x + 4, recipeY + 4, w - 8, recipeHeight - 8, mouseX, mouseY);
                    if (hovered != null && mouseY >= clipTop && mouseY < bounds.y() + bounds.height()) hoveredRecipeItem = hovered;
                }
            }
        } finally { graphics.disableScissor(); }
        if (nodeHeaderMaxScroll > 0) {
            int thumbHeight = Math.max(10, visibleHeight * visibleHeight / (visibleHeight + nodeHeaderMaxScroll));
            int thumbY = clipTop + (visibleHeight - thumbHeight) * nodeHeaderScroll / nodeHeaderMaxScroll;
            int trackX = bounds.x() + bounds.width() - 4;
            graphics.fill(trackX, clipTop, trackX + 2, clipTop + visibleHeight, Color.SURFACE.value());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, Color.ACCENT.value());
        }
        // 被裁掉的按钮不应接收画面外点击。
        actions.subList(headerActions, actions.size()).removeIf(action -> action.y() < clipTop
                || action.y() + action.h() > bounds.y() + bounds.height() - 1);
    }

    private void navigateStep(int delta) {
        if (stepRows.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < stepRows.size(); i++) if (stepRows.get(i).target() == node) { index = i; break; }
        onNavigate.accept(stepRows.get(Math.floorMod(index + delta, stepRows.size())).target());
    }

    private void scrollToCondition(PlanTreeNode target) {
        // 条件行可能换行；先按已知行高估算，下次绘制统一夹取到真实滚动范围。
        int preceding = 0;
        for (Row row : conditionRows) {
            if (row.target() == target) break;
            preceding += rowHeight(row, bounds.width() - 30);
        }
        listScroll = preceding;
    }

    private void renderSearch(GuiGraphics graphics, int y, int mouseX, int mouseY) {
        search.setPosition(bounds.x() + 11, y + 5);
        search.setWidth(Math.max(1, bounds.width() - 26));
        search.setTextColor(Color.TEXT.value());
        search.visible = search.active = true;
        graphics.fill(bounds.x() + 7, y, bounds.x() + bounds.width() - 7, y + 19, Color.SURFACE.value());
        search.render(graphics, mouseX, mouseY, 0);
    }

    private boolean matches(Row row) {
        String id = row.stack().isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(row.stack().getItem()).toString();
        return MaterialSearchMatcher.matches(row.title() + " " + row.detail(), id, search.getValue());
    }

    private int rowHeight(Row row, int width) {
        if (tab == Tab.ISSUES && !conditions && bounds.height() >= 230 && !row.stack().isEmpty()) return 52;
        if (!row.wrapped()) return PlanUiConfig.rowHeight();
        return Math.max(PlanUiConfig.rowHeight(), 16 + wrap(
                row.detail(), Math.max(1, width - (row.stack().isEmpty() ? 10 : 29))).size() * (font.lineHeight + 2));
    }

    private void renderRows(GuiGraphics graphics, List<Row> source, int mouseX, int mouseY, boolean materialOptions) {
        if (listBottom <= listTop) return;
        int x = bounds.x() + 6, width = bounds.width() - 15;
        if (filteredSource != source || !search.getValue().equals(filteredQuery)) {
            filteredSource = source;
            filteredQuery = search.getValue();
            filteredRows = source.stream().filter(this::matches).toList();
        }
        List<Row> rows = filteredRows;
        measureRows(rows, width);
        listMaxScroll = Math.max(0, measuredFullHeight - (listBottom - listTop));
        listScroll = Math.max(0, Math.min(listScroll, listMaxScroll));
        graphics.enableScissor(x, listTop, x + width + 3, listBottom);
        try {
            int start = firstVisibleRow(listScroll);
            for (int index = start; index < measuredRows.size(); index++) {
                MeasuredRow measured = measuredRows.get(index);
                Row row = measured.row();
                int y = listTop - listScroll + measured.top(), height = measured.height();
                if (y >= listBottom) break;
                if (y + height > listTop && y < listBottom) {
                    boolean hover = mouseX >= x && mouseX < x + width && mouseY >= Math.max(y, listTop)
                            && mouseY < Math.min(y + height, listBottom);
                    boolean selectedMachine = tab == Tab.MACHINES
                            && machineIndices.getOrDefault(row, -1) == machines.selectedIndex();
                    boolean shortageCard = tab == Tab.ISSUES && !conditions && row.shortage() > 0 && bounds.height() >= 230;
                    PlanWorkspaceChrome.panel(graphics, new PlanWorkspaceLayout.Rect(x, y, width, height),
                            selectedMachine ? Color.SELECTED : hover ? Color.HOVER : shortageCard ? Color.WARNING_SURFACE : Color.SURFACE,
                            shortageCard ? Color.WARNING_BORDER : Color.BORDER);
                    int textX = x + 5;
                    if (!row.stack().isEmpty()) {
                        InkFluidRenderer.render(graphics, row.stack(), x + 4, y + 5);
                        textX = x + 24;
                    }
                    int titleY = y + (shortageCard ? 7 : 4);
                    graphics.drawString(font, Component.literal(fit(row.title(), width - (textX - x) - (shortageCard ? 48 : 18)))
                                    .withStyle(ChatFormatting.BOLD), textX, titleY, Color.TEXT.value(), false);
                    if (row.wrapped()) {
                        int lineY = y + 18;
                        for (String line : measured.lines()) {
                            graphics.drawString(font, line, textX, lineY, row.color().value(), false);
                            lineY += font.lineHeight + 2;
                        }
                    }
                    else graphics.drawString(font, fit(row.detail(), width - (textX - x) - 5),
                            textX, y + (shortageCard ? 25 : 15), shortageCard ? Color.WARNING.value() : Color.MUTED.value(), false);
                    if (shortageCard) {
                        graphics.drawString(font, text("locate_hint"), textX, y + 39, Color.MUTED.value(), false);
                        int actionY = y + 6;
                        if (actionY >= listTop && actionY + 17 <= listBottom) button(graphics,
                                text("bookmark_short").getString(), x + width - 43, actionY, 38, 17,
                                false, true, mouseX, mouseY, () -> onBookmark.accept(row.stack(), row.shortage()));
                    } else if (row.shortage() > 0 && !row.stack().isEmpty()) {
                        graphics.drawString(font, "★", x + width - 14, y + 4, Color.WARNING.value(), false);
                    }
                    rowHits.add(new RowHit(Math.max(y, listTop), Math.min(y + height, listBottom), row,
                            materialOptions ? row.stack() : null));
                    if (hover) {
                        hoveredRow = row;
                        hoveredItemIcon = !row.stack().isEmpty() && mouseX < x + 23;
                    }
                }
            }
            if (rows.isEmpty()) graphics.drawString(font, text("empty"), x + 5, listTop + 8, Color.MUTED.value(), false);
        } finally {
            graphics.disableScissor();
        }
        if (listMaxScroll > 0) {
            int height = listBottom - listTop;
            int thumb = Math.min(height, Math.max(10, height * height / (height + listMaxScroll)));
            int y = listTop + (height - thumb) * listScroll / listMaxScroll;
            graphics.fill(x + width + 1, listTop, x + width + 3, listBottom, Color.SURFACE.value());
            graphics.fill(x + width + 1, y, x + width + 3, y + thumb, Color.ACCENT.value());
        }
    }

    /** 列表换行与高度只在内容/宽度变化时计算，每帧仅绘制可见行。 */
    private void measureRows(List<Row> rows, int width) {
        int base = PlanUiConfig.rowHeight();
        boolean tall = tab == Tab.ISSUES && !conditions && bounds.height() >= 230;
        if (measuredSource == rows && measuredWidth == width && measuredBaseHeight == base && measuredTallShortages == tall) return;
        measuredSource = rows;
        measuredWidth = width;
        measuredBaseHeight = base;
        measuredTallShortages = tall;
        List<MeasuredRow> result = new ArrayList<>(rows.size());
        int top = 0;
        for (Row row : rows) {
            List<String> lines = row.wrapped() ? wrap(row.detail(), Math.max(1, width - (row.stack().isEmpty() ? 10 : 29))) : List.of();
            int height = tall && !row.stack().isEmpty() ? 52
                    : Math.max(base, row.wrapped() ? 16 + lines.size() * (font.lineHeight + 2) : base);
            result.add(new MeasuredRow(row, top, height, lines));
            top += height + 3;
        }
        measuredRows = List.copyOf(result);
        measuredFullHeight = top;
    }

    private int firstVisibleRow(int scroll) {
        int low = 0, high = measuredRows.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            MeasuredRow row = measuredRows.get(middle);
            if (row.top() + row.height() <= scroll) low = middle + 1; else high = middle;
        }
        return low;
    }

    private void rebuildRows(List<Component> warnings) {
        List<PlanTreeNode> nodes = new ArrayList<>();
        collect(root, nodes);
        firstNodeByKey.clear();
        for (PlanTreeNode current : nodes) firstNodeByKey.putIfAbsent(current.key, current);
        rebuildMaterialRows();
        List<Row> issues = new ArrayList<>();
        List<Row> steps = new ArrayList<>();
        Set<String> attachedWarnings = new HashSet<>();
        Set<String> seenIssues = new HashSet<>();
        for (PlanTreeNode current : nodes) {
            String name = current.displayStack.getHoverName().getString();
            if (current.step != null) steps.add(new Row(current.displayStack, name,
                    text("step", current.depth, current.step.recipeId()).getString(), Color.TEXT, current, 0, false));
            List<Component> messages = new ArrayList<>(current.warnings);
            messages.addAll(current.hints);
            if (current.prerequisiteBlocked) messages.add(0, text("blocked"));
            if (current.parallelPlan != null) {
                ParallelPlan parallel = current.parallelPlan;
                messages.add(text("parallel", parallel.workerCount(), parallel.shareSummary(), parallel.fuelItems(),
                        parallel.catalystItems(), parallel.durabilityItems()));
                if (parallel.limitedBy() != null) messages.add(Component.literal(parallel.limitedBy()));
            }
            for (Component message : messages) {
                String value = ChatFormatting.stripFormatting(message.getString());
                attachedWarnings.add(value);
                String key = (current.step == null ? current.key.toString() : current.step.recipeId().toString()) + "|" + value;
                if (seenIssues.add(key)) issues.add(new Row(ItemStack.EMPTY, name, value,
                        current.prerequisiteBlocked ? Color.ERROR : Color.WARNING, current, 0, true));
            }
        }
        for (Component warning : warnings) {
            String value = ChatFormatting.stripFormatting(warning.getString());
            if (attachedWarnings.add(value)) issues.add(new Row(ItemStack.EMPTY, text("plan_condition").getString(),
                    value, Color.WARNING, null, 0, true));
        }
        // 显示深度/节点上限可能隐藏步骤，但不能因此吞掉服务端给出的前置条件。
        plan.stepIssues().forEach((recipe, issue) -> {
            if (nodes.stream().anyMatch(current -> current.step != null && current.step.recipeId().equals(recipe))) return;
            for (Component warning : issue.warnings()) {
                String value = ChatFormatting.stripFormatting(warning.getString());
                issues.add(new Row(ItemStack.EMPTY, recipe.toString(), value,
                        issue.blocked() ? Color.ERROR : Color.WARNING, null, 0, true));
            }
            if (issue.blocked() && issue.warnings().isEmpty()) issues.add(new Row(ItemStack.EMPTY,
                    recipe.toString(), text("blocked").getString(), Color.ERROR, null, 0, true));
        });
        conditionRows = List.copyOf(issues);
        stepRows = List.copyOf(steps);
        machineIndices.clear();
        List<Row> candidates = new ArrayList<>();
        for (int index = 0; index < plan.machineCandidates().size(); index++) {
            MachineCandidateView machine = plan.machineCandidates().get(index);
            Row row = new Row(machine.icon(), machine.icon().isEmpty() ? text("machine", index + 1).getString()
                    : machine.icon().getHoverName().getString(),
                    machine.dimension() + " " + machine.x() + ", " + machine.y() + ", " + machine.z()
                            + " · " + machine.status().getString(),
                    switch (machine.state()) {
                        case READY -> Color.READY;
                        case TEMPORARY -> Color.WARNING;
                        case INCOMPATIBLE -> Color.ERROR;
                    }, null, 0, true);
            machineIndices.put(row, index);
            candidates.add(row);
        }
        machineRows = List.copyOf(candidates);
    }

    private void rebuildMaterialRows() {
        List<Row> materials = new ArrayList<>();
        plan.materials().forEach((key, value) -> materials.add(new Row(key.stack(1),
                key.stack(1).getHoverName().getString(), text("availability", value.needed(), value.available(),
                        value.missingCount()).getString(), value.isEnough() ? Color.READY : Color.WARNING,
                firstNodeByKey.get(key), value.missingCount(), false)));
        materials.sort(Comparator.comparing((Row row) -> row.shortage() == 0).thenComparing(Row::title));
        plan.leftovers().forEach((key, value) -> materials.add(new Row(key.stack(1),
                key.stack(1).getHoverName().getString(), text("leftover", value).getString(),
                Color.REFERENCE, firstNodeByKey.get(key), 0, false)));
        materialRows = List.copyOf(materials);
        missingRows = MissingMaterialBookmarkList.textEntries(plan).stream().map(entry -> {
                PlanResponse.Availability value = entry.bookmarkable() ? plan.availability(entry.bookmark()) : null;
                return new Row(entry.bookmark(), entry.label(), value != null
                        ? text("availability", value.needed(), value.available(), value.missingCount()).getString()
                        : entry.missingCount() > 0 ? text("missing", entry.missingCount()).getString() : text("unresolved").getString(),
                        Color.WARNING, entry.bookmarkable() ? firstNodeByKey.get(IngredientKey.of(entry.bookmark())) : null,
                        entry.missingCount(), !entry.bookmarkable());
        }).toList();
    }

    private static void collect(PlanTreeNode node, List<PlanTreeNode> nodes) {
        nodes.add(node);
        for (PlanTreeNode child : node.children) collect(child, nodes);
    }

    private void button(GuiGraphics graphics, String label, int x, int y, int w, int h,
                        boolean selected, boolean enabled, int mx, int my, Runnable click) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        graphics.fill(x, y, x + w, y + h, (selected ? Color.ACCENT : Color.BORDER).value());
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                (selected ? Color.SELECTED : hover && enabled ? Color.HOVER : Color.SURFACE).value());
        String visible = fit(label, w - 4);
        graphics.drawString(font, visible, x + (w - font.width(visible)) / 2, y + (h - font.lineHeight) / 2,
                (enabled ? selected ? Color.ACCENT : Color.TEXT : Color.DISABLED).value(), false);
        if (enabled) actions.add(new Action(x, y, w, h, click));
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!contains(x, y)) { releaseFocus(); return false; }
        keyboardFocus = true;
        if (button == 0) {
            for (Action action : List.copyOf(actions)) {
                if (action.contains(x, y)) { action.click().run(); return true; }
            }
            if (search.visible && search.mouseClicked(x, y, button)) {
                search.setFocused(true);
                recipes.searchBox().setFocused(false);
                return true;
            }
        }
        search.setFocused(false);
        if (recipeVisible && recipes.contains(x, y)) {
            if (!waiting.getAsBoolean()) recipes.mouseClicked(x, y, button);
            return true;
        }
        for (RowHit hit : List.copyOf(rowHits)) {
            if (y < hit.top() || y >= hit.bottom()) continue;
            Row row = hit.row();
            if (tab == Tab.MACHINES && button == 0 && !waiting.getAsBoolean()) {
                Integer index = machineIndices.get(row);
                if (index != null) machines.selectCandidate(index);
            } else if (hit.variant() != null) {
                if (button == 0 && !waiting.getAsBoolean()) onMaterial.accept(node, hit.variant());
            } else if ((button == 1 || button == 2 || button == 0 && x >= bounds.x() + bounds.width() - 29)
                    && row.shortage() > 0 && !row.stack().isEmpty()) {
                onBookmark.accept(row.stack(), row.shortage());
            } else if (button == 0 && row.target() != null) onNavigate.accept(row.target());
            return true;
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        if (!contains(x, y)) return false;
        if (tab == Tab.NODE && !variants && !recipePage
                && (delta < 0 && nodeHeaderScroll < nodeHeaderMaxScroll
                || delta > 0 && nodeHeaderScroll > 0
                && (recipeVisible ? recipes.scrolledToTop() : listScroll == 0))) {
            nodeHeaderScroll = Math.max(0, Math.min(nodeHeaderMaxScroll, nodeHeaderScroll - (int) (delta * 24)));
            return true;
        }
        if (recipeVisible && recipes.contains(x, y)) return recipes.mouseScrolled(x, y, delta);
        listScroll = Math.max(0, Math.min(listMaxScroll, listScroll - (int) (delta * PlanUiConfig.rowHeight() * 2)));
        return true;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isOpen()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (variants || recipePage) { variants = recipePage = false; resetSearch(); }
            else close();
            return true;
        }
        if (!keyboardFocus) return false;
        if (search.visible && search.isFocused()) {
            search.keyPressed(key, scan, modifiers);
            return true;
        }
        if (recipeVisible && !waiting.getAsBoolean()) {
            // 嵌入区的 Esc 由外层处理，避免只关闭候选而留下空白步骤详情。
            recipes.keyPressed(key, scan, modifiers);
        }
        return true;
    }

    public boolean charTyped(char character, int modifiers) {
        if (!isOpen() || !keyboardFocus) return false;
        if (search.visible && search.isFocused()) return search.charTyped(character, modifiers);
        return recipeVisible && recipes.charTyped(character, modifiers);
    }

    private void resetSearch() {
        listScroll = 0;
        nodeHeaderScroll = 0;
        search.setValue("");
        search.setFocused(false);
        recipes.searchBox().setFocused(false);
        actions.clear();
        rowHits.clear();
    }

    private void drawWrapped(GuiGraphics graphics, String value, int x, int y, int width, Color color) {
        for (String line : wrap(value, width)) {
            graphics.drawString(font, line, x, y, color.value(), false);
            y += font.lineHeight + 2;
        }
    }

    private List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\\R", -1)) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            String remaining = paragraph;
            while (!remaining.isEmpty()) {
                String line = font.plainSubstrByWidth(remaining, Math.max(1, width));
                // GUI 缩放过大时宽度可能容不下一个字，也必须推进，避免渲染线程死循环。
                if (line.isEmpty()) line = remaining.substring(0, remaining.offsetByCodePoints(0, 1));
                lines.add(line);
                remaining = remaining.substring(line.length()).stripLeading();
            }
        }
        return lines;
    }

    private String fit(String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("rsi.plan.details." + key, args);
    }
}
