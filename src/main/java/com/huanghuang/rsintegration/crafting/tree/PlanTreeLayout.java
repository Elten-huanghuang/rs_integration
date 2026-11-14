package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.PlanUiConfig;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

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
    public static final int NODE_HEIGHT     = 36;
    public static final int SIBLING_GAP     = 18;
    public static final int LEVEL_GAP       = 76;
    /** Trailing space reserved on switchable nodes for the recipe-switch button (keeps it clear of the item icon). */
    public static final int SWITCH_BTN_SLOT = 12;

    /** A placed node in logical coordinates. {@code itemCenterX} is where the item icon centers
     *  (recipe icon sits to its left), used to anchor parent→child connectors. */
    public record Box(PlanTreeNode node, int x, int y, int w, int h, int itemCenterX) {
        public int centerX()   { return x + w / 2; }
        /** 带名称的卡片从卡片中央连线，紧凑模式沿用物品图标中心。 */
        public int connectorX() { return showsLabel(node) ? centerX() : itemCenterX; }
        public int bottom()    { return y + h; }
        public boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    record Connector(int x1, int y1, int x2, int y2) {
        int left(int width) { return x1 == x2 ? x1 - width / 2 : x1; }
        int right(int width) { return x1 == x2 ? x1 - width / 2 + width : x2; }
        int top(int width) { return x1 == x2 ? y1 : y1 - width / 2; }
        int bottom(int width) { return x1 == x2 ? y2 : y1 - width / 2 + width; }
    }

    /** 同层节点及横线互不重叠，按横坐标二分即可定位可见区。 */
    private static final class Level {
        private final List<Box> boxes = new ArrayList<>();
        private final List<Connector> verticals = new ArrayList<>();
        private final List<Connector> horizontals = new ArrayList<>();
    }

    private final List<Box> boxes = new ArrayList<>();
    private final IdentityHashMap<PlanTreeNode, Integer> measureCache = new IdentityHashMap<>();
    private final IdentityHashMap<PlanTreeNode, Box> boxByNode = new IdentityHashMap<>();
    private final IdentityHashMap<PlanTreeNode, Boolean> cycleCache = new IdentityHashMap<>();
    private final List<Level> levels = new ArrayList<>();

    private boolean dirty = true;
    private long revision;
    private int totalWidth;
    private int totalHeight;
    private int labelWidth = -1;
    private boolean leafNames;

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Rebuild the layout only if invalidated since the last pass. */
    public void ensureLayout(PlanTreeNode root) {
        if (labelWidth != PlanUiConfig.nodeLabelWidth() || leafNames != PlanUiConfig.showLeafNames()) dirty = true;
        if (dirty) rebuild(root);
    }

    public void rebuild(PlanTreeNode root) {
        labelWidth = PlanUiConfig.nodeLabelWidth();
        leafNames = PlanUiConfig.showLeafNames();
        boxes.clear();
        measureCache.clear();
        boxByNode.clear();
        levels.clear();
        cacheCycles(root);
        int rootWidth = measure(root);
        totalHeight = 0;
        layout(root, 0, 0, rootWidth);
        indexConnectors();
        totalWidth = rootWidth;
        dirty = false;
        revision++;
    }

    /** 相机变化不递增，供缩略图复用静态投影。 */
    public long revision() { return revision; }

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
        int level = Math.floorDiv(logicalY, LEVEL_GAP);
        if (level < 0 || level >= levels.size()) return null;
        List<Box> row = levels.get(level).boxes;
        int index = firstBoxEndingAfter(row, logicalX);
        if (index == row.size()) return null;
        Box box = row.get(index);
        return box.contains(logicalX, logicalY) ? box : null;
    }

    void forEachVisibleBox(int left, int top, int right, int bottom, Consumer<Box> visitor) {
        for (int row = levelIndex((long) top - NODE_HEIGHT - 8),
                 end = levelIndex((long) bottom + 4) + 1; row < Math.min(end, levels.size()); row++) {
            List<Box> candidates = levels.get(row).boxes;
            for (int i = firstBoxEndingAfter(candidates, (long) left - 4); i < candidates.size(); i++) {
                Box box = candidates.get(i);
                if ((long) box.x() - 8 >= right) break;
                if ((long) box.bottom() + 8 > top && (long) box.y() - 4 < bottom) visitor.accept(box);
            }
        }
    }

    void forEachVisibleConnector(int left, int top, int right, int bottom, int width,
                                 Consumer<Connector> visitor) {
        for (int row = levelIndex((long) top - LEVEL_GAP - width),
                 end = levelIndex((long) bottom + width) + 1; row < Math.min(end, levels.size()); row++) {
            Level level = levels.get(row);
            visitConnectors(level.verticals, left, top, right, bottom, width, visitor);
            visitConnectors(level.horizontals, left, top, right, bottom, width, visitor);
        }
    }

    private int levelIndex(long y) {
        return (int) Math.max(0L, Math.min(levels.size(), Math.floorDiv(y, LEVEL_GAP)));
    }

    private static int firstBoxEndingAfter(List<Box> row, long x) {
        int low = 0, high = row.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            Box box = row.get(mid);
            if ((long) box.x() + box.w() <= x) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static void visitConnectors(List<Connector> candidates, int left, int top, int right,
                                        int bottom, int width, Consumer<Connector> visitor) {
        int low = 0, high = candidates.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (candidates.get(mid).right(width) <= left) low = mid + 1;
            else high = mid;
        }
        for (int i = low; i < candidates.size(); i++) {
            Connector connector = candidates.get(i);
            if (connector.left(width) >= right) break;
            if (connector.bottom(width) > top && connector.top(width) < bottom) visitor.accept(connector);
        }
    }

    boolean hasAlternatives(PlanTreeNode node) {
        return node.step != null && node.step.hasOrSiblings() && !cycleCache.getOrDefault(node, node.cycle);
    }

    private void cacheCycles(PlanTreeNode root) {
        cycleCache.clear();
        List<PlanTreeNode> ordered = new ArrayList<>();
        ordered.add(root);
        for (int i = 0; i < ordered.size(); i++) ordered.addAll(ordered.get(i).children);
        // 包含折叠子树；布局和渲染共用这一代标记，不因相机移动反复扫描。
        for (int i = ordered.size() - 1; i >= 0; i--) {
            PlanTreeNode node = ordered.get(i);
            boolean cycle = node.cycle;
            for (PlanTreeNode child : node.children) cycle |= cycleCache.get(child);
            cycleCache.put(node, cycle);
        }
    }

    private void indexConnectors() {
        for (Box parent : boxes) {
            PlanTreeNode node = parent.node();
            if (!node.expanded || node.children.isEmpty()) continue;
            Level level = levels.get(parent.y() / LEVEL_GAP);
            int busY = parent.bottom() + (LEVEL_GAP - parent.h()) / 2;
            int minX = parent.connectorX(), maxX = minX;
            boolean anyChild = false;
            for (PlanTreeNode child : node.children) {
                Box box = boxByNode.get(child);
                if (box == null) continue;
                anyChild = true;
                minX = Math.min(minX, box.connectorX());
                maxX = Math.max(maxX, box.connectorX());
                level.verticals.add(new Connector(box.connectorX(), busY, box.connectorX(), box.y()));
            }
            if (!anyChild) continue;
            level.verticals.add(new Connector(parent.connectorX(), parent.bottom(), parent.connectorX(), busY));
            if (maxX > minX) level.horizontals.add(new Connector(minX, busY, maxX, busY));
        }
        for (Level level : levels) {
            level.verticals.sort(Comparator.comparingInt(Connector::x1));
            level.horizontals.sort(Comparator.comparingInt(Connector::x1));
        }
    }

    /** Placed box for a node identity, or null if not currently laid out (e.g. under a collapse). */
    @Nullable
    public Box boxFor(PlanTreeNode node) {
        return boxByNode.get(node);
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

    public static boolean showsLabel(PlanTreeNode node) {
        return PlanUiConfig.showNodeNames() && (node.depth == 0 || node.step != null || PlanUiConfig.showLeafNames());
    }

    private int nodeWidth(PlanTreeNode node) {
        int contentWidth = ITEM_ICON_SIZE;
        if (showsLabel(node)) contentWidth += labelWidth + NODE_PADDING;
        if (node.step != null && !showsLabel(node)) contentWidth += RECIPE_ICON_SIZE + NODE_ICON_GAP;
        if (hasAlternatives(node)) contentWidth += SWITCH_BTN_SLOT;
        return NODE_PADDING * 2 + contentWidth;
    }

    /** Distance from a node's left edge to its item-icon center (recipe icon sits left of it). */
    private int itemCenterOffset(PlanTreeNode node) {
        int offset = NODE_PADDING;
        if (node.step != null && !showsLabel(node)) offset += RECIPE_ICON_SIZE + NODE_ICON_GAP;
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
        Box box = new Box(node, x, y, nodeW, showsLabel(node) ? NODE_HEIGHT : 22, itemCenterX);
        boxes.add(box);
        boxByNode.put(node, box);
        int level = y / LEVEL_GAP;
        while (levels.size() <= level) levels.add(new Level());
        levels.get(level).boxes.add(box);
        totalHeight = Math.max(totalHeight, box.bottom() + (showsLabel(node) ? 0 : 12));

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
