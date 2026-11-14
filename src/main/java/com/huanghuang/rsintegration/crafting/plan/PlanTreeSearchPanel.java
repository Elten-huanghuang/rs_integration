package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** 树搜索的候选浮层；只在树或查询变化时筛选，折叠节点也参与搜索。 */
final class PlanTreeSearchPanel {
    private static final int ROW_HEIGHT = 32;
    private static final int HEADER_HEIGHT = 21;
    private static final int FOOTER_HEIGHT = 17;
    private record Entry(PlanTreeNode node, String name, MaterialSearchMatcher.SearchText search,
                         String parent, int position) {}
    private record Pending(PlanTreeNode node, String parent) {}
    private final Font font;
    private final PlanWorkspaceChrome chrome;
    private final Consumer<PlanTreeNode> locate;
    private final List<Entry> entries = new ArrayList<>();
    private List<Entry> matches = List.of();
    private String query = "";
    private boolean open;
    private int selected, firstRow, visibleRows = 1;
    private Rect bounds;

    PlanTreeSearchPanel(Font font, Consumer<PlanTreeNode> locate) {
        this.font = font;
        this.chrome = new PlanWorkspaceChrome(font);
        this.locate = locate;
    }

    void update(PlanTreeNode root) {
        entries.clear();
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(root, ""));
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            PlanTreeNode node = current.node();
            String name = node.displayStack.getHoverName().getString();
            entries.add(new Entry(node, name, MaterialSearchMatcher.prepare(name,
                    BuiltInRegistries.ITEM.getKey(node.displayStack.getItem()).toString()),
                    current.parent(), entries.size() + 1));
            for (int i = node.children.size() - 1; i >= 0; i--) pending.push(new Pending(node.children.get(i), name));
        }
        filter();
        close(); // 新计划不能继续点击上一棵树的旧命中区。
    }

    void search(String value) {
        String next = value.trim();
        if (!query.equals(next)) { query = next; filter(); }
        show();
    }

    private void filter() {
        String normalized = MaterialSearchMatcher.normalizeQuery(query);
        matches = normalized.isEmpty() ? List.of() : entries.stream()
                .filter(entry -> entry.search().matches(normalized)).toList();
        selected = 0;
        firstRow = 0;
        bounds = null;
    }

    void show() { open = !query.isEmpty(); }
    void close() { open = false; bounds = null; }
    boolean isOpen() { return open; }
    boolean contains(double x, double y) { return open && bounds != null && bounds.contains(x, y); }

    void render(GuiGraphics g, Rect anchor, Rect frame, int mouseX, int mouseY) {
        if (!open) return;
        int width = Math.min(Math.max(300, anchor.width()), frame.width() - 8);
        int x = Math.max(frame.x() + 4, Math.min(anchor.x(), frame.right() - width - 4));
        int y = anchor.bottom() + 2;
        visibleRows = Math.min(6, Math.max(1, (frame.bottom() - 4 - y - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT));
        int rows = Math.max(1, Math.min(visibleRows, matches.size()));
        int height = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT;
        y = Math.min(y, frame.bottom() - height - 4);
        bounds = new Rect(x, y, width, height);
        firstRow = Math.max(0, Math.min(firstRow, Math.max(0, matches.size() - visibleRows)));
        g.flush();
        g.pose().pushPose();
        // 候选配方页在侧栏内还有一层浮层，搜索结果应盖住它及其物品图标。
        g.pose().translate(0, 0, 1500);
        try {
            PlanWorkspaceChrome.panel(g, bounds, Color.PANEL, Color.ACTIVE_BORDER);
            chrome.label(g, text("search_results", matches.size()), x + 8, y + 6, width - 16, Color.ACCENT);
            if (matches.isEmpty()) {
                chrome.label(g, text("search_empty"), x + 8, y + HEADER_HEIGHT + 10, width - 16, Color.MUTED);
            }
            for (int row = 0; row < rows && firstRow + row < matches.size(); row++) {
                int index = firstRow + row;
                Entry entry = matches.get(index);
                int top = y + HEADER_HEIGHT + row * ROW_HEIGHT;
                Rect hit = new Rect(x + 3, top, width - 6, ROW_HEIGHT);
                g.fill(hit.x(), top, hit.right(), top + ROW_HEIGHT - 1,
                        (hit.contains(mouseX, mouseY) ? Color.HOVER : index == selected ? Color.SELECTED : Color.SURFACE).value());
                if (index == selected) g.fill(x + 3, top + 2, x + 5, top + ROW_HEIGHT - 3, Color.ACCENT.value());
                InkFluidRenderer.render(g, entry.node().displayStack, x + 9, top + 7);
                String amount = InkFluidRenderer.compactQuantity(entry.node().displayStack, entry.node().displayAmount());
                int amountWidth = Math.min(width / 3, font.width(amount));
                chrome.label(g, entry.name(), x + 31, top + 4, width - 49 - amountWidth, Color.TEXT);
                chrome.label(g, amount, x + width - amountWidth - 12, top + 4, amountWidth, Color.MUTED);
                String context = entry.parent().isEmpty() ? text("search_root")
                        : text("search_parent", entry.position(), entry.parent());
                chrome.label(g, context, x + 31, top + 18, width - 45, Color.MUTED);
            }
            if (matches.size() > visibleRows) {
                int track = rows * ROW_HEIGHT, thumb = Math.max(8, track * visibleRows / matches.size());
                int top = y + HEADER_HEIGHT + (track - thumb) * firstRow / (matches.size() - visibleRows);
                g.fill(bounds.right() - 4, top, bounds.right() - 2, top + thumb, Color.ACCENT.value());
            }
            chrome.label(g, text("search_keys"), x + 8, bounds.bottom() - 12, width - 16, Color.MUTED);
        } finally { g.flush(); g.pose().popPose(); }
    }

    boolean click(double x, double y, int button) {
        if (!contains(x, y)) return false;
        int row = (int) (y - bounds.y() - HEADER_HEIGHT);
        if (button == 0 && row >= 0 && row < visibleRows * ROW_HEIGHT) {
            int index = firstRow + row / ROW_HEIGHT;
            if (index < matches.size()) { selected = index; choose(); }
        }
        return true;
    }

    boolean scroll(double x, double y, double delta) {
        if (!contains(x, y)) return false;
        firstRow = Math.max(0, Math.min(firstRow - (int) Math.signum(delta), Math.max(0, matches.size() - visibleRows)));
        selected = Math.max(firstRow, Math.min(selected, firstRow + visibleRows - 1));
        return true;
    }

    boolean keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!open && matches.size() != 1) show(); else choose();
            return true;
        }
        if (key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) return false;
        boolean wasOpen = open;
        show();
        if (!matches.isEmpty()) {
            if (wasOpen) selected = Math.max(0, Math.min(selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), matches.size() - 1));
            if (selected < firstRow) firstRow = selected;
            if (selected >= firstRow + visibleRows) firstRow = selected - visibleRows + 1;
        }
        return true;
    }

    private void choose() {
        if (matches.isEmpty()) return;
        PlanTreeNode node = matches.get(selected).node();
        close();
        locate.accept(node);
    }

    private static String text(String key, Object... args) {
        return Component.translatable("rsi.plan.workspace." + key, args).getString();
    }
}
