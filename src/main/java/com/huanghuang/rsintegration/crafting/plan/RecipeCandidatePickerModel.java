package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 候选列表的搜索、滚动和键盘导航，不依赖客户端渲染环境。 */
public final class RecipeCandidatePickerModel {
    public static final int HEADER_HEIGHT = 44;
    public static final int FOOTER_HEIGHT = 16;
    private static final int PREFERRED_ROW_HEIGHT = 82;
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

    /** 窄侧栏最多展示三行，不延伸到下方的执行模式与开始按钮。 */
    public static Bounds layout(int screenWidth, int screenHeight, int contentTop) {
        int width = Math.max(1, Math.min(screenWidth * 40 / 100,
                Math.min(204, Math.max(160, screenWidth * 28 / 100))));
        int bottom = Math.min(screenHeight - 8, Math.max(68, screenHeight - 84));
        int y = Math.max(8, Math.min(contentTop, bottom - 104));
        int bodyHeight = Math.max(1, bottom - y - HEADER_HEIGHT - FOOTER_HEIGHT);
        int rows = Math.min(3, Math.max(1, bodyHeight / PREFERRED_ROW_HEIGHT));
        int rowHeight = Math.min(PREFERRED_ROW_HEIGHT, Math.max(1, bodyHeight / rows));
        int height = HEADER_HEIGHT + rows * rowHeight + FOOTER_HEIGHT;
        return new Bounds(Math.max(0, screenWidth - width - 8), y, width, height,
                rowHeight, rows);
    }
}
