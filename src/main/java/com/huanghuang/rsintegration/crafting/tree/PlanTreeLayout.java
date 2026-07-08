package com.huanghuang.rsintegration.crafting.tree;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Ported measure/layout for the recipe tree (from JEI-Crafting-Tree), producing node
 * positions in <b>logical coordinates</b> — zoom and pan are applied at render time by the
 * screen, never baked into layout. This keeps layout cacheable across camera moves and lets
 * hit-testing go through a single {@code screenToTree} inverse transform on the screen (see
 * design doc §十一).
 * <p>
 * JEICT re-measured subtrees inside {@code layout()} every frame — O(N²). Here {@link #measure}
 * memoizes into an {@link IdentityHashMap} that persists across the measure+layout pass, so the
 * layout phase's re-measures are O(1). {@link #markDirty()} invalidates when the tree is rebuilt
 * or a node's expand state toggles.
 */
public final class PlanTreeLayout {

    // ── Layout constants (logical units, pre-zoom) ──────────────
    public static final int NODE_PADDING    = 6;
    public static final int ITEM_ICON_SIZE  = 16;
    public static final int RECIPE_ICON_SIZE = 18;
    public static final int NODE_ICON_GAP   = 5;
    public static final int NODE_HEIGHT     = 30;
    public static final int SIBLING_GAP     = 18;
    public static final int LEVEL_GAP       = 76;
    /** Trailing space reserved on switchable nodes for the recipe-switch button (keeps it clear of the item icon). */
    public static final int SWITCH_BTN_SLOT = 12;

    /** A placed node in logical coordinates. {@code itemCenterX} is where the item icon centers
     *  (recipe icon sits to its left), used to anchor parent→child connectors. */
    public record Box(PlanTreeNode node, int x, int y, int w, int h, int itemCenterX) {
        public int centerX()   { return x + w / 2; }
        public int bottom()    { return y + h; }
        public boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final IdentityHashMap<PlanTreeNode, Integer> measureCache = new IdentityHashMap<>();

    private boolean dirty = true;
    private int totalWidth;
    private int totalHeight;

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Rebuild the layout only if invalidated since the last pass. */
    public void ensureLayout(PlanTreeNode root) {
        if (dirty) rebuild(root);
    }

    public void rebuild(PlanTreeNode root) {
        boxes.clear();
        measureCache.clear();
        int rootWidth = measure(root);
        totalHeight = 0;
        layout(root, 0, 0, rootWidth);
        totalWidth = rootWidth;
        dirty = false;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public int totalWidth() {
        return totalWidth;
    }

    public int totalHeight() {
        return totalHeight;
    }

    /** First box (pre-order) whose rectangle contains the given logical point, or null. */
    @Nullable
    public Box hitTest(int logicalX, int logicalY) {
        for (Box b : boxes) {
            if (b.contains(logicalX, logicalY)) return b;
        }
        return null;
    }

    /** Placed box for a node identity, or null if not currently laid out (e.g. under a collapse). */
    @Nullable
    public Box boxFor(PlanTreeNode node) {
        for (Box b : boxes) {
            if (b.node == node) return b;
        }
        return null;
    }

    /** First placed box matching an ingredient key (pre-order). Used for cost-bar centering. */
    @Nullable
    public Box boxForKey(IngredientKey key) {
        for (Box b : boxes) {
            if (b.node.key.equals(key)) return b;
        }
        return null;
    }

    // ── measure / layout ─────────────────────────────────────────

    private int nodeWidth(PlanTreeNode node) {
        int contentWidth = ITEM_ICON_SIZE;
        if (node.step != null) contentWidth += RECIPE_ICON_SIZE + NODE_ICON_GAP;
        if (node.hasAlternatives()) contentWidth += SWITCH_BTN_SLOT;
        return NODE_PADDING * 2 + contentWidth;
    }

    /** Distance from a node's left edge to its item-icon center (recipe icon sits left of it). */
    private int itemCenterOffset(PlanTreeNode node) {
        int offset = NODE_PADDING;
        if (node.step != null) offset += RECIPE_ICON_SIZE + NODE_ICON_GAP;
        return offset + ITEM_ICON_SIZE / 2;
    }

    private static List<PlanTreeNode> visibleChildren(PlanTreeNode node) {
        return node.expanded ? node.children : List.of();
    }

    private int measure(PlanTreeNode node) {
        Integer cached = measureCache.get(node);
        if (cached != null) return cached;

        int result;
        List<PlanTreeNode> visible = visibleChildren(node);
        if (visible.isEmpty()) {
            result = nodeWidth(node);
        } else {
            result = Math.max(nodeWidth(node), childrenWidth(visible));
        }
        measureCache.put(node, result);
        return result;
    }

    private int childrenWidth(List<PlanTreeNode> visible) {
        int width = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) width += SIBLING_GAP;
            width += measure(visible.get(i));
        }
        return width;
    }

    private void layout(PlanTreeNode node, int left, int y, int subtreeWidth) {
        int nodeW = nodeWidth(node);
        int x = left + (subtreeWidth - nodeW) / 2;
        int itemCenterX = x + itemCenterOffset(node);
        boxes.add(new Box(node, x, y, nodeW, NODE_HEIGHT, itemCenterX));
        totalHeight = Math.max(totalHeight, y + NODE_HEIGHT);

        List<PlanTreeNode> visible = visibleChildren(node);
        if (visible.isEmpty()) return;

        int childLeft = left + (subtreeWidth - childrenWidth(visible)) / 2;
        int childY = y + LEVEL_GAP;
        for (PlanTreeNode child : visible) {
            int childWidth = measure(child);
            layout(child, childLeft, childY, childWidth);
            childLeft += childWidth + SIBLING_GAP;
        }
    }
}
