package com.huanghuang.rsintegration.client.gui;

/** 按行滚动；数量缩减、拖拽和定位选择都在同一处钳制。 */
public final class ListScrollState {
    private int count, visible, offset;
    public void range(int count, int visible) {
        this.count = Math.max(0, count);
        this.visible = Math.max(1, visible);
        offset(offset);
    }
    public int offset() { return offset; }
    public int maximum() { return Math.max(0, count - visible); }
    public void offset(int value) { offset = Math.max(0, Math.min(maximum(), value)); }
    public void scroll(int rows) { offset(offset + rows); }
    public void reveal(int row) {
        if (row < offset) offset(row);
        else if (row >= offset + visible) offset(row - visible + 1);
    }
    public int thumbHeight(int height) {
        return ScrollBarStyle.thumbHeight(height);
    }
    public int thumbTop(int height) {
        return ScrollBarStyle.thumbTop(height, offset, maximum());
    }
    public void drag(double thumbTop, int height) {
        int travel = height - thumbHeight(height);
        offset(travel <= 0 ? 0 : (int) Math.round(Math.max(0, Math.min(travel, thumbTop)) * maximum() / travel));
    }
}
