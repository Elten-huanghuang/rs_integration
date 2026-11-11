package com.huanghuang.rsintegration.mods.rs.monitor;

/** 保留三列五行，空间足够时用宽格展示原始像素字号，窄屏折叠步骤栏。 */
public record MonitorLayout(int materialWidth, int cellWidth, int cellHeight, int detailsWidth, int railWidth) {
    public static MonitorLayout forViewport(int width, int height) {
        boolean wide = width >= 600 && height >= 260;
        int materialWidth = wide ? 310 : 254;
        int detailsWidth = wide || width >= 540 ? 166 : 0;
        int railWidth = width >= 374 ? Math.min(96, width - materialWidth - detailsWidth - 28) : 30;
        return new MonitorLayout(materialWidth, wide ? 92 : 73, wide ? 35 : 29, detailsWidth, railWidth);
    }
    public int width() { return materialWidth + detailsWidth; }
    /** 任务栏、主面板和右侧按钮一起居中，防止加宽左栏后越界。 */
    public int mainLeft(int viewportWidth) {
        int leftExtension = railWidth + 4;
        return leftExtension + (viewportWidth - leftExtension - width() - 22) / 2;
    }
    public int gridHeight() { return (cellHeight + 1) * 5 - 1; }
    public int height() { return gridHeight() + 52; }
    public int footerY() { return height() - 27; }
    public int gridRight() { return 7 + (cellWidth + 1) * 3 - 1; }
}
