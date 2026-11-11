package com.huanghuang.rsintegration.mods.rs.monitor;

/** 保留三列五行，空间足够时用宽格展示原始像素字号，窄屏折叠步骤栏。 */
public record MonitorLayout(int materialWidth, int cellWidth, int cellHeight, int detailsWidth, int railWidth) {
    public static MonitorLayout forViewport(int width, int height) {
        boolean wide = width >= 600 && height >= 260;
        return new MonitorLayout(wide ? 310 : 254, wide ? 92 : 73, wide ? 35 : 29,
                wide || width >= 540 ? 166 : 0, width >= 374 ? 54 : 30);
    }
    public int width() { return materialWidth + detailsWidth; }
    public int gridHeight() { return (cellHeight + 1) * 5 - 1; }
    public int height() { return gridHeight() + 52; }
    public int footerY() { return height() - 27; }
    public int gridRight() { return 7 + (cellWidth + 1) * 3 - 1; }
}
