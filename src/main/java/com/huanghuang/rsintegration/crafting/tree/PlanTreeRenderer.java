package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanRenderEngine;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;

import javax.annotation.Nullable;

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

    // ── Palette ────────────────────────────────────────────────────
    private static final int C_SHADOW          = 0x55000000;
    // Depth-differentiated fills
    private static final int C_FILL_ROOT       = 0xF01A2E1A;
    private static final int C_FILL_ROOT_BOT   = 0xF0142816;
    private static final int C_FILL_STEP       = 0xF01A221E;
    private static final int C_FILL_STEP_BOT   = 0xF0141A16;
    private static final int C_FILL_LEAF       = 0xF01A1A1A;
    private static final int C_FILL_LEAF_BOT   = 0xF0141414;
    private static final int C_FILL_MISSING_BG = 0xF01A201A;

    private static final int C_BORDER_NORMAL   = 0xC0A8C4B4;
    private static final int C_BORDER_HOVER    = 0xFFFFFFFF;
    private static final int C_BORDER_SELECT   = 0xFF4AE04A;  // green glow
    private static final int C_READY           = 0xFF4AE04A;
    private static final int C_PARTIAL         = 0xFFFFAA33;
    private static final int C_MISSING         = 0xFFFF4444;
    private static final int C_CONNECTOR       = 0xFF44CC88;
    private static final int C_CONNECTOR_DIRTY = 0xFF558866;
    private static final int C_RECIPE_ICON     = 0xFF2E4A3C;
    private static final int C_COUNT_TEXT      = 0xFFD8E8DC;
    private static final int C_COUNT_BACKDROP  = 0xAA0A140E;
    private static final int C_MOD_LABEL       = 0xFF889988;
    private static final int C_PATH_TINT       = 0x111A3E1A;
    private static final int C_LIMITED         = 0xFFF0A040;  // over-candidate-cap badge
    private static final int C_TRUNCATED       = 0xFFE0A040;  // hidden by display depth/node limits
    private static final int C_CAROUSEL        = 0xFF66CCEE;  // tag-input carousel indicator
    private static final int C_REFERENCE       = 0xFF6FA8C8;  // 引用节点角标（成本已在别处展开）
    private static final int C_REFERENCE_TINT  = 0x33101C24;  // 引用节点整体压暗
    private static final int C_PREREQ_WARNING  = 0xFFFFAA33;
    private static final int C_PREREQ_BLOCKED  = 0xFFFF4444;
    private static final int C_MACHINE_HINT    = 0xFF66CCEE;

    private static final float NODE_RADIUS = 4f;
    private static final int LINE_THICKNESS = 2;

    private final Font font;

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
        // Connectors behind nodes.
        for (PlanTreeLayout.Box box : layout.boxes()) {
            drawConnectors(gfx, layout, box, path,
                    viewportLeft, viewportTop, viewportRight, viewportBottom);
        }
        for (PlanTreeLayout.Box box : layout.boxes()) {
            if (!intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                    box.x() - 8, box.y() - 4, box.x() + box.w() + 4, box.y() + box.h() + 8)) {
                continue;
            }
            drawNode(gfx, box, path, box.node() == hovered);
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
        int busY = parentBottomY + PlanTreeLayout.LEVEL_GAP / 2;
        int minX = parent.itemCenterX();
        int maxX = parent.itemCenterX();
        int maxY = busY;
        boolean anyChild = false;
        for (PlanTreeNode child : node.children) {
            PlanTreeLayout.Box cb = layout.boxFor(child);
            if (cb == null) continue;
            anyChild = true;
            minX = Math.min(minX, cb.itemCenterX());
            maxX = Math.max(maxX, cb.itemCenterX());
            maxY = Math.max(maxY, cb.y());
        }
        if (!anyChild || !intersectsViewport(viewportLeft, viewportTop, viewportRight, viewportBottom,
                minX - LINE_THICKNESS, parentBottomY, maxX + LINE_THICKNESS + 1, maxY + 1)) {
            return;
        }

        int dirtyColor = path.isDirty() ? C_CONNECTOR_DIRTY : C_CONNECTOR;
        // Vertical stub from parent bottom down to horizontal bus.
        vLine(gfx, parent.itemCenterX(), parentBottomY, busY, dirtyColor);
        for (PlanTreeNode child : node.children) {
            PlanTreeLayout.Box cb = layout.boxFor(child);
            if (cb == null) continue;
            vLine(gfx, cb.itemCenterX(), busY, cb.y(), dirtyColor);
        }

        // Horizontal bus.
        if (maxX > minX) {
            hLine(gfx, minX, maxX, busY, dirtyColor);
        }

    }

    private static void vLine(GuiGraphics gfx, int cx, int y1, int y2, int color) {
        if (y2 <= y1) return;
        int half = LINE_THICKNESS / 2;
        gfx.fill(cx - half, y1, cx - half + LINE_THICKNESS, y2, color);
    }

    private static void hLine(GuiGraphics gfx, int x1, int x2, int cy, int color) {
        if (x2 <= x1) return;
        int half = LINE_THICKNESS / 2;
        gfx.fill(x1, cy - half, x2, cy - half + LINE_THICKNESS, color);
    }

    static boolean intersectsViewport(int viewportLeft, int viewportTop,
                                      int viewportRight, int viewportBottom,
                                      int left, int top, int right, int bottom) {
        return right > viewportLeft && left < viewportRight
                && bottom > viewportTop && top < viewportBottom;
    }

    // ── Node drawing ───────────────────────────────────────────────

    private void drawNode(GuiGraphics gfx, PlanTreeLayout.Box box, SelectedPath path, boolean hovered) {
        PlanTreeNode node = box.node();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();

        boolean isSelected = node.hasAlternatives() && path.isSelected(node.key);
        boolean marketRecipe = node.step != null
                && "farmingforblockheads".equals(node.step.recipeId().getNamespace())
                && node.step.recipeId().getPath().startsWith("market/");

        // Path tint: subtle green backing behind a user-selected alternative node.
        if (isSelected) {
            UIRenderer.rounded(gfx, x - 2, y - 2, w + 4, h + 4, NODE_RADIUS + 2, C_PATH_TINT);
        }

        // 1. Shadow.
        UIRenderer.rounded(gfx, x + 2, y + 2, w, h, NODE_RADIUS, C_SHADOW);

        // 2. Border.
        int borderColor = marketRecipe && !hovered && !isSelected
                ? 0xFF55B978 : borderColor(node, hovered, isSelected);
        UIRenderer.rounded(gfx, x, y, w, h, NODE_RADIUS, borderColor);

        // 3. Fill — depth-differentiated + missing tint.
        UIRenderer.roundedGradient(gfx, x + 1, y + 1, w - 2, h - 2, NODE_RADIUS - 1,
                fillTopColor(node), fillBotColor(node));

        // 3b. 引用节点压暗：该批产出已在树中别处展开成本，这里只表示消耗关系。
        if (node.referenceOnly) {
            UIRenderer.rounded(gfx, x + 1, y + 1, w - 2, h - 2, NODE_RADIUS - 1, C_REFERENCE_TINT);
        }

        int iconY = y + (h - PlanTreeLayout.ITEM_ICON_SIZE) / 2;
        int contentStartX = x + PlanTreeLayout.NODE_PADDING;

        // 5. Recipe-category icon (JEI category icon, or placeholder color block on fallback).
        if (node.step != null) {
            int rx = box.itemCenterX() - PlanTreeLayout.ITEM_ICON_SIZE / 2
                    - PlanTreeLayout.NODE_ICON_GAP - PlanTreeLayout.RECIPE_ICON_SIZE;
            if (rx < contentStartX) rx = contentStartX;
            int ry = y + (h - PlanTreeLayout.RECIPE_ICON_SIZE) / 2;
            boolean drewIcon = iconRenderer != null && iconRenderer.draw(
                    gfx, node.step.recipeId(), rx, ry, PlanTreeLayout.RECIPE_ICON_SIZE);
            if (!drewIcon) {
                UIRenderer.rounded(gfx, rx, ry, PlanTreeLayout.RECIPE_ICON_SIZE,
                        PlanTreeLayout.RECIPE_ICON_SIZE, 3f, C_RECIPE_ICON);
            }
            if (marketRecipe) {
                gfx.drawString(font, "M", rx + 3, ry + 2, 0xFFFFFFFF, true);
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
        if (node.hasAlternatives()) {
            drawSwitchButton(gfx, x + w - PlanTreeLayout.NODE_PADDING - 9, y + (h - 9) / 2, hovered, isSelected);
        }

        // 8. Count label under node.
        String count = InkFluidRenderer.compactQuantity(stack, node.displayAmount());
        int tw = font.width(count);
        int cx = box.itemCenterX() - tw / 2;
        int cy = box.bottom() + 2;
        UIRenderer.textBackdrop(gfx, font, cx, cy, count, C_COUNT_BACKDROP);
        gfx.drawString(font, count, cx, cy, C_COUNT_TEXT, false);

        // 9. Mod color dot below count.
        if (node.step != null && node.step.modType() != null) {
            String modName = node.step.modType().id();
            int dotColor = PlanRenderEngine.modLabelColor(modName);
            int dotY = cy + font.lineHeight + 1;
            // Small color dot.
            gfx.fill(box.itemCenterX() - 3, dotY, box.itemCenterX() + 3, dotY + 4, dotColor);
            // 优先使用实际绑定机器名称，宽度限制在节点内。
            String label = PlanRenderEngine.formatMachineLabel(node.step);
            int maxW = w + 6;
            if (font.width(label) > maxW) {
                label = font.plainSubstrByWidth(label, maxW - font.width("…")) + "…";
            }
            int mnw = font.width(label);
            gfx.drawString(font, label, box.itemCenterX() - mnw / 2, dotY + 4,
                    C_MOD_LABEL, false);
        }
    }

    // ── Border color logic ─────────────────────────────────────────

    private int borderColor(PlanTreeNode node, boolean hovered, boolean isSelected) {
        if (hovered) return C_BORDER_HOVER;
        if (node.prerequisiteBlocked) return C_PREREQ_BLOCKED;
        if (!node.warnings.isEmpty()) return C_PREREQ_WARNING;
        if (isSelected) return C_BORDER_SELECT;
        // Availability tint for leaves with known numbers.
        if (node.needed > 0) {
            if (node.available >= node.needed) return C_READY;
            if (node.available > 0) return C_PARTIAL;
            return C_MISSING;
        }
        return C_BORDER_NORMAL;
    }

    // ── Fill colors ────────────────────────────────────────────────

    private int fillTopColor(PlanTreeNode node) {
        if (node.isLeaf()) return node.available < node.needed ? C_FILL_MISSING_BG : C_FILL_LEAF;
        if (node.depth == 0) return C_FILL_ROOT;
        return C_FILL_STEP;
    }

    private int fillBotColor(PlanTreeNode node) {
        if (node.isLeaf()) return C_FILL_LEAF_BOT;
        if (node.depth == 0) return C_FILL_ROOT_BOT;
        return C_FILL_STEP_BOT;
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Distinct dropdown button (border + fill + down triangle) marking a switchable node. */
    private static void drawSwitchButton(GuiGraphics gfx, int x, int y, boolean hovered, boolean selected) {
        int size = 9;
        int border = selected ? C_BORDER_SELECT : 0xFF6ABF6A;
        int bg = hovered ? 0xFFFFFFFF : 0xFF23421F;
        UIRenderer.rounded(gfx, x, y, size, size, 2f, border);
        gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, bg);
        int arrow = hovered ? 0xFF204020 : 0xFFDDFFDD;
        int midX = x + size / 2;
        int topY = y + 3;
        gfx.fill(midX - 2, topY, midX + 3, topY + 1, arrow);
        gfx.fill(midX - 1, topY + 1, midX + 2, topY + 2, arrow);
        gfx.fill(midX, topY + 2, midX + 1, topY + 3, arrow);
    }

    /** Small [−] (expanded) / [+] (collapsed) expander marking a foldable subtree. */
    private static void drawFoldIndicator(GuiGraphics gfx, int cx, int cy, boolean expanded) {
        int r = 4;
        UIRenderer.rounded(gfx, cx - r, cy - r, r * 2f, r * 2f, 2f, 0xFF3A6A3A);
        UIRenderer.rounded(gfx, cx - r + 1, cy - r + 1, r * 2f - 2, r * 2f - 2, 1.5f, 0xFF13260F);
        gfx.fill(cx - 2, cy, cx + 3, cy + 1, 0xFF9BE59B);          // horizontal bar → "−"
        if (!expanded) gfx.fill(cx, cy - 2, cx + 1, cy + 3, 0xFF9BE59B); // + vertical bar → "+"
    }

    /** Orange corner triangle marking a node whose extra alternatives are hidden (candidate cap). */
    private static void drawLimitedBadge(GuiGraphics gfx, int cornerX, int topY) {
        for (int i = 0; i < 5; i++) {
            gfx.fill(cornerX - i, topY + i, cornerX + 5 - i, topY + i + 1, C_LIMITED);
        }
    }

    /** Orange corner triangle marking children hidden by the client tree display limits. */
    private static void drawViewTruncatedBadge(GuiGraphics gfx, int cornerX, int bottomY) {
        for (int i = 0; i < 5; i++) {
            gfx.fill(cornerX - i, bottomY - i, cornerX + 5 - i, bottomY - i + 1, C_TRUNCATED);
        }
    }

    /** 左上角小方标，标出"该批产出已在树中别处展开，此处仅为消耗引用"。 */
    private static void drawReferenceBadge(GuiGraphics gfx, int x, int y) {
        int size = 9;
        UIRenderer.rounded(gfx, x, y, size, size, 2f, C_REFERENCE);
        UIRenderer.rounded(gfx, x + 1, y + 1, size - 2, size - 2, 1.5f, 0xFF10242E);
        // 右上斜箭头，暗示"指向已展开的那一处"。
        for (int i = 0; i < 4; i++) {
            gfx.fill(x + 2 + i, y + 6 - i, x + 3 + i, y + 7 - i, C_REFERENCE);
        }
        gfx.fill(x + 5, y + 2, x + 7, y + 3, C_REFERENCE);
        gfx.fill(x + 6, y + 2, x + 7, y + 4, C_REFERENCE);
    }

    private void drawPrerequisiteBadge(GuiGraphics gfx, int x, int y, boolean blocked) {
        int color = blocked ? C_PREREQ_BLOCKED : C_PREREQ_WARNING;
        int size = PlanTreeLayout.ITEM_ICON_SIZE;
        UIRenderer.rounded(gfx, x, y, size, size, size / 2f, 0xFF140303);
        UIRenderer.rounded(gfx, x + 1, y + 1, size - 2, size - 2,
                (size - 2) / 2f, color);
        UIRenderer.rounded(gfx, x + 3, y + 3, size - 6, size - 6,
                (size - 6) / 2f, 0xFF32100D);
        gfx.drawString(font, "!", x + 6, y + 2, color, true);
    }

    private void drawHintBadge(GuiGraphics gfx, int x, int y) {
        int size = PlanTreeLayout.ITEM_ICON_SIZE;
        UIRenderer.rounded(gfx, x, y, size, size, size / 2f, 0xFF102633);
        UIRenderer.rounded(gfx, x + 1, y + 1, size - 2, size - 2,
                (size - 2) / 2f, C_MACHINE_HINT);
        UIRenderer.rounded(gfx, x + 3, y + 3, size - 6, size - 6,
                (size - 6) / 2f, 0xFF102633);
        gfx.drawString(font, "i", x + 7, y + 2, C_MACHINE_HINT, true);
    }

    /** Bottom bar of dots under a carousel icon, one dot per tag member (max 5). */
    private static void drawCarouselIndicator(GuiGraphics gfx, int iconX, int iconY, int optionCount) {
        int dots = Math.min(optionCount, 5);
        int barY = iconY + PlanTreeLayout.ITEM_ICON_SIZE;
        int totalW = dots * 3 - 1;
        int startX = iconX + (PlanTreeLayout.ITEM_ICON_SIZE - totalW) / 2;
        for (int i = 0; i < dots; i++) {
            int dx = startX + i * 3;
            gfx.fill(dx, barY, dx + 2, barY + 1, C_CAROUSEL);
        }
    }

    private static void drawMaterialLock(GuiGraphics gfx, int x, int y) {
        gfx.fill(x + 1, y, x + 4, y + 3, 0xFFB6F0C0);
        gfx.fill(x, y + 2, x + 5, y + 7, 0xFF247A42);
        gfx.fill(x + 2, y + 4, x + 3, y + 6, 0xFFFFFFFF);
    }
}
