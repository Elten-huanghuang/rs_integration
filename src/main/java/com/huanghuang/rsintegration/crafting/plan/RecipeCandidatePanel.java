package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Bounds;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Candidate;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
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
        options.forEach((id, mod) -> {
            var details = preview.candidateDetails(id, node.displayStack);
            String machine = preview.categoryTitle(id).map(Component::getString)
                    .orElseGet(() -> PlanRenderEngine.formatModTypeLabel(mod));
            candidates.add(new Candidate(id, mod, details.name(), machine, details.searchText()));
        });
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
                    graphics.fill(x + 5, rowY + 1, x + w - 9, rowY + bounds.rowHeight() - 2,
                            selected ? 0xFF244B34 : index == model.cursor() ? 0xFF284036 : 0xFF172A20);
                    graphics.fill(x + 5, rowY + bounds.rowHeight() - 2, x + w - 9,
                            rowY + bounds.rowHeight() - 1, 0xFF385341);
                    if (selected) graphics.fill(x + 5, rowY + 3, x + 7, rowY + bounds.rowHeight() - 4, 0xFF66D48A);
                    graphics.flush();
                    preview.drawCategoryIcon(graphics, candidate.recipeId(), x + 10, rowY + 3, 14);
                    ModType modType = ModType.findById(candidate.modTypeId());
                    boolean bound = candidate.modTypeId().isEmpty() || "generic".equals(candidate.modTypeId())
                            || modType != null && modType.isVirtual() || boundMachines.contains(candidate.modTypeId());
                    String machine = candidate.machine() + (bound ? "" : " · "
                            + Component.translatable("rsi.plan.recipe_picker.unbound").getString());
                    graphics.drawString(font, font.plainSubstrByWidth(machine, w - 42),
                            x + 28, rowY + 5, bound ? 0xFFAACDB7 : 0xFFE5B36C, false);
                    graphics.drawString(font, font.plainSubstrByWidth(candidate.name(), w - 24),
                            x + 11, rowY + 19, 0xFFE5F2E9, false);
                    ItemStack rowHovered = preview.renderRecipeInArea(graphics, font, candidate.recipeId(),
                            x + 12, rowY + 32, w - 28, Math.max(1, bounds.rowHeight() - 36),
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
