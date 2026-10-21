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
    private static final int CARD_COLLAPSED_HEIGHT = 30;
    private static final int CARD_EXPANDED_HEIGHT = 96;
    private static final int CARD_GAP = 3;

    private final Font font;
    private final RecipePreviewRenderer preview;
    private final BiConsumer<PlanTreeNode, ResourceLocation> onSelect;
    private final Path preferencesPath;
    private final Map<String, LinkedHashMap<ResourceLocation, String>> knownCandidates =
            new LinkedHashMap<>();
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
    private int cardScroll;
    private int cardMaxScroll;
    private int cardViewportTop;
    private int cardViewportBottom;
    private final List<CardHit> cardHits = new ArrayList<>();
    private ItemStack hoveredCardItem = ItemStack.EMPTY;
    private Candidate hoveredCardCandidate;

    private record CardHit(int index, int y, int height) {}

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
            cardScroll = 0;
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
        String candidateKey = preferenceKey == null ? node.key.toString() : preferenceKey;
        LinkedHashMap<ResourceLocation, String> cached = knownCandidates.computeIfAbsent(
                candidateKey, ignored -> new LinkedHashMap<>());
        var options = new LinkedHashMap<ResourceLocation, String>();
        options.put(node.step.recipeId(),
                node.step.modType() == null ? "generic" : node.step.modType().id());
        options.putAll(cached);
        for (int i = 0; i < node.step.alternatives().size(); i++) {
            String mod = i < node.step.alternativeModTypes().size() ? node.step.alternativeModTypes().get(i) : "generic";
            options.putIfAbsent(node.step.alternatives().get(i), mod);
        }
        cached.clear();
        cached.putAll(options);
        while (knownCandidates.size() > 256) {
            knownCandidates.remove(knownCandidates.keySet().iterator().next());
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
        cardScroll = 0;
        cardMaxScroll = 0;
        cardHits.clear();
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
        cardScroll = 0;
        cardMaxScroll = 0;
        cardHits.clear();
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
        cardViewportTop = rowsTop;
        cardViewportBottom = bounds.y() + bounds.height()
                - RecipeCandidatePickerModel.FOOTER_HEIGHT;
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
        hoveredCardItem = ItemStack.EMPTY;
        hoveredCardCandidate = null;
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
            forgetW = font.width(forgetLabel) + 6;
            forgetH = 14;
            forgetX = x + w - forgetW - 24;
            forgetY = y + 5;
            int titleRight = w - 24 - forgetW - 4;
            graphics.drawString(font, font.plainSubstrByWidth(title, Math.max(1, titleRight)),
                    x + 8, y + 6, 0xFFE0F2E6, false);
            boolean forgetHover = canForget
                    && mouseX >= forgetX && mouseX < forgetX + forgetW
                    && mouseY >= forgetY && mouseY < forgetY + forgetH;
            graphics.drawString(font, forgetLabel, forgetX, forgetY,
                    canForget ? (forgetHover ? 0xFFFFFFFF : 0xFF9FC9B0) : 0xFF596B60,
                    false);
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
            renderCandidateCards(graphics, mouseX, mouseY);
            String hint = Component.translatable("rsi.plan.recipe_picker.hint").getString();
            graphics.drawString(font, font.plainSubstrByWidth(hint, w - 16),
                    x + 8, y + h - 11, 0xFF8CA898, false);
            graphics.flush();
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
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
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
                    x + 10, cardViewportTop + 8, 0xFF9EB4A7, false);
            cardMaxScroll = 0;
            cardScroll = 0;
            return;
        }

        int expandedIndex = -1;
        if (!noRecipeSelected) {
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).recipeId().equals(node.step.recipeId())) {
                    expandedIndex = i;
                    break;
                }
            }
            if (expandedIndex < 0) expandedIndex = 0;
        }
        int contentHeight = 0;
        for (int i = 0; i < filtered.size(); i++) {
            contentHeight += i == expandedIndex
                    ? CARD_EXPANDED_HEIGHT : CARD_COLLAPSED_HEIGHT;
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
                boolean expanded = index == expandedIndex;
                int cardH = expanded ? CARD_EXPANDED_HEIGHT : CARD_COLLAPSED_HEIGHT;
                boolean hover = mouseX >= cardX && mouseX < cardX + cardW
                        && mouseY >= cardY && mouseY < cardY + cardH
                        && mouseY >= cardViewportTop && mouseY < cardViewportBottom;
                if (hover) {
                    if (!keyboardNavigation) model.hover(index);
                    hoveredCardCandidate = candidate;
                }

                boolean selected = expanded;
                boolean isDefault = !noRecipeSelected
                        && candidate.recipeId().toString().equals(defaultRecipeId);
                boolean excluded = excludedRecipes.contains(candidate.recipeId().toString());
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
                preview.drawCategoryIcon(graphics, candidate.recipeId(),
                        cardX + 7, cardY + 6, 14);

                ModType modType = ModType.findById(candidate.modTypeId());
                boolean bound = candidate.modTypeId().isEmpty() || "generic".equals(candidate.modTypeId())
                        || modType != null && modType.isVirtual()
                        || boundMachines.contains(candidate.modTypeId());
                String machine = candidate.machine() + (bound ? "" : " · "
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
                String badge = String.join(" · ", badges);
                int badgeW = badge.isEmpty() ? 0 : font.width(badge);
                int machineWidth = Math.max(1, cardW - 33 - badgeW);
                graphics.drawString(font, font.plainSubstrByWidth(machine, machineWidth),
                        cardX + 25, cardY + 5,
                        bound ? 0xFFAACDB7 : 0xFFE5B36C, false);
                graphics.drawString(font, font.plainSubstrByWidth(candidate.name(), cardW - 16),
                        cardX + 8, cardY + 18, 0xFFE5F2E9, false);
                if (!badge.isEmpty()) {
                    graphics.drawString(font, badge,
                            cardX + cardW - badgeW - 7, cardY + 5,
                            excluded ? 0xFFFF8A95 : 0xFF9CF0B5, false);
                }

                if (expanded) {
                    int previewX = cardX + 8;
                    int previewY = cardY + 30;
                    int previewW = Math.max(1, cardW - 16);
                    int previewH = Math.max(1, cardH - 33);
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
                    cardViewportBottom, 0xFF233D2D);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF66AE80);
        }
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!contains(x, y)) return false;
        if (button == 1) {
            CardHit hit = cardHitAt(y);
            if (hit != null) {
                String recipeId = model.filtered().get(hit.index()).recipeId().toString();
                boolean excluded = !excludedRecipes.contains(recipeId);
                excludedRecipes = new LinkedHashSet<>(excludedRecipes);
                if (excluded) excludedRecipes.add(recipeId);
                else excludedRecipes.remove(recipeId);
                CraftingPlanPreferences.setExcluded(preferencesPath, recipeId, excluded);
            }
            return true;
        }
        if (button != 0) return true;
        if (x >= bounds.x() + bounds.width() - 24 && y < bounds.y() + 22) { close(); return true; }
        if ((defaultRecipeId != null || noRecipeSelected)
                && forgetW > 0 && x >= forgetX && x < forgetX + forgetW
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
        keyboardNavigation = false;
        cardScroll = Math.max(0, Math.min(cardMaxScroll,
                cardScroll - (delta > 0 ? CARD_COLLAPSED_HEIGHT : -CARD_COLLAPSED_HEIGHT)));
        return true;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isOpen()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
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
        return isOpen() && search.isFocused() && search.charTyped(character, modifiers);
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
