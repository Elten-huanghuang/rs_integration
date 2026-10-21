package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Bounds;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Candidate;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** 固定在右侧的候选配方面板；只构建可见行的 JEI 布局。 */
@OnlyIn(Dist.CLIENT)
public final class RecipeCandidatePanel {
    private final Font font;
    private final RecipePreviewRenderer preview;
    private final BiConsumer<PlanTreeNode, ResourceLocation> onSelect;
    private final EditBox search;
    private PlanTreeNode node;
    private RecipeCandidatePickerModel model;
    private Bounds bounds;
    private Set<String> boundMachines = Set.of();
    private int rowsTop;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;
    private boolean keyboardNavigation;

    public RecipeCandidatePanel(Font font, RecipePreviewRenderer preview,
                                BiConsumer<PlanTreeNode, ResourceLocation> onSelect) {
        this.font = font;
        this.preview = preview;
        this.onSelect = onSelect;
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
    }

    public void close() {
        node = null;
        model = null;
        bounds = null;
        search.visible = search.active = false;
        search.setFocused(false);
    }

    public void tick() { if (isOpen()) search.tick(); }

    /** 在主视图绘制前确定侧栏位置，确保布局与输入捕获同步。 */
    public void layout(int screenWidth, int screenHeight, int contentTop) {
        if (!isOpen()) return;
        bounds = RecipeCandidatePickerModel.layout(screenWidth, screenHeight, contentTop);
        model.setVisibleRows(bounds.rows());
        rowsTop = bounds.y() + RecipeCandidatePickerModel.HEADER_HEIGHT;
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
            graphics.drawString(font, font.plainSubstrByWidth(title, Math.max(1, w - 32)),
                    x + 8, y + 6, 0xFFE0F2E6, false);
            graphics.drawString(font, "×", x + w - 17, y + 6, 0xFFAAC8B4, false);
            graphics.fill(x + 6, y + 23, x + w - 6, y + 39, 0xFF50745E);
            graphics.fill(x + 7, y + 24, x + w - 7, y + 38, 0xFF192C21);
            search.render(graphics, mouseX, mouseY, 0);
            graphics.flush();
            int rowsBottom = rowsTop + bounds.rows() * bounds.rowHeight();
            graphics.enableScissor(x + 4, rowsTop, x + w - 4, rowsBottom);
            try {
                if (model.filtered().isEmpty()) {
                    graphics.drawString(font, Component.translatable("rsi.plan.recipe_picker.empty"),
                            x + 10, rowsTop + 8, 0xFF9EB4A7, false);
                }
                for (int row = 0; row < bounds.rows(); row++) {
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
                    boolean selected = candidate.recipeId().equals(node.step.recipeId());
                    int cardX = x + 5;
                    int cardY = rowY + 2;
                    int cardW = w - 14;
                    int cardH = Math.max(1, bounds.rowHeight() - 4);
                    int cardBorder = selected ? 0xFF66D48A
                            : hover ? 0xFF5B8A6B : 0xFF385341;
                    int cardFill = selected ? 0xFF1C3B2A
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
                    int machineWidth = selected ? cardW - 82 : cardW - 42;
                    graphics.drawString(font, font.plainSubstrByWidth(machine, Math.max(1, machineWidth)),
                            cardX + 25, cardY + 5, bound ? 0xFFAACDB7 : 0xFFE5B36C, false);
                    graphics.drawString(font, font.plainSubstrByWidth(candidate.name(), cardW - 16),
                            cardX + 8, cardY + 18, 0xFFE5F2E9, false);
                    String current = Component.translatable(
                            "rsi.plan.recipe_picker.current").getString();
                    int currentW = font.width(current);
                    if (selected) {
                        graphics.drawString(font, current,
                                cardX + cardW - currentW - 7, cardY + 5,
                                0xFF9CF0B5, false);
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
        if (button != 0) return true;
        if (x >= bounds.x() + bounds.width() - 24 && y < bounds.y() + 22) { close(); return true; }
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
        close();
        if (!candidate.recipeId().equals(owner.step.recipeId())) onSelect.accept(owner, candidate.recipeId());
    }
}
