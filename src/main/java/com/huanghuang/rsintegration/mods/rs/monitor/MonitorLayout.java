package com.huanghuang.rsintegration.mods.rs.monitor;

/** 保留三列五行，按 JEI 两侧所需空间选择展开或折叠步骤栏。 */
public record MonitorLayout(int materialWidth, int cellWidth, int cellHeight, int detailsWidth, int railWidth) {
    // 四列 JEI 物品加边距和控件，不能只预留原先的 52～64 像素。
    public static final int JEI_SIDE_SPACE = 90;

    public static MonitorLayout forViewport(int width, int height) {
        // 26 为任务栏间隔和右侧按钮，剩余预算才属于三个面板。
        int budget = width - 2 * JEI_SIDE_SPACE - 26;
        if (budget >= 310 + 166 + 96 && height >= 300) {
            return new MonitorLayout(310, 92, 35, 166, 96);
        }
        if (budget >= 254 + 146 + 86) {
            return new MonitorLayout(254, 73, 29, 146, 86);
        }
        if (budget >= 214 + 126 + 30) {
            return new MonitorLayout(214, 60, 29, 126, Math.min(86, budget - 214 - 126));
        }
        // 小视口通过侧按钮查看步骤，材料和任务仍能直接操作。
        int materialWidth = budget >= 254 + 30 ? 254 : budget >= 214 + 30 ? 214 : 184;
        int cellWidth = materialWidth == 254 ? 73 : materialWidth == 214 ? 60 : 50;
        return new MonitorLayout(materialWidth, cellWidth, 29, 0,
                Math.max(30, Math.min(66, budget - materialWidth)));
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
    public int footerButtonWidth() { return Math.min(76, (materialWidth - 22) / 3); }
    public int gridRight() { return 7 + (cellWidth + 1) * 3 - 1; }
}
