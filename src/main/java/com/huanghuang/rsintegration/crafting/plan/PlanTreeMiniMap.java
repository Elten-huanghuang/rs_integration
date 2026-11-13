package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** 整树缩略导航：真实节点、连线、当前视野和点击定位均来自同一布局。 */
public final class PlanTreeMiniMap {
    private Rect bounds;
    private double scale, originX, originY;
    private boolean dragging;
    private PlanTreeLayout cachedLayout;
    private long cachedRevision = -1;
    private Rect cachedBounds;
    private List<Rect> connectors = List.of(), nodes = List.of();

    public void render(GuiGraphics g, Font font, PlanTreeLayout layout, Rect viewport,
                       double panX, double panY, double zoom, PlanTreeNode selected) {
        if (viewport.width() < 180 || viewport.height() < 100) { bounds = null; return; }
        int width = Math.min(PlanUiConfig.minimapWidth(), viewport.width() / 3);
        int height = Math.min(PlanUiConfig.minimapHeight(), viewport.height() / 3);
        bounds = new Rect(viewport.right() - width - 6, viewport.bottom() - height - 6, width, height);
        // 主树的原生物品绘制会抬高 Z；导航需要完整盖住下面的物品和文字。
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        try {
            renderContents(g, font, layout, viewport, panX, panY, zoom, selected);
        } finally { g.flush(); g.pose().popPose(); }
    }

    private void renderContents(GuiGraphics g, Font font, PlanTreeLayout layout, Rect viewport,
                                double panX, double panY, double zoom, PlanTreeNode selected) {
        int width = bounds.width(), height = bounds.height();
        PlanWorkspaceChrome.panel(g, bounds, Color.PANEL, Color.BORDER);
        g.drawString(font, Component.translatable("rsi.plan.workspace.minimap"), bounds.x() + 5,
                bounds.y() + 4, Color.MUTED.value(), false);
        scale = Math.min((width - 12.0) / Math.max(1, layout.totalWidth()), (height - 22.0) / Math.max(1, layout.totalHeight() + 18));
        originX = bounds.x() + (bounds.width() - layout.totalWidth() * scale) / 2;
        originY = bounds.y() + 17;
        if (cachedLayout != layout || cachedRevision != layout.revision() || !bounds.equals(cachedBounds)) {
            cacheProjection(layout);
            cachedLayout = layout;
            cachedRevision = layout.revision();
            cachedBounds = bounds;
        }
        g.enableScissor(bounds.x() + 2, bounds.y() + 15, bounds.right() - 2, bounds.bottom() - 2);
        try {
            g.drawManaged(() -> {
                for (Rect rect : connectors) fill(g, rect, Color.CONNECTOR_PENDING);
                for (Rect rect : nodes) fill(g, rect, Color.READY);
                PlanTreeLayout.Box selectedBox = selected == null ? null : layout.boxFor(selected);
                if (selectedBox != null) fill(g, projectedNode(selectedBox), Color.ACCENT);
            });
            int left = sx((viewport.x() - panX) / zoom), top = sy((viewport.y() - panY) / zoom);
            int right = sx((viewport.right() - panX) / zoom), bottom = sy((viewport.bottom() - panY) / zoom);
            left = Math.max(bounds.x() + 3, Math.min(left, bounds.right() - 4));
            right = Math.max(left + 1, Math.min(right, bounds.right() - 3));
            top = Math.max(bounds.y() + 16, Math.min(top, bounds.bottom() - 4));
            bottom = Math.max(top + 1, Math.min(bottom, bounds.bottom() - 3));
            g.renderOutline(left, top, Math.max(2, right - left), Math.max(2, bottom - top), Color.REFERENCE.value());
        } finally { g.disableScissor(); }
    }

    /** 只在布局/尺寸变化时投影；合并同色像素，静止和平移时不再遍历整树或重复叠画。 */
    private void cacheProjection(PlanTreeLayout layout) {
        BitSet[] edgePixels = pixels(), nodePixels = pixels();
        for (PlanTreeLayout.Box box : layout.boxes()) {
            for (PlanTreeNode child : box.node().children) {
                PlanTreeLayout.Box target = layout.boxFor(child);
                if (target == null) continue;
                int x1 = sx(box.connectorX()), x2 = sx(target.connectorX());
                int y1 = sy(box.bottom()), y2 = sy(target.y()), middle = (y1 + y2) / 2;
                paint(edgePixels, new Rect(x1, y1, 1, middle + 1 - y1));
                paint(edgePixels, new Rect(Math.min(x1, x2), middle, Math.abs(x2 - x1) + 1, 1));
                paint(edgePixels, new Rect(x2, middle, 1, y2 - middle));
            }
            paint(nodePixels, projectedNode(box));
        }
        connectors = merge(edgePixels);
        nodes = merge(nodePixels);
    }

    private Rect projectedNode(PlanTreeLayout.Box box) {
        int x = sx(box.x()), y = sy(box.y());
        return new Rect(x, y, Math.max(2, sx(box.x() + box.w()) - x), Math.max(2, sy(box.bottom()) - y));
    }

    private BitSet[] pixels() {
        BitSet[] rows = new BitSet[bounds.height()];
        for (int y = 0; y < rows.length; y++) rows[y] = new BitSet(bounds.width());
        return rows;
    }

    private void paint(BitSet[] pixels, Rect rect) {
        int left = Math.max(2, rect.x() - bounds.x()), right = Math.min(bounds.width() - 2, rect.right() - bounds.x());
        if (right <= left) return;
        for (int y = Math.max(15, rect.y() - bounds.y()); y < Math.min(pixels.length - 2, rect.bottom() - bounds.y()); y++) {
            pixels[y].set(left, right);
        }
    }

    private List<Rect> merge(BitSet[] pixels) {
        List<Rect> result = new ArrayList<>();
        Map<Long, Integer> previous = Map.of();
        for (int y = 0; y < pixels.length; y++) {
            Map<Long, Integer> current = new HashMap<>();
            for (int x = pixels[y].nextSetBit(0); x >= 0; ) {
                int right = pixels[y].nextClearBit(x);
                long span = ((long) x << 32) | right;
                Integer index = previous.get(span);
                if (index == null) {
                    index = result.size();
                    result.add(new Rect(bounds.x() + x, bounds.y() + y, right - x, 1));
                } else {
                    Rect old = result.get(index);
                    result.set(index, new Rect(old.x(), old.y(), old.width(), old.height() + 1));
                }
                current.put(span, index);
                x = pixels[y].nextSetBit(right);
            }
            previous = current;
        }
        return List.copyOf(result);
    }

    private static void fill(GuiGraphics g, Rect rect, Color color) {
        g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color.value());
    }

    public boolean contains(double x, double y) { return bounds != null && bounds.contains(x, y); }
    public boolean click(double x, double y, int button, BiConsumer<Double, Double> center) {
        if (!contains(x, y)) return false;
        if (button == 0) { dragging = true; center(x, y, center); }
        return true;
    }
    public boolean drag(double x, double y, BiConsumer<Double, Double> center) {
        if (!dragging || bounds == null) return false;
        center(x, y, center);
        return true;
    }
    public void release() { dragging = false; }
    private void center(double x, double y, BiConsumer<Double, Double> callback) {
        callback.accept((x - originX) / scale, (y - originY) / scale);
    }
    private int sx(double x) { return (int) Math.round(originX + x * scale); }
    private int sy(double y) { return (int) Math.round(originY + y * scale); }
}
