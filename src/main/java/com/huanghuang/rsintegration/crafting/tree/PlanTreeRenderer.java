package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanRenderEngine;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.plan.PlanUiDraw;
import net.minecraft.client.gui.Font;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws the recipe tree in logical coordinates. The screen applies camera (translate + scale)
 * and scissor before calling {@link #render}, so every primitive here works in unzoomed tree space.
 * <p>
 * Rendering features:
 * <ul>
 *   <li>Emerald palette with depth-differentiated fill colors</li>
 *   <li>Selected (green glow) branch styling</li>
 *   <li>Mod-type color dot below each node</li>
 *   <li>Single material quantity label under each node</li>
 *   <li>Leaf availability coloring (green/orange/red border)</li>
 * </ul>
 */
public final class PlanTreeRenderer {

    private static final float NODE_RADIUS = 4f;

    private final Font font;
    @Nullable
    private PlanTreeNode inspectedNode;
    private PlanTreeLayout cachedLayout;
    private long cachedRevision = -1;
    private final Map<PlanTreeNode, Boolean> alternatives = new IdentityHashMap<>();

    public void setInspectedNode(@Nullable PlanTreeNode node) { inspectedNode = node; }

    /**
     * Draws a recipe's category icon at (x,y) fitted to {@code size}; returns false to fall back
     * to the placeholder. Kept JEI-free here so the renderer classloads without JEI on the path.
     */
    @FunctionalInterface
    public interface RecipeIconRenderer {
        boolean draw(GuiGraphics gfx, ResourceLocation recipeId, int x, int y, int size);
    }

    @Nullable
    private RecipeIconRenderer iconRenderer;

    public PlanTreeRenderer(Font font) {
        this.font = font;
    }

    public void setIconRenderer(@Nullable RecipeIconRenderer iconRenderer) {
        this.iconRenderer = iconRenderer;
    }

    // ── Public entry ───────────────────────────────────────────────

    public void render(GuiGraphics gfx, PlanTreeLayout layout, SelectedPath path,
                       @Nullable PlanTreeNode hovered, int viewportLeft, int viewportTop,
                       int viewportRight, int viewportBottom) {
        if (cachedLayout != layout || cachedRevision != layout.revision()) {
            cachedLayout = layout;
            cachedRevision = layout.revision();
            alternatives.clear();
        }
        // Connectors behind nodes.
        gfx.drawManaged(() -> {
            for (PlanTreeLayout.Box box : layout.boxes()) {
                drawConnectors(gfx, layout, box, path,
                        viewportLeft, viewportTop, viewportRight, viewportBottom);
            }
        });
        for (PlanTreeLayout.Box box : layout.boxes()) {
            if (!intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                    box.x() - 8, box.y() - 4, box.x() + box.w() + 4, box.y() + box.h() + 8)) {
                continue;
            }
            drawNode(gfx, box, path, box.node() == hovered,
                    alternatives.computeIfAbsent(box.node(), PlanTreeNode::hasAlternatives));
        }
    }

    // ── Connectors with batch labels ───────────────────────────────

    private void drawConnectors(GuiGraphics gfx, PlanTreeLayout layout,
                                 PlanTreeLayout.Box parent, SelectedPath path,
                                 int viewportLeft, int viewportTop,
                                 int viewportRight, int viewportBottom) {
        PlanTreeNode node = parent.node();
        if (!node.expanded || node.children.isEmpty()) return;

        int parentBottomY = parent.bottom();
        int busY = parentBottomY + (PlanTreeLayout.LEVEL_GAP - parent.h()) / 2;
        int minX = parent.connectorX();
        int maxX = parent.connectorX();
        int maxY = busY;
        boolean anyChild = false;
        for (PlanTreeNode child : node.children) {
            PlanTreeLayout.Box cb = layout.boxFor(child);
            if (cb == null) continue;
            anyChild = true;
            minX = Math.min(minX, cb.connectorX());
            maxX = Math.max(maxX, cb.connectorX());
            maxY = Math.max(maxY, cb.y());
        }
        if (!anyChild || !intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                minX - PlanUiConfig.connectorWidth(), parentBottomY,
                maxX + PlanUiConfig.connectorWidth() + 1, maxY + 1)) {
            return;
        }

        int dirtyColor = path.isDirty() ? Color.CONNECTOR_PENDING.value() : Color.CONNECTOR.value();
        // Vertical stub from parent bottom down to horizontal bus.
        int lineWidth = PlanUiConfig.connectorWidth();
        if (intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                parent.connectorX() - lineWidth, parentBottomY, parent.connectorX() + lineWidth, busY)) {
            vLine(gfx, parent.connectorX(), parentBottomY, busY, dirtyColor);
        }
        for (PlanTreeNode child : node.children) {
            PlanTreeLayout.Box cb = layout.boxFor(child);
            if (cb == null) continue;
            if (!intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                    cb.connectorX() - lineWidth, busY, cb.connectorX() + lineWidth, cb.y())) continue;
            vLine(gfx, cb.connectorX(), busY, cb.y(), dirtyColor);
        }

        // Horizontal bus.
        if (maxX > minX) {
            hLine(gfx, minX, maxX, busY, dirtyColor);
        }

    }

    private static void vLine(GuiGraphics gfx, int cx, int y1, int y2, int color) {
        if (y2 <= y1) return;
        int width = PlanUiConfig.connectorWidth();
        int half = width / 2;
        gfx.fill(cx - half, y1, cx - half + width, y2, color);
    }

    private static void hLine(GuiGraphics gfx, int x1, int x2, int cy, int color) {
        if (x2 <= x1) return;
        int width = PlanUiConfig.connectorWidth();
        int half = width / 2;
        gfx.fill(x1, cy - half, x2, cy - half + width, color);
    }

    static boolean intersectsViewport(int viewportLeft, int viewportTop,
                                      int viewportRight, int viewportBottom,
                                      int left, int top, int right, int bottom) {
        return right > viewportLeft && left < viewportRight
                && bottom > viewportTop && top < viewportBottom;
    }

    // ── Node drawing ───────────────────────────────────────────────

    private void drawNode(GuiGraphics gfx, PlanTreeLayout.Box box, SelectedPath path, boolean hovered, boolean hasAlternatives) {
        PlanTreeNode node = box.node();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();

        boolean isSelected = path.isDirty() && hasAlternatives && path.isSelected(node.key);
        boolean marketRecipe = node.step != null
                && "farmingforblockheads".equals(node.step.recipeId().getNamespace())
                && node.step.recipeId().getPath().startsWith("market/");

        // 一张节点的阴影/边框/底色先作为同一纯色批次提交，再绘制物品，避免影响自定义物品渲染。
        gfx.drawManaged(() -> {
            if (isSelected) {
                PlanUiDraw.rounded(gfx, x - 2, y - 2, w + 4, h + 4, NODE_RADIUS + 2, Color.HOVER_OVERLAY.value());
            }
            PlanUiDraw.rounded(gfx, x + 2, y + 2, w, h, NODE_RADIUS, Color.SHADOW.value());
            int borderColor = node == inspectedNode ? Color.ACCENT.value()
                    : marketRecipe && !hovered && !isSelected ? Color.READY.value() : borderColor(node, hovered, isSelected);
            PlanUiDraw.rounded(gfx, x, y, w, h, NODE_RADIUS, borderColor);
            PlanUiDraw.roundedGradient(gfx, x + 1, y + 1, w - 2, h - 2, NODE_RADIUS - 1,
                    fillTopColor(node), fillBotColor(node));
            // 引用节点压暗：该批产出已在树中别处展开成本，这里只表示消耗关系。
            if (node.referenceOnly) {
                PlanUiDraw.rounded(gfx, x + 1, y + 1, w - 2, h - 2, NODE_RADIUS - 1, Color.REFERENCE_OVERLAY.value());
            }
        });

        int iconY = y + (h - PlanTreeLayout.ITEM_ICON_SIZE) / 2;
        int contentStartX = x + PlanTreeLayout.NODE_PADDING;

        // 5. Recipe-category icon (JEI category icon, or placeholder color block on fallback).
        if (node.step != null && !PlanTreeLayout.showsLabel(node)) {
            int rx = box.itemCenterX() - PlanTreeLayout.ITEM_ICON_SIZE / 2
                    - PlanTreeLayout.NODE_ICON_GAP - PlanTreeLayout.RECIPE_ICON_SIZE;
            if (rx < contentStartX) rx = contentStartX;
            int ry = y + (h - PlanTreeLayout.RECIPE_ICON_SIZE) / 2;
            boolean drewIcon = iconRenderer != null && iconRenderer.draw(
                    gfx, node.step.recipeId(), rx, ry, PlanTreeLayout.RECIPE_ICON_SIZE);
            if (!drewIcon) {
                PlanUiDraw.rounded(gfx, rx, ry, PlanTreeLayout.RECIPE_ICON_SIZE,
                        PlanTreeLayout.RECIPE_ICON_SIZE, 3f, Color.SURFACE.value());
            }
            if (marketRecipe) {
                gfx.drawString(font, "M", rx + 3, ry + 2, Color.TEXT.value(), true);
            }
        }

        // 6. Item icon (or Ingredient carousel for tag nodes).
        int iconX = box.itemCenterX() - PlanTreeLayout.ITEM_ICON_SIZE / 2;
        ItemStack stack = node.displayStack;
        if (node.lockedMaterial != null && !node.lockedMaterial.isEmpty()) {
            stack = node.lockedMaterial;
        }
        if (node.ingredient != null) {
            ItemStack[] opts = node.ingredient.getItems();
            if (opts.length > 1 && node.lockedMaterial == null) {
                // Cycle through the tag's members ~every 1.2s so all alternatives are visible.
                int idx = (int) ((System.currentTimeMillis() / 1200) % opts.length);
                ItemStack cycled = opts[idx];
                if (!cycled.isEmpty()) stack = cycled;
                drawCarouselIndicator(gfx, iconX, iconY, opts.length);
            }
        }
        if (!stack.isEmpty()) {
            InkFluidRenderer.render(gfx, stack, iconX, iconY);
        }
        if (node.lockedMaterial != null) {
            drawMaterialLock(gfx, iconX + PlanTreeLayout.ITEM_ICON_SIZE - 5, iconY - 1);
        }

        // 6b. Limited badge — node hides extra alternatives beyond the candidate cap.
        if (node.limited) {
            drawLimitedBadge(gfx, x + w - 6, y - 1);
        }
        // 6b-2. 引用角标：放在左上角，与右上"候选上限"、右下"截断"角标区分。
        if (node.referenceOnly) {
            drawReferenceBadge(gfx, x + 1, y + 1);
        }
        if (node.viewTruncated) {
            drawViewTruncatedBadge(gfx, x + w - 6, y + h - 1);
        }
        if (!node.warnings.isEmpty() || node.prerequisiteBlocked) {
            drawPrerequisiteBadge(gfx, x + w - PlanTreeLayout.ITEM_ICON_SIZE / 2,
                    iconY, node.prerequisiteBlocked);
        } else if (!node.hints.isEmpty()) {
            drawHintBadge(gfx, x + w - PlanTreeLayout.ITEM_ICON_SIZE / 2, iconY);
        }

        // 6c. Fold indicator on the left edge for foldable nodes (non-root with a subtree). A
        //     collapsed step otherwise looks identical to a raw leaf, so the [+]/[−] is the primary
        //     cue that the node hides children; it is also left-clickable (see CraftingPlanScreen).
        if (!node.children.isEmpty() && node.depth != 0) {
            drawFoldIndicator(gfx, x - 6, y + h / 2, node.expanded);
        }

        // 7. Switch button on the right edge for nodes with alternative recipes — a distinct
        //    bordered dropdown control (not a bare chevron) so "this recipe is swappable" reads
        //    at a glance and highlights on hover. Sits in the reserved SWITCH_BTN_SLOT trailing
        //    space (see PlanTreeLayout.nodeWidth), clear of the item icon.
        if (hasAlternatives) {
            drawSwitchButton(gfx, x + w - PlanTreeLayout.NODE_PADDING - 9, y + (h - 9) / 2, hovered, isSelected);
        }

        // 8. Count label under node.
        String count = InkFluidRenderer.compactQuantity(stack, node.displayAmount());
        int cy = box.bottom() + 2;
        if (PlanTreeLayout.showsLabel(node)) {
            int textX = iconX + PlanTreeLayout.ITEM_ICON_SIZE + PlanTreeLayout.NODE_PADDING;
            int maxWidth = Math.max(1, PlanUiConfig.nodeLabelWidth());
            String name = node.displayStack.getHoverName().getString();
            int nameWidth = Math.max(1, maxWidth - font.width(" " + count));
            if (font.width(name) > nameWidth) name = font.plainSubstrByWidth(name,
                    Math.max(1, nameWidth - font.width("…"))) + "…";
            gfx.drawString(font, Component.literal(name + " " + count).withStyle(ChatFormatting.BOLD), textX, y + 6, Color.TEXT.value(), false);
            boolean missing = node.missingCount > 0;
            String subtitle = node.step == null ? Component.translatable("rsi.plan.details.availability", node.needed,
                    node.available, node.missingCount).getString() : node.step.modType() == null ? Component.translatable("rsi.plan.workspace.recursive_crafting").getString() : PlanRenderEngine.formatMachineLabel(node.step);
            gfx.drawString(font, font.plainSubstrByWidth(subtitle, maxWidth), textX, y + 22,
                    missing ? Color.WARNING.value() : Color.MUTED.value(), false);
        } else {
            int cx = box.itemCenterX() - font.width(count) / 2;
            PlanUiDraw.textBackdrop(gfx, font, cx, cy, count, Color.TEXT_BACKDROP.value());
            gfx.drawString(font, count, cx, cy, Color.TEXT.value(), false);
            cy += font.lineHeight + 1;
        }

        // 9. Mod color dot below count.
        if (!PlanTreeLayout.showsLabel(node) && node.step != null && node.step.modType() != null) {
            String modName = node.step.modType().id();
            int dotColor = Color.ACCENT.value();
            int dotY = cy;
            // Small color dot.
            gfx.fill(box.centerX() - 3, dotY, box.centerX() + 3, dotY + 4, dotColor);
            // 优先使用实际绑定机器名称，宽度限制在节点内。
            String label = PlanRenderEngine.formatMachineLabel(node.step);
            int maxW = w + 6;
            if (font.width(label) > maxW) {
                label = font.plainSubstrByWidth(label, maxW - font.width("…")) + "…";
            }
            int mnw = font.width(label);
            gfx.drawString(font, label, box.centerX() - mnw / 2, dotY + 4,
                    Color.MUTED.value(), false);
        }
    }

    // ── Border color logic ─────────────────────────────────────────

    private int borderColor(PlanTreeNode node, boolean hovered, boolean isSelected) {
        if (hovered) return Color.TEXT.value();
        if (node.prerequisiteBlocked) return Color.ERROR.value();
        if (!node.warnings.isEmpty()) return Color.WARNING.value();
        if (isSelected) return Color.ACCENT.value();
        // Availability tint for leaves with known numbers.
        if (node.needed > 0) {
            if (node.missingCount > 0) return Color.WARNING.value();
        }
        return Color.NODE_BORDER.value();
    }

    // ── Fill colors ────────────────────────────────────────────────

    private int fillTopColor(PlanTreeNode node) {
        if (node.isLeaf()) return node.missingCount > 0 ? Color.WARNING_SURFACE.value() : Color.LEAF.value();
        if (node.depth == 0) return Color.ROOT.value();
        return Color.STEP.value();
    }

    private int fillBotColor(PlanTreeNode node) {
        if (node.isLeaf()) return Color.LEAF.value();
        if (node.depth == 0) return Color.ROOT.value();
        return Color.STEP.value();
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Distinct dropdown button (border + fill + down triangle) marking a switchable node. */
    private static void drawSwitchButton(GuiGraphics gfx, int x, int y, boolean hovered, boolean selected) {
        int size = 9;
        int border = Color.ACCENT.value();
        int bg = hovered ? Color.TEXT.value() : Color.SELECTED.value();
        PlanUiDraw.rounded(gfx, x, y, size, size, 2f, border);
        gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, bg);
        int arrow = hovered ? Color.HOVER.value() : Color.TEXT.value();
        int midX = x + size / 2;
        int topY = y + 3;
        gfx.fill(midX - 2, topY, midX + 3, topY + 1, arrow);
        gfx.fill(midX - 1, topY + 1, midX + 2, topY + 2, arrow);
        gfx.fill(midX, topY + 2, midX + 1, topY + 3, arrow);
    }

    /** Small [−] (expanded) / [+] (collapsed) expander marking a foldable subtree. */
    private static void drawFoldIndicator(GuiGraphics gfx, int cx, int cy, boolean expanded) {
        int r = 4;
        PlanUiDraw.rounded(gfx, cx - r, cy - r, r * 2f, r * 2f, 2f, Color.HOVER.value());
        PlanUiDraw.rounded(gfx, cx - r + 1, cy - r + 1, r * 2f - 2, r * 2f - 2, 1.5f, Color.SURFACE.value());
        gfx.fill(cx - 2, cy, cx + 3, cy + 1, Color.ACCENT.value());          // horizontal bar → "−"
        if (!expanded) gfx.fill(cx, cy - 2, cx + 1, cy + 3, Color.ACCENT.value()); // + vertical bar → "+"
    }

    /** Orange corner triangle marking a node whose extra alternatives are hidden (candidate cap). */
    private static void drawLimitedBadge(GuiGraphics gfx, int cornerX, int topY) {
        for (int i = 0; i < 5; i++) {
            gfx.fill(cornerX - i, topY + i, cornerX + 5 - i, topY + i + 1, Color.WARNING.value());
        }
    }

    /** Orange corner triangle marking children hidden by the client tree display limits. */
    private static void drawViewTruncatedBadge(GuiGraphics gfx, int cornerX, int bottomY) {
        for (int i = 0; i < 5; i++) {
            gfx.fill(cornerX - i, bottomY - i, cornerX + 5 - i, bottomY - i + 1, Color.WARNING.value());
        }
    }

    /** 左上角小方标，标出"该批产出已在树中别处展开，此处仅为消耗引用"。 */
    private static void drawReferenceBadge(GuiGraphics gfx, int x, int y) {
        int size = 9;
        PlanUiDraw.rounded(gfx, x, y, size, size, 2f, Color.REFERENCE.value());
        PlanUiDraw.rounded(gfx, x + 1, y + 1, size - 2, size - 2, 1.5f, Color.SURFACE.value());
        // 右上斜箭头，暗示"指向已展开的那一处"。
        for (int i = 0; i < 4; i++) {
            gfx.fill(x + 2 + i, y + 6 - i, x + 3 + i, y + 7 - i, Color.REFERENCE.value());
        }
        gfx.fill(x + 5, y + 2, x + 7, y + 3, Color.REFERENCE.value());
        gfx.fill(x + 6, y + 2, x + 7, y + 4, Color.REFERENCE.value());
    }

    private void drawPrerequisiteBadge(GuiGraphics gfx, int x, int y, boolean blocked) {
        int color = blocked ? Color.ERROR.value() : Color.WARNING.value();
        int size = PlanTreeLayout.ITEM_ICON_SIZE;
        PlanUiDraw.rounded(gfx, x, y, size, size, size / 2f, Color.ERROR_SURFACE.value());
        PlanUiDraw.rounded(gfx, x + 1, y + 1, size - 2, size - 2,
                (size - 2) / 2f, color);
        PlanUiDraw.rounded(gfx, x + 3, y + 3, size - 6, size - 6,
                (size - 6) / 2f, Color.ERROR_SURFACE.value());
        gfx.drawString(font, "!", x + 6, y + 2, color, true);
    }

    private void drawHintBadge(GuiGraphics gfx, int x, int y) {
        int size = PlanTreeLayout.ITEM_ICON_SIZE;
        PlanUiDraw.rounded(gfx, x, y, size, size, size / 2f, Color.SURFACE.value());
        PlanUiDraw.rounded(gfx, x + 1, y + 1, size - 2, size - 2,
                (size - 2) / 2f, Color.REFERENCE.value());
        PlanUiDraw.rounded(gfx, x + 3, y + 3, size - 6, size - 6,
                (size - 6) / 2f, Color.SURFACE.value());
        gfx.drawString(font, "i", x + 7, y + 2, Color.REFERENCE.value(), true);
    }

    /** Bottom bar of dots under a carousel icon, one dot per tag member (max 5). */
    private static void drawCarouselIndicator(GuiGraphics gfx, int iconX, int iconY, int optionCount) {
        int dots = Math.min(optionCount, 5);
        int barY = iconY + PlanTreeLayout.ITEM_ICON_SIZE;
        int totalW = dots * 3 - 1;
        int startX = iconX + (PlanTreeLayout.ITEM_ICON_SIZE - totalW) / 2;
        for (int i = 0; i < dots; i++) {
            int dx = startX + i * 3;
            gfx.fill(dx, barY, dx + 2, barY + 1, Color.ACCENT.value());
        }
    }

    private static void drawMaterialLock(GuiGraphics gfx, int x, int y) {
        gfx.fill(x + 1, y, x + 4, y + 3, Color.TEXT.value());
        gfx.fill(x, y + 2, x + 5, y + 7, Color.READY.value());
        gfx.fill(x + 2, y + 4, x + 3, y + 6, Color.TEXT.value());
    }
}
