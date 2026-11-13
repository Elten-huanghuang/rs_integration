package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Bounds;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Candidate;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 固定在右侧的候选配方面板；只构建可见行的 JEI 布局。 */
@OnlyIn(Dist.CLIENT)
public final class RecipeCandidatePanel {
    private enum Tab { NODE, DIAGNOSTICS }

    private static final int CARD_COLLAPSED_HEIGHT = 30;
    private static final int CARD_HEADER_HEIGHT = 30;
    private static final int CARD_BOTTOM_PADDING = 5;
    private static final int CARD_GAP = 3;
    /**
     * 候选卡片只保留约 3×3 原版配方的预览预算。更大的 JEI 布局在固定区域内等比缩放，
     * 避免 9×9 工作台或大型机器配方把整张卡片撑高。
     */
    static final int MAX_PREVIEW_WIDTH = 116;
    static final int MAX_PREVIEW_HEIGHT = 112;

    private final Font font;
    private final RecipePreviewRenderer preview;
    private final BiConsumer<PlanTreeNode, ResourceLocation> onSelect;
    private final BiConsumer<PlanTreeNode, ResourceLocation> onCancelStockOnly;
    private final Consumer<PlanTreeNode> onForgetPreference;
    private final Consumer<PlanTreeNode> onExclusionChanged;
    private final Path preferencesPath;
    private final Runnable onBookmarkAll;
    private final BooleanSupplier canBookmarkAll;
    private final Map<ResourceLocation, RecipePreviewRenderer.PreviewBounds> previewSizeCache =
            new LinkedHashMap<>();
    private final EditBox search;
    private PlanTreeNode node;
    private RecipeCandidatePickerModel model;
    private Bounds bounds;
    private boolean embedded;
    private Set<String> boundMachines = Set.of();
    private int rowsTop;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;
    private boolean keyboardNavigation;
    private String preferenceKey;
    private String defaultRecipeId;
    private boolean noRecipeSelected;
    private Set<String> excludedRecipes = Set.of();
    private int noRecipeCardX, noRecipeCardY, noRecipeCardW, noRecipeCardH;
    private int forgetX, forgetY, forgetW, forgetH;
    private int bookmarkAllX, bookmarkAllY, bookmarkAllW, bookmarkAllH;
    private int nodeTabX, nodeTabY, nodeTabW, nodeTabH, diagnosticsTabX;
    private Tab activeTab = Tab.NODE;
    private int cardScroll;
    private int cardMaxScroll;
    private int diagnosticsScroll;
    private int diagnosticsMaxScroll;
    private int preferredWidth;
    private int cardViewportTop;
    private int cardViewportBottom;
    private List<MissingMaterialBookmarkList.TextEntry> planMissing = List.of();
    private List<Component> planWarnings = List.of();
    private final List<CardHit> cardHits = new ArrayList<>();
    private ItemStack hoveredCardItem = ItemStack.EMPTY;
    private Candidate hoveredCardCandidate;

    private record CardHit(int index, int y, int height) {}

    public RecipeCandidatePanel(Font font, RecipePreviewRenderer preview,
                                BiConsumer<PlanTreeNode, ResourceLocation> onSelect,
                                BiConsumer<PlanTreeNode, ResourceLocation> onCancelStockOnly,
                                Consumer<PlanTreeNode> onForgetPreference,
                                Consumer<PlanTreeNode> onExclusionChanged,
                                Path preferencesPath, Runnable onBookmarkAll,
                                BooleanSupplier canBookmarkAll) {
        this.font = font;
        this.preview = preview;
        this.onSelect = onSelect;
        this.onCancelStockOnly = onCancelStockOnly;
        this.onForgetPreference = onForgetPreference;
        this.onExclusionChanged = onExclusionChanged;
        this.preferencesPath = preferencesPath;
        this.onBookmarkAll = onBookmarkAll;
        this.canBookmarkAll = canBookmarkAll;
        search = new EditBox(font, 0, 0, 100, 16, Component.translatable("rsi.plan.recipe_picker.search"));
        search.setHint(Component.translatable("rsi.plan.recipe_picker.search"));
        search.setMaxLength(100);
        search.setBordered(false);
        search.setResponder(query -> {
            if (model != null) model.search(query);
            cardScroll = 0;
            keyboardNavigation = true;
        });
        close();
    }

    public EditBox searchBox() { return search; }
    public boolean isOpen() { return node != null; }
    public boolean contains(double x, double y) { return isOpen() && bounds != null && bounds.contains(x, y); }

    /** 诊断页展示整份计划的缺料和前置条件，而不是当前节点的元信息。 */
    public void setPlanDiagnostics(List<MissingMaterialBookmarkList.TextEntry> missing,
                                   List<Component> warnings) {
        planMissing = missing == null ? List.of() : List.copyOf(missing);
        planWarnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnosticsScroll = 0;
        diagnosticsMaxScroll = 0;
    }

    /** 底部缺料摘要点击后直接进入诊断页。 */
    public void showDiagnostics() {
        if (!isOpen()) return;
        activeTab = Tab.DIAGNOSTICS;
        search.setFocused(false);
    }

    public void open(PlanTreeNode node, Set<String> machines) {
        open(node, machines, true);
    }

    /** 步骤详情只绑定选择状态；打开配方子页时才读取 JEI 预览和尺寸。 */
    public void bind(PlanTreeNode node, Set<String> machines) {
        open(node, machines, false);
        embedded = true;
    }

    private void open(PlanTreeNode node, Set<String> machines, boolean loadPreviews) {
        if (node == null) return;
        this.node = node;
        this.boundMachines = Set.copyOf(machines);
        ResourceLocation preference = CraftingResolver.preferenceKey(node.key.stack(1));
        this.preferenceKey = preference == null ? null : preference.toString();
        CraftingPlanPreferences.Snapshot preferences =
                CraftingPlanPreferences.load(preferencesPath);
        RecipeChoice defaultChoice = preferenceKey == null ? null
                : preferences.recipeChoices().get(preferenceKey);
        this.defaultRecipeId = defaultChoice == null ? null : defaultChoice.serialize();
        this.noRecipeSelected = defaultChoice == RecipeChoice.NoRecipe.INSTANCE;
        this.excludedRecipes = new LinkedHashSet<>(preferences.excludedRecipes());
        Map<ResourceLocation, String> options = RecipeCandidateRules.optionsFor(node);
        List<Candidate> candidates = new ArrayList<>();
        int widestPreview = 0;
        for (Map.Entry<ResourceLocation, String> option : options.entrySet()) {
            ResourceLocation id = option.getKey();
            String mod = option.getValue();
            if (!loadPreviews) {
                candidates.add(new Candidate(id, mod, id.toString(), "", id.toString(), 1));
                continue;
            }
            var details = preview.candidateDetails(id, node.displayStack);
            Component title = preview.categoryTitle(id).orElse(null);
            String boundMachine = PlanRenderEngine.findBoundMachineLabel(mod, id);
            String machine = boundMachine != null
                    ? boundMachine
                    : title != null ? title.getString() : PlanRenderEngine.formatModTypeLabel(mod);
            candidates.add(new Candidate(id, mod, details.name(), machine,
                    details.searchText(), details.outputCount()));
            widestPreview = Math.max(widestPreview,
                    Math.min(MAX_PREVIEW_WIDTH, preview.previewWidthHint(id)));
        }
        if (!RecipeCandidateRules.canChooseStockOnly(node.depth, candidates.size())) {
            noRecipeSelected = false;
            if (RecipeChoice.NoRecipe.INSTANCE.serialize().equals(defaultRecipeId)) {
                defaultRecipeId = null;
            }
        }
        if (loadPreviews && node.step != null) {
            widestPreview = Math.max(widestPreview,
                    constrainPreviewBounds(
                            preview.previewBounds(node.step.recipeId())).width());
        }
        preferredWidth = widestPreview <= 0 ? 0 : widestPreview + 34;
        model = new RecipeCandidatePickerModel(candidates,
                node.step == null ? null : node.step.recipeId());
        search.setValue("");
        search.visible = search.active = true;
        search.setFocused(false);
        keyboardNavigation = false;
        cardScroll = 0;
        cardMaxScroll = 0;
        diagnosticsScroll = 0;
        diagnosticsMaxScroll = 0;
        planMissing = List.of();
        planWarnings = List.of();
        cardHits.clear();
        previewSizeCache.clear();
        activeTab = Tab.NODE;
        noRecipeCardW = 0;
        forgetW = 0;
        bookmarkAllW = 0;
    }

    public void close() {
        node = null;
        model = null;
        bounds = null;
        search.visible = search.active = false;
        search.setFocused(false);
        preferenceKey = null;
        defaultRecipeId = null;
        noRecipeSelected = false;
        excludedRecipes = Set.of();
        cardScroll = 0;
        cardMaxScroll = 0;
        diagnosticsScroll = 0;
        diagnosticsMaxScroll = 0;
        preferredWidth = 0;
        planMissing = List.of();
        planWarnings = List.of();
        cardHits.clear();
        previewSizeCache.clear();
        activeTab = Tab.NODE;
        noRecipeCardW = 0;
        forgetW = 0;
        bookmarkAllW = 0;
    }

    public void tick() { if (isOpen()) search.tick(); }

    /** 在主视图绘制前确定侧栏位置，确保布局与输入捕获同步。 */
    public void layout(int screenWidth, int screenHeight, int contentTop, int contentBottom) {
        if (!isOpen()) return;
        embedded = false;
        bounds = RecipeCandidatePickerModel.layout(
                screenWidth, screenHeight, contentTop, contentBottom, preferredWidth);
        layoutContents();
    }

    /** 作为步骤详情内的制作配方区，沿用候选卡片和键盘逻辑，不再绘制第二套页签。 */
    public void layoutEmbedded(int x, int y, int width, int height) {
        if (!isOpen()) return;
        embedded = true;
        bounds = new Bounds(x, y, Math.max(1, width), Math.max(1, height), 30, 1);
        layoutContents();
        noRecipeCardH = 0;
        rowsTop = y + 24;
        cardViewportTop = rowsTop;
        search.setY(y + 7);
    }

    private void layoutContents() {
        int x = bounds.x(), w = bounds.width(), y = bounds.y();
        noRecipeCardX = bounds.x() + 5;
        noRecipeCardY = y + RecipeCandidatePickerModel.HEADER_HEIGHT;
        noRecipeCardW = bounds.width() - 14;
        noRecipeCardH = 24;
        rowsTop = noRecipeCardY + noRecipeCardH + 5;
        cardViewportTop = rowsTop;
        cardViewportBottom = y + bounds.height() - RecipeCandidatePickerModel.FOOTER_HEIGHT;
        search.setPosition(x + 10, y + 26);
        search.setWidth(Math.max(1, w - 20));
        forgetW = font.width(Component.translatable(
                "rsi.plan.recipe_picker.forget").getString()) + 12;
        forgetH = 14;
        forgetX = x + 8;
        forgetY = y + bounds.height() - RecipeCandidatePickerModel.FOOTER_HEIGHT + 2;
        nodeTabY = y + 2;
        nodeTabH = 17;
        nodeTabX = x + 6;
        int tabsRight = x + w - 24;
        int tabWidth = Math.max(28, Math.min(64, (tabsRight - nodeTabX - 2) / 2));
        nodeTabW = tabWidth;
        diagnosticsTabX = nodeTabX + tabWidth + 2;
    }

    public int contentRight(int fallback) { return isOpen() && bounds != null ? bounds.contentRight() : fallback; }

    List<Candidate> visibleCandidates() {
        return model == null ? List.of() : model.filtered();
    }

    boolean stockOnlySelected() {
        return noRecipeSelected;
    }

    boolean scrolledToTop() { return cardScroll == 0; }

    /** 嵌入侧栏中的改动只形成请求草稿，由屏幕收到成功预览后持久化。 */
    CraftingPlanPreferences.Snapshot draftPreferences() {
        CraftingPlanPreferences.Snapshot stored = CraftingPlanPreferences.load(preferencesPath);
        Map<String, RecipeChoice> choices = new LinkedHashMap<>(stored.recipeChoices());
        if (preferenceKey != null) {
            RecipeChoice choice = RecipeChoice.parse(defaultRecipeId);
            if (choice == null) choices.remove(preferenceKey);
            else choices.put(preferenceKey, choice);
        }
        return new CraftingPlanPreferences.Snapshot(stored.outputDestination(), choices,
                stored.ingredientChoices(), excludedRecipes);
    }

    void resumeCrafting() {
        if (noRecipeSelected) selectNoRecipe();
    }

    /** 步骤卡片下只画当前配方，不加载其他候选，也不提供选择操作。 */
    public ItemStack renderCurrentRecipe(GuiGraphics graphics, PlanTreeNode owner,
                                         int x, int y, int width, int height, int mouseX, int mouseY) {
        if (owner.step == null) return ItemStack.EMPTY;
        return preview.renderRecipeInArea(graphics, font, owner.step.recipeId(),
                x, y, width, height, mouseX, mouseY, owner.displayStack);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isOpen()) return;
        if (bounds == null) return;
        if (mouseX != lastMouseX || mouseY != lastMouseY) keyboardNavigation = false;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        hoveredCardItem = ItemStack.EMPTY;
        hoveredCardCandidate = null;
        // 先结束主视图批次，侧栏的背景、内容和提示按顺序绘制。
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        try {
            if (embedded) {
                graphics.fill(x + 6, y + 3, x + w - 6, y + 20,
                        Color.SURFACE.value());
                search.visible = search.active = true;
                search.setTextColor(Color.TEXT.value());
                search.render(graphics, mouseX, mouseY, 0);
                renderCandidateCards(graphics, mouseX, mouseY);
                renderForgetButton(graphics, mouseX, mouseY);
                graphics.flush();
                return;
            }
            // 使用同一 GUI 批次绘制实心背景和两层边框，避免即时圆角被深度/剔除状态吃掉。
            graphics.fill(x + 3, y + 3, x + w + 3, y + h + 3, Color.SHADOW.value());
            graphics.fill(x, y, x + w, y + h, Color.ACCENT.value());
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, Color.BORDER.value());
            graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, Color.PANEL.value());
            graphics.fill(x + 2, y + 2, x + w - 2, y + 20, Color.SURFACE.value());
            graphics.flush();
            String nodeTabLabel = Component.translatable(
                    "rsi.plan.recipe_picker.tab.node").getString();
            String diagnosticsTabLabel = Component.translatable(
                    "rsi.plan.recipe_picker.tab.diagnostics").getString();
            renderTab(graphics, nodeTabLabel, nodeTabX, nodeTabY, nodeTabW, nodeTabH,
                    activeTab == Tab.NODE, mouseX, mouseY);
            renderTab(graphics, diagnosticsTabLabel, diagnosticsTabX, nodeTabY, nodeTabW,
                    nodeTabH, activeTab == Tab.DIAGNOSTICS, mouseX, mouseY);
            graphics.drawString(font, "×", x + w - 17, y + 6, Color.MUTED.value(), false);
            search.visible = activeTab == Tab.NODE;
            if (activeTab == Tab.NODE) {
                graphics.fill(x + 6, y + 23, x + w - 6, y + 39, Color.BORDER.value());
                graphics.fill(x + 7, y + 24, x + w - 7, y + 38, Color.SURFACE.value());
                search.render(graphics, mouseX, mouseY, 0);
            } else {
                graphics.fill(x + 6, y + 23, x + w - 6, y + 39, Color.BORDER.value());
                graphics.fill(x + 7, y + 24, x + w - 7, y + 38, Color.SURFACE.value());
                graphics.drawString(font, ellipsize(diagnosticsTabLabel, w - 20),
                        x + 10, y + 27, Color.MUTED.value(), false);
            }
            graphics.flush();
            if (activeTab == Tab.NODE) {
                renderNoRecipeRow(graphics, mouseX, mouseY);
                renderCandidateCards(graphics, mouseX, mouseY);
            } else {
                renderDiagnosticsTab(graphics);
            }
            if (RecipeCandidateRules.showsForget(activeTab == Tab.DIAGNOSTICS)) {
                renderForgetButton(graphics, mouseX, mouseY);
            } else if (RecipeCandidateRules.showsBookmarkAll(
                    activeTab == Tab.DIAGNOSTICS)) {
                renderBookmarkAllButton(graphics, mouseX, mouseY);
            }
            if (activeTab == Tab.NODE) {
                String hint = Component.translatable("rsi.plan.recipe_picker.hint").getString();
                int hintX = forgetX + forgetW + 6;
                graphics.drawString(font, ellipsize(hint, x + w - 8 - hintX),
                        hintX, y + h - 11, Color.MUTED.value(), false);
            }
            graphics.flush();
            renderHoveredTooltip(graphics, mouseX, mouseY);
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!hoveredCardItem.isEmpty() && contains(mouseX, mouseY)) {
            if (InkFluidSupport.isToken(hoveredCardItem)) {
                graphics.renderComponentTooltip(font, List.of(hoveredCardItem.getHoverName(),
                        Component.literal(hoveredCardItem.getCount() + " mB")), mouseX, mouseY);
            } else graphics.renderTooltip(font, hoveredCardItem, mouseX, mouseY);
        } else if (hoveredCardCandidate != null) {
            graphics.renderComponentTooltip(font, List.of(Component.literal(hoveredCardCandidate.name()),
                    Component.literal(hoveredCardCandidate.machine()), Component.literal(hoveredCardCandidate.recipeId().toString())),
                    mouseX, mouseY);
        }
    }

    /** 嵌入模式的提示在外层解除裁剪后绘制，避免 JEI 槽位提示被侧栏边缘截断。 */
    public void renderEmbeddedTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!embedded || !isOpen()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        try { renderHoveredTooltip(graphics, mouseX, mouseY); }
        finally { graphics.flush(); graphics.pose().popPose(); }
    }

    private void renderForgetButton(GuiGraphics graphics, int mouseX, int mouseY) {
        String label = Component.translatable(
                "rsi.plan.recipe_picker.forget").getString();
        boolean canForget = defaultRecipeId != null || noRecipeSelected;
        boolean hover = canForget
                && mouseX >= forgetX && mouseX < forgetX + forgetW
                && mouseY >= forgetY && mouseY < forgetY + forgetH;
        int border = !canForget ? Color.BORDER.value()
                : hover ? Color.ACCENT.value() : Color.BORDER.value();
        PlanUiDraw.rounded(graphics, forgetX, forgetY, forgetW, forgetH, 3f, border);
        PlanUiDraw.rounded(graphics, forgetX + 1, forgetY + 1, forgetW - 2, forgetH - 2,
                2f, canForget ? Color.SELECTED.value() : Color.SURFACE.value());
        graphics.drawString(font, ellipsize(label, forgetW - 10), forgetX + 5, forgetY + 3,
                canForget ? (hover ? Color.TEXT.value() : Color.MUTED.value()) : Color.DISABLED.value(), false);
    }

    private void renderBookmarkAllButton(GuiGraphics graphics, int mouseX, int mouseY) {
        bookmarkAllW = 0;
        if (!canBookmarkAll.getAsBoolean()) return;
        String label = Component.translatable(
                "rsi.plan.bookmark_all.action").getString();
        bookmarkAllW = 12 + font.width(label);
        bookmarkAllH = 14;
        bookmarkAllX = bounds.x() + 8;
        bookmarkAllY = forgetY;
        boolean hover = mouseX >= bookmarkAllX && mouseX < bookmarkAllX + bookmarkAllW
                && mouseY >= bookmarkAllY && mouseY < bookmarkAllY + bookmarkAllH;
        PlanUiDraw.rounded(graphics, bookmarkAllX, bookmarkAllY,
                bookmarkAllW, bookmarkAllH, bookmarkAllH / 2f,
                hover ? Color.HOVER.value() : Color.SELECTED.value());
        graphics.drawString(font, label,
                bookmarkAllX + (bookmarkAllW - font.width(label)) / 2,
                bookmarkAllY + (bookmarkAllH - font.lineHeight) / 2,
                hover ? Color.ACCENT.value() : Color.READY.value(), false);
    }

    private void renderTab(GuiGraphics graphics, String label, int x, int y, int width,
                           int height, boolean active, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
        int border = active ? Color.ACCENT.value() : hover ? Color.HOVER.value() : Color.BORDER.value();
        PlanUiDraw.rounded(graphics, x, y, width, height, 3f, border);
        PlanUiDraw.rounded(graphics, x + 1, y + 1, width - 2, height - 2, 2f,
                active ? Color.SELECTED.value() : hover ? Color.HOVER.value() : Color.SURFACE.value());
        String text = ellipsize(label, width - 8);
        graphics.drawString(font, text, x + (width - font.width(text)) / 2,
                y + (height - font.lineHeight) / 2,
                active ? Color.TEXT.value() : Color.MUTED.value(), false);
        if (active) {
            graphics.fill(x + 4, y + height - 2, x + width - 4, y + height,
                    Color.ACCENT.value());
        }
    }

    private void renderNoRecipeRow(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!RecipeCandidateRules.canChooseStockOnly(
                node.depth, model.total())) return;
        boolean hover = mouseX >= noRecipeCardX && mouseX < noRecipeCardX + noRecipeCardW
                && mouseY >= noRecipeCardY && mouseY < noRecipeCardY + noRecipeCardH;
        int border = noRecipeSelected ? Color.ERROR.value()
                : hover ? Color.ERROR.value() : Color.ERROR_SURFACE.value();
        PlanUiDraw.rounded(graphics, noRecipeCardX, noRecipeCardY,
                noRecipeCardW, noRecipeCardH, 3f, border);
        PlanUiDraw.rounded(graphics, noRecipeCardX + 1, noRecipeCardY + 1,
                noRecipeCardW - 2, noRecipeCardH - 2, 2f,
                noRecipeSelected ? Color.ERROR_SURFACE.value() : Color.ERROR_SURFACE.value());
        graphics.fill(noRecipeCardX + 6, noRecipeCardY + 7,
                noRecipeCardX + 16, noRecipeCardY + 17,
                noRecipeSelected ? Color.ERROR.value() : Color.ERROR_SURFACE.value());
        graphics.drawString(font, "×", noRecipeCardX + 8, noRecipeCardY + 8,
                Color.ERROR_SURFACE.value(), false);
        String current = Component.translatable(
                "rsi.plan.recipe_picker.current").getString();
        int currentW = noRecipeSelected ? font.width(current) : 0;
        int labelW = noRecipeCardW - 22 - (noRecipeSelected ? currentW + 14 : 7);
        graphics.drawString(font, ellipsize(Component.translatable(
                        "rsi.plan.recipe_picker.stock_only").getString(), labelW),
                noRecipeCardX + 22, noRecipeCardY + 8,
                noRecipeSelected ? Color.ERROR.value() : Color.ERROR.value(), false);
        if (noRecipeSelected) {
            graphics.drawString(font, current,
                    noRecipeCardX + noRecipeCardW - currentW - 7,
                    noRecipeCardY + 8, Color.ERROR.value(), false);
        }
        graphics.fill(noRecipeCardX, rowsTop - 3,
                noRecipeCardX + noRecipeCardW, rowsTop - 2, Color.BORDER.value());
    }

    /** 诊断页展示整份计划的缺料清单与仪式/前置条件全文。 */
    private void renderDiagnosticsTab(GuiGraphics graphics) {
        int left = bounds.x() + 8;
        int width = bounds.width() - 16;
        int top = noRecipeCardY + 2;
        int bottom = bounds.y() + bounds.height()
                - RecipeCandidatePickerModel.FOOTER_HEIGHT - 4;
        List<DiagnosticLine> lines = buildDiagnosticLines(width);
        int lineHeight = font.lineHeight + 1;
        int contentHeight = lines.size() * lineHeight;
        diagnosticsMaxScroll = Math.max(0, contentHeight - (bottom - top));
        diagnosticsScroll = Math.max(0,
                Math.min(diagnosticsScroll, diagnosticsMaxScroll));
        graphics.enableScissor(bounds.x() + 4, top, bounds.x() + bounds.width() - 4, bottom);
        try {
            int y = top - diagnosticsScroll;
            for (DiagnosticLine line : lines) {
                if (y + font.lineHeight > top && y < bottom) {
                    graphics.drawString(font, ellipsize(line.text(), width - line.indent()),
                            left + line.indent(), y, line.color(), false);
                }
                y += lineHeight;
            }
        } finally {
            graphics.disableScissor();
        }
        if (diagnosticsMaxScroll > 0) {
            int trackX = bounds.x() + bounds.width() - 7;
            int trackHeight = Math.max(1, bottom - top);
            int thumbHeight = Math.max(10, (int) ((long) trackHeight * trackHeight
                    / (trackHeight + diagnosticsMaxScroll)));
            int thumbY = top + (int) ((long) (trackHeight - thumbHeight)
                    * diagnosticsScroll / diagnosticsMaxScroll);
            graphics.fill(trackX, top, trackX + 2, bottom, Color.SURFACE.value());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, Color.ACCENT.value());
        }
    }

    private record DiagnosticLine(String text, int color, int indent) {}

    private List<DiagnosticLine> buildDiagnosticLines(int width) {
        List<DiagnosticLine> lines = new ArrayList<>();
        lines.add(new DiagnosticLine(Component.translatable(
                "rsi.plan.missing_summary", planMissing.size()).getString(),
                Color.MUTED.value(), 0));
        if (planMissing.isEmpty()) {
            lines.add(new DiagnosticLine("-", Color.DISABLED.value(), 8));
        } else {
            for (MissingMaterialBookmarkList.TextEntry entry : planMissing) {
                String count = "×" + entry.missingCount();
                int labelWidth = width - 8 - font.width(count) - 6;
                lines.add(new DiagnosticLine(ellipsize(entry.label(), labelWidth)
                        + "  " + count, Color.WARNING.value(), 8));
            }
        }
        lines.add(new DiagnosticLine("", 0, 0));
        lines.add(new DiagnosticLine(Component.translatable(
                "rsi.plan.warning_summary", planWarnings.size()).getString(),
                Color.MUTED.value(), 0));
        if (planWarnings.isEmpty()) {
            lines.add(new DiagnosticLine("-", Color.DISABLED.value(), 8));
        } else {
            for (Component warning : planWarnings) {
                appendWrapped(lines, ChatFormatting.stripFormatting(warning.getString()),
                        width, Color.ERROR.value(), 8);
            }
        }
        return lines;
    }

    private void appendWrapped(List<DiagnosticLine> lines, String text, int width,
                               int color, int indent) {
        String prefix = "· ";
        int continuationIndent = font.width(prefix);
        List<String> wrapped = UIRenderer.wrapLines(font, text,
                Math.max(1, width - indent - continuationIndent));
        for (int i = 0; i < wrapped.size(); i++) {
            String rendered = i == 0 ? prefix + wrapped.get(i) : wrapped.get(i);
            lines.add(new DiagnosticLine(rendered,
                    color, indent + (i == 0 ? 0 : continuationIndent)));
        }
        lines.add(new DiagnosticLine("", 0, 0));
    }

    private String ellipsize(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String suffix = "…";
        int suffixWidth = font.width(suffix);
        if (maxWidth <= suffixWidth) return font.plainSubstrByWidth(suffix, maxWidth);
        return font.plainSubstrByWidth(text, maxWidth - suffixWidth) + suffix;
    }

    private static int indexOfRecipe(List<Candidate> candidates, @Nullable ResourceLocation id) {
        if (id == null) return -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).recipeId().equals(id)) return i;
        }
        return -1;
    }

    /** 每张候选卡片使用统一上限内的预览区域，超出部分由 JEI 绘制阶段等比缩放。 */
    int cardHeight(Candidate candidate) {
        RecipePreviewRenderer.PreviewBounds size = previewSizeCache.get(candidate.recipeId());
        if (size == null || size.height() <= 1) {
            size = constrainPreviewBounds(preview.previewBounds(candidate.recipeId()));
            if (size.height() > 1) previewSizeCache.put(candidate.recipeId(), size);
        }
        return CARD_HEADER_HEIGHT + size.height() + CARD_BOTTOM_PADDING;
    }

    static RecipePreviewRenderer.PreviewBounds constrainPreviewBounds(
            RecipePreviewRenderer.PreviewBounds bounds) {
        if (bounds == null) return new RecipePreviewRenderer.PreviewBounds(1, 1);
        return new RecipePreviewRenderer.PreviewBounds(
                Math.max(1, Math.min(MAX_PREVIEW_WIDTH, bounds.width())),
                Math.max(1, Math.min(MAX_PREVIEW_HEIGHT, bounds.height())));
    }

    private void renderCandidateCards(GuiGraphics graphics, int mouseX, int mouseY) {
        cardHits.clear();
        int x = bounds.x();
        int w = bounds.width();
        int viewportHeight = cardViewportBottom - cardViewportTop;
        if (viewportHeight <= 0) {
            cardMaxScroll = 0;
            return;
        }
        List<Candidate> filtered = model.filtered();
        if (filtered.isEmpty()) {
            graphics.drawString(font, Component.translatable("rsi.plan.recipe_picker.empty"),
                    x + 10, cardViewportTop + 8, Color.MUTED.value(), false);
            cardMaxScroll = 0;
            cardScroll = 0;
            return;
        }

        int selectedIndex = -1;
        if (!noRecipeSelected && node.step != null) {
            // “current” 永远表示服务端这一次实际采用的配方。持久偏好只作为
            // default 徽标，不能在失败预览后被误画成已经生效。
            selectedIndex = indexOfRecipe(filtered, node.step.recipeId());
            if (selectedIndex < 0) selectedIndex = 0;
        }
        int contentHeight = 0;
        for (int i = 0; i < filtered.size(); i++) {
            contentHeight += cardHeight(filtered.get(i));
            if (i + 1 < filtered.size()) contentHeight += CARD_GAP;
        }
        cardMaxScroll = Math.max(0, contentHeight - viewportHeight);
        cardScroll = Math.max(0, Math.min(cardScroll, cardMaxScroll));

        int cardX = x + 5;
        int cardW = w - 14;
        graphics.enableScissor(x + 4, cardViewportTop, x + w - 4, cardViewportBottom);
        try {
            int cardY = cardViewportTop - cardScroll;
            for (int index = 0; index < filtered.size(); index++) {
                Candidate candidate = filtered.get(index);
                boolean selected = index == selectedIndex;
                int cardH = cardHeight(candidate);
                boolean onScreen = cardY + cardH > cardViewportTop
                        && cardY < cardViewportBottom;
                if (!onScreen) {
                    cardHits.add(new CardHit(index, cardY, cardH));
                    cardY += cardH + CARD_GAP;
                    continue;
                }
                boolean hover = mouseX >= cardX && mouseX < cardX + cardW
                        && mouseY >= cardY && mouseY < cardY + cardH
                        && mouseY >= cardViewportTop && mouseY < cardViewportBottom;
                if (hover) {
                    if (!keyboardNavigation) model.hover(index);
                    hoveredCardCandidate = candidate;
                }

                boolean isDefault = !noRecipeSelected
                        && candidate.recipeId().toString().equals(defaultRecipeId);
                boolean excluded = RecipeCandidateRules.canExclude(
                        node.depth, filtered.size())
                        && excludedRecipes.contains(candidate.recipeId().toString());
                int cardBorder = excluded ? Color.ERROR.value()
                        : isDefault ? Color.WARNING.value()
                        : hover ? Color.HOVER.value() : Color.BORDER.value();
                int cardFill = selected ? Color.SELECTED.value()
                        : excluded ? Color.ERROR_SURFACE.value()
                        : hover ? Color.HOVER.value() : Color.PANEL.value();
                PlanUiDraw.rounded(graphics, cardX, cardY, cardW, cardH, 5f,
                        selected && !excluded ? Color.ACCENT.value() : cardBorder);
                PlanUiDraw.rounded(graphics, cardX + 1, cardY + 1,
                        cardW - 2, cardH - 2, 4f, cardFill);
                graphics.flush();
                preview.drawCategoryIcon(graphics, candidate.recipeId(),
                        cardX + 7, cardY + 6, 14);

                ModType modType = ModType.findById(candidate.modTypeId());
                boolean bound = candidate.modTypeId().isEmpty() || "generic".equals(candidate.modTypeId())
                        || modType != null && modType.isVirtual()
                        || boundMachines.contains(candidate.modTypeId());
                String machine = "x" + candidate.outputCount() + " · "
                        + candidate.machine() + (bound ? "" : " · "
                        + Component.translatable("rsi.plan.recipe_picker.unbound").getString());
                List<String> badges = new ArrayList<>(3);
                if (selected) {
                    badges.add(Component.translatable("rsi.plan.recipe_picker.current").getString());
                }
                if (isDefault) {
                    badges.add(Component.translatable("rsi.plan.recipe_picker.default").getString());
                }
                if (excluded) {
                    badges.add(Component.translatable("rsi.plan.recipe_picker.excluded").getString());
                }
                String badge = ellipsize(String.join(" · ", badges),
                        Math.max(1, cardW / 2));
                int badgeW = badge.isEmpty() ? 0 : font.width(badge);
                int machineWidth = Math.max(1, cardW - 33 - badgeW);
                graphics.drawString(font, ellipsize(machine, machineWidth),
                        cardX + 25, cardY + 5,
                        bound ? Color.MUTED.value() : Color.WARNING.value(), false);
                graphics.drawString(font, ellipsize(candidate.name(), cardW - 16),
                        cardX + 8, cardY + 18, Color.TEXT.value(), false);
                if (!badge.isEmpty()) {
                    graphics.drawString(font, badge,
                            cardX + cardW - badgeW - 7, cardY + 5,
                            excluded ? Color.ERROR.value() : Color.ACCENT.value(), false);
                }

                int previewX = cardX + 8;
                int previewY = cardY + CARD_HEADER_HEIGHT;
                int previewW = Math.max(1, cardW - 16);
                int previewH = Math.max(1, cardH - CARD_HEADER_HEIGHT - CARD_BOTTOM_PADDING);
                graphics.enableScissor(previewX, previewY,
                        previewX + previewW, previewY + previewH);
                try {
                    ItemStack hovered = preview.renderRecipeInArea(graphics, font,
                            candidate.recipeId(), previewX, previewY, previewW, previewH,
                            mouseX, mouseY, node.displayStack);
                    if (hover && !hovered.isEmpty()) hoveredCardItem = hovered;
                } finally {
                    graphics.disableScissor();
                }

                cardHits.add(new CardHit(index, cardY, cardH));
                cardY += cardH + CARD_GAP;
            }
        } finally {
            graphics.flush();
            graphics.disableScissor();
        }
        if (cardMaxScroll > 0) {
            int trackX = x + w - 7;
            int trackH = viewportHeight;
            int thumbH = Math.max(10, (int) ((long) trackH * trackH
                    / (trackH + cardMaxScroll)));
            int thumbY = cardViewportTop
                    + (int) ((long) (trackH - thumbH) * cardScroll / cardMaxScroll);
            graphics.fill(trackX, cardViewportTop, trackX + 2,
                    cardViewportBottom, Color.SURFACE.value());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, Color.ACCENT.value());
        }
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!contains(x, y)) return false;
        if (button == 1) {
            if (activeTab != Tab.NODE) return true;
            if (!RecipeCandidateRules.canExclude(node.depth, model.filtered().size())) {
                return true;
            }
            CardHit hit = cardHitAt(y);
            if (hit != null) {
                String recipeId = model.filtered().get(hit.index()).recipeId().toString();
                boolean excluded = !excludedRecipes.contains(recipeId);
                excludedRecipes = new LinkedHashSet<>(excludedRecipes);
                if (excluded) excludedRecipes.add(recipeId);
                else excludedRecipes.remove(recipeId);
                if (!embedded) CraftingPlanPreferences.setExcluded(preferencesPath, recipeId, excluded);
                onExclusionChanged.accept(node);
            }
            return true;
        }
        if (button != 0) return true;
        if (!embedded && y >= nodeTabY && y < nodeTabY + nodeTabH) {
            if (x >= nodeTabX && x < nodeTabX + nodeTabW) {
                activeTab = Tab.NODE;
                return true;
            }
            if (x >= diagnosticsTabX && x < diagnosticsTabX + nodeTabW) {
                activeTab = Tab.DIAGNOSTICS;
                search.setFocused(false);
                return true;
            }
        }
        if (!embedded && x >= bounds.x() + bounds.width() - 16 && y < bounds.y() + 22) { close(); return true; }
        if (activeTab == Tab.NODE
                && (defaultRecipeId != null || noRecipeSelected)
                && forgetW > 0 && x >= forgetX && x < forgetX + forgetW
                && y >= forgetY && y < forgetY + forgetH) {
            forgetPreference();
            return true;
        }
        if (activeTab == Tab.DIAGNOSTICS && bookmarkAllW > 0
                && x >= bookmarkAllX && x < bookmarkAllX + bookmarkAllW
                && y >= bookmarkAllY && y < bookmarkAllY + bookmarkAllH) {
            onBookmarkAll.run();
            return true;
        }
        if (activeTab != Tab.NODE) return true;
        if (!embedded && x >= noRecipeCardX && x < noRecipeCardX + noRecipeCardW
                && y >= noRecipeCardY && y < noRecipeCardY + noRecipeCardH) {
            selectNoRecipe();
            return true;
        }
        if (y >= search.getY() - 2 && y < search.getY() + search.getHeight()) {
            search.setFocused(true);
            search.mouseClicked(x, y, button);
            return true;
        }
        search.setFocused(false);
        if (x >= bounds.x() + bounds.width() - 9 && y >= rowsTop
                && y < cardViewportBottom) {
            int span = Math.max(1, cardViewportBottom - cardViewportTop);
            int target = (int) ((y - cardViewportTop) * cardMaxScroll / span);
            cardScroll = Math.max(0, Math.min(cardMaxScroll, target));
            return true;
        }
        CardHit hit = cardHitAt(y);
        if (hit != null) {
            model.hover(hit.index());
            choose();
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        if (!contains(x, y)) return false;
        if (activeTab == Tab.DIAGNOSTICS) {
            int step = (font.lineHeight + 1) * 3;
            diagnosticsScroll = Math.max(0, Math.min(diagnosticsMaxScroll,
                    diagnosticsScroll - (delta > 0 ? step : -step)));
            return true;
        }
        keyboardNavigation = false;
        cardScroll = Math.max(0, Math.min(cardMaxScroll,
                cardScroll - (delta > 0 ? CARD_COLLAPSED_HEIGHT : -CARD_COLLAPSED_HEIGHT)));
        return true;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isOpen()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        // 诊断页没有输入控件，但仍要吞掉按键，避免 Enter 落到主界面的确认动作。
        if (activeTab != Tab.NODE) return true;
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            keyboardNavigation = true;
            model.move(key == GLFW.GLFW_KEY_UP ? -1 : 1);
            revealCursorCard();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            keyboardNavigation = true;
            model.move((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1)
                    * Math.max(1, (cardViewportBottom - cardViewportTop) / CARD_COLLAPSED_HEIGHT));
            revealCursorCard();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { choose(); return true; }
        return search.isFocused() && search.keyPressed(key, scan, modifiers);
    }

    public boolean charTyped(char character, int modifiers) {
        if (!isOpen()) return false;
        if (activeTab != Tab.NODE) return true;
        return search.isFocused() && search.charTyped(character, modifiers);
    }

    private CardHit cardHitAt(double y) {
        if (y < cardViewportTop || y >= cardViewportBottom) return null;
        for (CardHit hit : cardHits) {
            if (y >= hit.y() && y < hit.y() + hit.height()) return hit;
        }
        return null;
    }

    private void revealCursorCard() {
        int cursor = model.cursor();
        for (CardHit hit : cardHits) {
            if (hit.index() != cursor) continue;
            if (hit.y() < cardViewportTop) {
                cardScroll = Math.max(0, cardScroll - (cardViewportTop - hit.y()));
            } else if (hit.y() + hit.height() > cardViewportBottom) {
                cardScroll = Math.min(cardMaxScroll,
                        cardScroll + hit.y() + hit.height() - cardViewportBottom);
            }
            return;
        }
    }

    private void choose() {
        Candidate candidate = model.active();
        if (candidate == null) return;
        PlanTreeNode owner = node;
        excludedRecipes = new LinkedHashSet<>(excludedRecipes);
        excludedRecipes.remove(candidate.recipeId().toString());
        defaultRecipeId = candidate.recipeId().toString();
        noRecipeSelected = false;
        if (!embedded) close();
        // 即使卡片显示的配方等于旧计划，也必须提交一次显式选择：失败预览
        // 可能已把 SelectedPath 改成另一配方，静默跳过会留下错误 forced map。
        onSelect.accept(owner, candidate.recipeId());
    }

    void selectNoRecipe() {
        if (!RecipeCandidateRules.canChooseStockOnly(node.depth, model.total())) return;
        boolean cancel = noRecipeSelected;
        noRecipeSelected = !cancel;
        defaultRecipeId = cancel ? null : RecipeChoice.NoRecipe.INSTANCE.serialize();
        if (!embedded) CraftingPlanPreferences.saveRecipeChoice(preferencesPath, preferenceKey,
                cancel ? null : RecipeChoice.NoRecipe.INSTANCE);
        PlanTreeNode owner = node;
        ResourceLocation fallbackRecipe = owner.step == null
                ? null : owner.step.recipeId();
        if (!embedded) close();
        if (cancel && fallbackRecipe != null) {
            onCancelStockOnly.accept(owner, fallbackRecipe);
        } else {
            onSelect.accept(owner, null);
        }
    }

    private void forgetPreference() {
        PlanTreeNode owner = node;
        defaultRecipeId = null;
        noRecipeSelected = false;
        if (!embedded) CraftingPlanPreferences.saveRecipeChoice(
                preferencesPath, preferenceKey, (RecipeChoice) null);
        if (!embedded) close();
        onForgetPreference.accept(owner);
    }
}
