package com.huanghuang.rsintegration.crafting.planning;

/** 按完整提示区域定位，窗口缩放后不遗留屏幕外的按钮或文字。 */
public record PlanningHudLayout(int x, int y, int width, int height, boolean visible) {
    public static PlanningHudLayout place(int screenWidth, int screenHeight, int preferredWidth,
                                   int minWidth, int contentHeight, int margin,
                                   double horizontal, double vertical) {
        int availableWidth = Math.max(0, screenWidth - margin * 2);
        int availableHeight = Math.max(0, screenHeight - margin * 2);
        int width = Math.min(preferredWidth, availableWidth);
        int x = margin + (int) Math.round(Math.max(0, availableWidth - width) * normalized(horizontal));
        int y = margin + (int) Math.round(Math.max(0, availableHeight - contentHeight) * normalized(vertical));
        return new PlanningHudLayout(x, y, width, contentHeight,
                width >= minWidth && contentHeight <= availableHeight);
    }

    private static double normalized(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
