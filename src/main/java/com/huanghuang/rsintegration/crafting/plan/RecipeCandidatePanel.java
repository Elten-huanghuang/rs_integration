package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiConsumer;

/** 固定在右侧的候选配方面板；只构建可见行的 JEI 布局。 */
@OnlyIn(Dist.CLIENT)
public final class RecipeCandidatePanel {
    private final Font font;
    private final RecipePreviewRenderer preview;
    private final BiConsumer<PlanTreeNode, ResourceLocation> onSelect;
    private final Path preferencesPath;
    private final EditBox search;
    private PlanTreeNode node;
    private RecipeCandidatePickerModel model;
    private Bounds bounds;
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

    public RecipeCandidatePanel(Font font, RecipePreviewRenderer preview,
                                BiConsumer<PlanTreeNode, ResourceLocation> onSelect,
                                Path preferencesPath) {
        this.font = font;
        this.preview = preview;
        this.onSelect = onSelect;
        this.preferencesPath = preferencesPath;
        search = new EditBox(font, 0, 0, 100, 16, Component.translatable("rsi.plan.recipe_picker.search"));
        search.setHint(Component.translatable("rsi.plan.recipe_picker.search"));
        search.setMaxLength(100);
        search.setBordered(false);
        search.setResponder(query -> {
            if (model != null) model.search(query);
            keyboardNavigation = true;
        });
        close();
    }

    public EditBox searchBox() { return search; }
    public boolean isOpen() { return node != null; }
    public boolean contains(double x, double y) { return isOpen() && bounds != null && bounds.contains(x, y); }

    public void open(PlanTreeNode node, Set<String> machines) {
        if (node.step == null) return;
        this.node = node;
        this.boundMachines = Set.copyOf(machines);
        ResourceLocation preference = CraftingResolver.preferenceKey(node.key.stack(1));
        this.preferenceKey = preference == null ? null : preference.toString();
        CraftingPlanPreferences.Snapshot preferences =
                CraftingPlanPreferences.load(preferencesPath);
        this.defaultRecipeId = preferenceKey == null ? null
                : preferences.recipeChoices().get(preferenceKey);
        this.noRecipeSelected = CraftingPlanPreferences.NO_RECIPE.equals(defaultRecipeId);
        this.excludedRecipes = new LinkedHashSet<>(preferences.excludedRecipes());
        var options = new LinkedHashMap<ResourceLocation, String>();
        options.put(node.step.recipeId(), node.step.modType() == null ? "generic" : node.step.modType().id());
        for (int i = 0; i < node.step.alternatives().size(); i++) {
            String mod = i < node.step.alternativeModTypes().size() ? node.step.alternativeModTypes().get(i) : "generic";
            options.putIfAbsent(node.step.alternatives().get(i), mod);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<ResourceLocation, String> option : options.entrySet()) {
            ResourceLocation id = option.getKey();
            String mod = option.getValue();
            var details = preview.candidateDetails(id, node.displayStack);
            Component title = preview.categoryTitle(id).orElse(null);
            String machine = title != null
                    ? title.getString()
                    : PlanRenderEngine.formatModTypeLabel(mod);
            candidates.add(new Candidate(id, mod, details.name(), machine, details.searchText()));
        }
        model = new RecipeCandidatePickerModel(candidates, node.step.recipeId());
        search.setValue("");
        search.visible = search.active = true;
        search.setFocused(false);
        keyboardNavigation = false;
        noRecipeCardW = 0;
        forgetW = 0;
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
        noRecipeCardW = 0;
        forgetW = 0;
    }

    public void tick() { if (isOpen()) search.tick(); }

    /** 在主视图绘制前确定侧栏位置，确保布局与输入捕获同步。 */
    public void layout(int screenWidth, int screenHeight, int contentTop) {
        if (!isOpen()) return;
        bounds = RecipeCandidatePickerModel.layout(screenWidth, screenHeight, contentTop);
        noRecipeCardX = bounds.x() + 5;
        noRecipeCardY = bounds.y() + RecipeCandidatePickerModel.HEADER_HEIGHT;
        noRecipeCardW = bounds.width() - 14;
        noRecipeCardH = 30;
        rowsTop = noRecipeCardY + noRecipeCardH + 3;
        int availableRowsHeight = bounds.y() + bounds.height()
                - RecipeCandidatePickerModel.FOOTER_HEIGHT - rowsTop;
        model.setVisibleRows(Math.max(1, availableRowsHeight / bounds.rowHeight()));
        search.setPosition(bounds.x() + 10, bounds.y() + 26);
        search.setWidth(Math.max(1, bounds.width() - 20));
    }

    public int contentRight(int fallback) { return isOpen() && bounds != null ? bounds.contentRight() : fallback; }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isOpen()) return;
        if (bounds == null) return;
        if (mouseX != lastMouseX || mouseY != lastMouseY) keyboardNavigation = false;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        ItemStack hovered = ItemStack.EMPTY;
        Candidate hoveredCandidate = null;
        // 先结束主视图批次，侧栏的背景、内容和提示按顺序绘制。
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        try {
            // 使用同一 GUI 批次绘制实心背景和两层边框，避免即时圆角被深度/剔除状态吃掉。
            graphics.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x90000000);
            graphics.fill(x, y, x + w, y + h, 0xFF70A888);
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF243D30);
            graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF101A15);
            graphics.fill(x + 2, y + 2, x + w - 2, y + 20, 0xFF213A2B);
            graphics.flush();
            String title = Component.translatable("rsi.plan.recipe_picker.title",
                    model.filtered().size(), model.total()).getString();
            String forgetLabel = Component.translatable(
                    "rsi.plan.recipe_picker.forget").getString();
            boolean canForget = defaultRecipeId != null || noRecipeSelected;
            forgetW = canForget ? font.width(forgetLabel) + 6 : 0;
            forgetH = 14;
            forgetX = x + w - forgetW - 24;
            forgetY = y + 5;
            int titleRight = w - 24 - (forgetW > 0 ? forgetW + 4 : 0);
            graphics.drawString(font, font.plainSubstrByWidth(title, Math.max(1, titleRight)),
                    x + 8, y + 6, 0xFFE0F2E6, false);
            if (forgetW > 0) {
                boolean forgetHover = mouseX >= forgetX && mouseX < forgetX + forgetW
                        && mouseY >= forgetY && mouseY < forgetY + forgetH;
                graphics.drawString(font, forgetLabel, forgetX, forgetY,
                        forgetHover ? 0xFFFFFFFF : 0xFF9FC9B0, false);
            }
            graphics.drawString(font, "×", x + w - 17, y + 6, 0xFFAAC8B4, false);
            graphics.fill(x + 6, y + 23, x + w - 6, y + 39, 0xFF50745E);
            graphics.fill(x + 7, y + 24, x + w - 7, y + 38, 0xFF192C21);
            search.render(graphics, mouseX, mouseY, 0);
            graphics.flush();
            boolean noRecipeHover = mouseX >= noRecipeCardX && mouseX < noRecipeCardX + noRecipeCardW
                    && mouseY >= noRecipeCardY && mouseY < noRecipeCardY + noRecipeCardH;
            int noRecipeBorder = noRecipeSelected ? 0xFFFF6677
                    : noRecipeHover ? 0xFFB85B68 : 0xFF66313A;
            UIRenderer.rounded(graphics, noRecipeCardX, noRecipeCardY,
                    noRecipeCardW, noRecipeCardH, 5f, noRecipeBorder);
            UIRenderer.rounded(graphics, noRecipeCardX + 1, noRecipeCardY + 1,
                    noRecipeCardW - 2, noRecipeCardH - 2, 4f,
                    noRecipeSelected ? 0xFF3B1C22 : 0xFF211417);
            graphics.fill(noRecipeCardX + 7, noRecipeCardY + 9,
                    noRecipeCardX + 17, noRecipeCardY + 19, 0xFFFF6677);
            graphics.drawString(font, "×", noRecipeCardX + 9, noRecipeCardY + 10,
                    0xFF3B1C22, false);
            String stockOnly = Component.translatable(
                    "rsi.plan.recipe_picker.stock_only").getString();
            graphics.drawString(font, font.plainSubstrByWidth(stockOnly, noRecipeCardW - 58),
                    noRecipeCardX + 24, noRecipeCardY + 10,
                    noRecipeSelected ? 0xFFFFB8C0 : 0xFFE8C0C5, false);
            if (noRecipeSelected) {
                String current = Component.translatable(
                        "rsi.plan.recipe_picker.current").getString();
                graphics.drawString(font, current,
                        noRecipeCardX + noRecipeCardW - font.width(current) - 7,
                        noRecipeCardY + 10, 0xFFFFB8C0, false);
            }
            int visibleRows = model.visibleRows();
            int rowsBottom = rowsTop + visibleRows * bounds.rowHeight();
            graphics.enableScissor(x + 4, rowsTop, x + w - 4, rowsBottom);
            try {
                if (model.filtered().isEmpty()) {
                    graphics.drawString(font, Component.translatable("rsi.plan.recipe_picker.empty"),
                            x + 10, rowsTop + 8, 0xFF9EB4A7, false);
                }
                for (int row = 0; row < visibleRows; row++) {
                    int index = model.firstRow() + row;
                    if (index >= model.filtered().size()) break;
                    Candidate candidate = model.filtered().get(index);
                    int rowY = rowsTop + row * bounds.rowHeight();
                    boolean hover = mouseX >= x + 4 && mouseX < x + w - 8
                            && mouseY >= rowY && mouseY < rowY + bounds.rowHeight();
                    if (hover) {
                        if (!keyboardNavigation) model.hover(index);
                        hoveredCandidate = candidate;
                    }
                    boolean selected = !noRecipeSelected
                            && candidate.recipeId().equals(node.step.recipeId());
                    boolean isDefault = !noRecipeSelected
                            && candidate.recipeId().toString().equals(defaultRecipeId);
                    boolean excluded = excludedRecipes.contains(
                            candidate.recipeId().toString());
                    int cardX = x + 5;
                    int cardY = rowY + 2;
                    int cardW = w - 14;
                    int cardH = Math.max(1, bounds.rowHeight() - 4);
                    int cardBorder = excluded ? 0xFFB23A48
                            : selected ? 0xFF66D48A
                            : hover ? 0xFF5B8A6B : 0xFF385341;
                    int cardFill = selected ? 0xFF1C3B2A
                            : excluded ? 0xFF28171A
                            : hover ? 0xFF20382A : 0xFF141F19;
                    UIRenderer.rounded(graphics, cardX, cardY, cardW, cardH, 5f, cardBorder);
                    UIRenderer.rounded(graphics, cardX + 1, cardY + 1,
                            cardW - 2, cardH - 2, 4f, cardFill);
                    if (selected) {
                        graphics.fill(cardX + 1, cardY + 4,
                                cardX + 3, cardY + cardH - 4, 0xFF66D48A);
                    }
                    graphics.flush();
                    preview.drawCategoryIcon(graphics, candidate.recipeId(), cardX + 7, cardY + 5, 14);
                    ModType modType = ModType.findById(candidate.modTypeId());
                    boolean bound = candidate.modTypeId().isEmpty() || "generic".equals(candidate.modTypeId())
                            || modType != null && modType.isVirtual() || boundMachines.contains(candidate.modTypeId());
                    String machine = candidate.machine() + (bound ? "" : " · "
                            + Component.translatable("rsi.plan.recipe_picker.unbound").getString());
                    List<String> badges = new ArrayList<>(3);
                    if (selected) {
                        badges.add(Component.translatable(
                                "rsi.plan.recipe_picker.current").getString());
                    }
                    if (isDefault) {
                        badges.add(Component.translatable(
                                "rsi.plan.recipe_picker.default").getString());
                    }
                    if (excluded) {
                        badges.add(Component.translatable(
                                "rsi.plan.recipe_picker.excluded").getString());
                    }
                    String badge = String.join(" · ", badges);
                    int badgeW = badge.isEmpty() ? 0 : font.width(badge);
                    int machineWidth = cardW - 33 - badgeW;
                    graphics.drawString(font, font.plainSubstrByWidth(machine, Math.max(1, machineWidth)),
                            cardX + 25, cardY + 5, bound ? 0xFFAACDB7 : 0xFFE5B36C, false);
                    graphics.drawString(font, font.plainSubstrByWidth(candidate.name(), cardW - 16),
                            cardX + 8, cardY + 18, 0xFFE5F2E9, false);
                    if (!badge.isEmpty()) {
                        graphics.drawString(font, badge,
                                cardX + cardW - badgeW - 7, cardY + 5,
                                excluded ? 0xFFFF8A95 : 0xFF9CF0B5, false);
                    }
                    ItemStack rowHovered = preview.renderRecipeInArea(graphics, font, candidate.recipeId(),
                            cardX + 8, cardY + 30, cardW - 16, Math.max(1, cardH - 33),
                            mouseX, mouseY, node.displayStack);
                    graphics.flush();
                    if (hover && !rowHovered.isEmpty()) hovered = rowHovered;
                }
            } finally {
                graphics.flush();
                graphics.disableScissor();
            }
            if (model.maxScroll() > 0) {
                int thumbH = Math.max(8, (rowsBottom - rowsTop) * bounds.rows() / model.filtered().size());
                int thumbY = rowsTop + (rowsBottom - rowsTop - thumbH) * model.firstRow() / model.maxScroll();
                graphics.fill(x + w - 7, rowsTop, x + w - 5, rowsBottom, 0xFF233D2D);
                graphics.fill(x + w - 7, thumbY, x + w - 5, thumbY + thumbH, 0xFF66AE80);
            }
            String hint = Component.translatable("rsi.plan.recipe_picker.hint").getString();
            graphics.drawString(font, font.plainSubstrByWidth(hint, w - 16),
                    x + 8, y + h - 11, 0xFF8CA898, false);
            graphics.flush();
            if (!hovered.isEmpty() && contains(mouseX, mouseY)) {
                if (InkFluidSupport.isToken(hovered)) {
                    graphics.renderComponentTooltip(font, List.of(hovered.getHoverName(),
                            Component.literal(hovered.getCount() + " mB")), mouseX, mouseY);
                } else graphics.renderTooltip(font, hovered, mouseX, mouseY);
            } else if (hoveredCandidate != null && (mouseY - rowsTop) % bounds.rowHeight() < 30) {
                graphics.renderComponentTooltip(font, List.of(Component.literal(hoveredCandidate.name()),
                        Component.literal(hoveredCandidate.machine()), Component.literal(hoveredCandidate.recipeId().toString())),
                        mouseX, mouseY);
            }
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!contains(x, y)) return false;
        if (button == 1) {
            int row = (int) (y - rowsTop) / bounds.rowHeight();
            if (y >= rowsTop && row < model.visibleRows()) {
                int index = model.firstRow() + row;
                if (index >= 0 && index < model.filtered().size()) {
                    String recipeId = model.filtered().get(index).recipeId().toString();
                    boolean excluded = !excludedRecipes.contains(recipeId);
                    excludedRecipes = new LinkedHashSet<>(excludedRecipes);
                    if (excluded) excludedRecipes.add(recipeId);
                    else excludedRecipes.remove(recipeId);
                    CraftingPlanPreferences.setExcluded(preferencesPath, recipeId, excluded);
                }
            }
            return true;
        }
        if (button != 0) return true;
        if (x >= bounds.x() + bounds.width() - 24 && y < bounds.y() + 22) { close(); return true; }
        if (forgetW > 0 && x >= forgetX && x < forgetX + forgetW
                && y >= forgetY && y < forgetY + forgetH) {
            forgetPreference();
            return true;
        }
        if (x >= noRecipeCardX && x < noRecipeCardX + noRecipeCardW
                && y >= noRecipeCardY && y < noRecipeCardY + noRecipeCardH) {
            selectNoRecipe();
            return true;
        }
        if (y >= bounds.y() + 23 && y < bounds.y() + 39) {
            search.setFocused(true);
            search.mouseClicked(x, y, button);
            return true;
        }
        search.setFocused(false);
        int row = (int) (y - rowsTop) / bounds.rowHeight();
        if (x >= bounds.x() + bounds.width() - 9 && y >= rowsTop
                && y < rowsTop + bounds.rows() * bounds.rowHeight()) {
            int target = (int) ((y - rowsTop) * model.maxScroll() / (bounds.rows() * bounds.rowHeight()));
            model.scroll(target - model.firstRow());
            return true;
        }
        if (y >= rowsTop && row < bounds.rows()) {
            int index = model.firstRow() + row;
            if (index < model.filtered().size()) { model.hover(index); choose(); }
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        if (!contains(x, y)) return false;
        keyboardNavigation = false;
        model.scroll(delta > 0 ? -1 : delta < 0 ? 1 : 0);
        return true;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isOpen()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            keyboardNavigation = true;
            model.move(key == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            keyboardNavigation = true;
            model.move((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * model.visibleRows());
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { choose(); return true; }
        return search.isFocused() && search.keyPressed(key, scan, modifiers);
    }

    public boolean charTyped(char character, int modifiers) {
        return isOpen() && search.isFocused() && search.charTyped(character, modifiers);
    }

    private void choose() {
        Candidate candidate = model.active();
        if (candidate == null) return;
        PlanTreeNode owner = node;
        String recipeId = candidate.recipeId().toString();
        excludedRecipes = new LinkedHashSet<>(excludedRecipes);
        excludedRecipes.remove(recipeId);
        defaultRecipeId = recipeId;
        noRecipeSelected = false;
        CraftingPlanPreferences.saveRecipeChoice(preferencesPath, preferenceKey, recipeId);
        CraftingPlanPreferences.setExcluded(preferencesPath, recipeId, false);
        close();
        if (!candidate.recipeId().equals(owner.step.recipeId())) onSelect.accept(owner, candidate.recipeId());
    }

    private void selectNoRecipe() {
        noRecipeSelected = true;
        defaultRecipeId = CraftingPlanPreferences.NO_RECIPE;
        CraftingPlanPreferences.saveRecipeChoice(preferencesPath, preferenceKey,
                CraftingPlanPreferences.NO_RECIPE);
    }

    private void forgetPreference() {
        defaultRecipeId = null;
        noRecipeSelected = false;
        CraftingPlanPreferences.saveRecipeChoice(preferencesPath, preferenceKey, null);
    }
}
