package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 候选列表的搜索、滚动和键盘导航，不依赖客户端渲染环境。 */
public final class RecipeCandidatePickerModel {
    public static final int HEADER_HEIGHT = 44;
    public static final int FOOTER_HEIGHT = 18;
    private static final int PREFERRED_ROW_HEIGHT = 92;
    public record Candidate(ResourceLocation recipeId, String modTypeId, String name,
                            String machine, String searchText) {}

    private final List<Candidate> candidates;
    private List<Candidate> filtered;
    private int cursor;
    private int firstRow;
    private int visibleRows = 1;

    public RecipeCandidatePickerModel(List<Candidate> candidates, ResourceLocation selected) {
        var unique = new LinkedHashMap<ResourceLocation, Candidate>();
        for (Candidate candidate : candidates) unique.putIfAbsent(candidate.recipeId(), candidate);
        this.candidates = List.copyOf(unique.values());
        this.filtered = this.candidates;
        this.cursor = indexOf(selected);
        revealCursor();
    }

    public void search(String query) {
        Candidate previous = active();
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String text = (candidate.recipeId() + " " + candidate.modTypeId() + " "
                    + candidate.name() + " " + candidate.machine() + " " + candidate.searchText())
                    .toLowerCase(Locale.ROOT);
            boolean match = true;
            for (String term : terms) if (!text.contains(term)) { match = false; break; }
            if (match) matches.add(candidate);
        }
        filtered = List.copyOf(matches);
        cursor = indexOf(previous == null ? null : previous.recipeId());
        firstRow = 0;
        revealCursor();
    }

    public void setVisibleRows(int rows) {
        int next = Math.max(1, rows);
        if (next != visibleRows) {
            visibleRows = next;
            revealCursor();
        }
        firstRow = Math.min(firstRow, maxScroll());
    }

    public void move(int delta) {
        if (filtered.isEmpty()) return;
        cursor = Math.floorMod(cursor + delta, filtered.size());
        revealCursor();
    }

    public void scroll(int delta) {
        firstRow = Math.max(0, Math.min(maxScroll(), firstRow + delta));
    }

    public void hover(int row) {
        if (row >= 0 && row < filtered.size()) cursor = row;
    }

    public Candidate active() { return filtered.isEmpty() ? null : filtered.get(cursor); }
    public List<Candidate> filtered() { return filtered; }
    public int total() { return candidates.size(); }
    public int cursor() { return cursor; }
    public int firstRow() { return firstRow; }
    public int visibleRows() { return visibleRows; }
    public int maxScroll() { return Math.max(0, filtered.size() - visibleRows); }

    private int indexOf(ResourceLocation id) {
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).recipeId().equals(id)) return i;
        return 0;
    }

    private void revealCursor() {
        if (cursor < firstRow) firstRow = cursor;
        if (cursor >= firstRow + visibleRows) firstRow = cursor - visibleRows + 1;
        firstRow = Math.max(0, Math.min(firstRow, maxScroll()));
    }

    public record Bounds(int x, int y, int width, int height, int rowHeight, int rows) {
        /** 主视图与侧栏之间保留间距，绘制与命中区域使用同一个右边界。 */
        public int contentRight() { return Math.max(1, x - 8); }

        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    /** 窄侧栏最多展示四张卡片，并严格限制在配方树可视区域内。 */
    public static Bounds layout(int screenWidth, int screenHeight, int contentTop,
                                int contentBottom) {
        return layout(screenWidth, screenHeight, contentTop, contentBottom, 0);
    }

    /** 面板宽度优先保证 JEI 配方按原尺寸显示，屏幕过窄时才限制到可用宽度。 */
    public static Bounds layout(int screenWidth, int screenHeight, int contentTop,
                                int contentBottom, int preferredWidth) {
        int maxWidth = Math.max(176, Math.min(screenWidth * 45 / 100, 420));
        int width = preferredWidth > 0
                ? Math.max(176, Math.min(maxWidth, preferredWidth))
                : Math.max(1, Math.min(screenWidth * 42 / 100,
                Math.min(232, Math.max(176, screenWidth * 30 / 100))));
        int bottom = Math.max(8, Math.min(screenHeight - 8, contentBottom - 4));
        int y = Math.max(8, Math.min(contentTop, bottom - HEADER_HEIGHT - FOOTER_HEIGHT - 1));
        int bodyHeight = Math.max(1, bottom - y - HEADER_HEIGHT - FOOTER_HEIGHT);
        // 给展开卡片留出完整预览空间；至少保证六个候选行的高度。
        int rows = Math.min(6, Math.max(1, bodyHeight / PREFERRED_ROW_HEIGHT));
        int rowHeight = Math.min(PREFERRED_ROW_HEIGHT, Math.max(1, bodyHeight / rows));
        int height = Math.min(bottom - y,
                HEADER_HEIGHT + rows * rowHeight + FOOTER_HEIGHT);
        return new Bounds(Math.max(0, screenWidth - width - 8), y, width, height,
                rowHeight, rows);
    }
}
