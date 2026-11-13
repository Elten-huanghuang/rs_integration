package com.huanghuang.rsintegration.crafting.plan;

/** 整个计划工作区共用的几何布局，绘制与点击使用同一份边界。 */
public record PlanWorkspaceLayout(Rect frame, Rect header, Rect summary, Rect toolbar,
                                  Rect content, Rect footer, Rect tree, Rect inspector,
                                  Rect confirm, Rect cancel, boolean compact) {
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double x, double y) {
            return x >= this.x && x < right() && y >= this.y && y < bottom();
        }
    }

    public static PlanWorkspaceLayout create(int width, int height, boolean inspectorOpen, int preferredWidth) {
        boolean compact = width < 720 || height < 360;
        int margin = compact ? 6 : 12, gap = compact ? 4 : 8;
        int inner = Math.max(1, width - margin * 2);
        Rect frame = new Rect(margin, margin, inner, Math.max(1, height - margin * 2));
        Rect header = new Rect(margin + (compact ? 6 : 8), margin + 4, inner - 12, compact ? 24 : 36);
        Rect summary = new Rect(margin + (compact ? 6 : 8), header.bottom() + gap, inner - 12, compact ? 28 : 46);
        Rect toolbar = new Rect(summary.x(), summary.bottom() + gap, summary.width(), width < 720 ? 38 : 20);
        int footerHeight = width < 720 ? 48 : 42;
        Rect footer = new Rect(summary.x(), height - margin - footerHeight, summary.width(), footerHeight);
        Rect content = new Rect(summary.x(), toolbar.bottom() + gap, summary.width(),
                Math.max(1, footer.y() - gap - toolbar.bottom() - gap));
        int inspectorWidth = Math.max(1, Math.min(preferredWidth, content.width() * 34 / 100));
        // GUI 宽度很小时侧栏仍需容纳三页签；树保留至少半个内容区。
        if (compact) inspectorWidth = Math.min(Math.max(160, inspectorWidth), content.width() / 2);
        // 小 GUI 的右侧抽屉向上延伸，为候选卡片和条件列表保留滚动空间。
        int inspectorTop = content.height() < 160 ? summary.y() : toolbar.y();
        Rect inspector = new Rect(content.right() - inspectorWidth, inspectorTop, inspectorWidth,
                footer.y() - gap - inspectorTop);
        Rect tree = inspectorOpen ? new Rect(content.x(), content.y(),
                Math.max(1, content.width() - inspectorWidth - gap), content.height()) : content;
        int cancelWidth = compact ? 44 : 72, confirmWidth = compact ? 88 : 150;
        Rect cancel = new Rect(footer.right() - cancelWidth, footer.bottom() - (compact ? 22 : 31), cancelWidth, compact ? 20 : 24);
        Rect confirm = new Rect(cancel.x() - gap - confirmWidth, cancel.y(), confirmWidth, cancel.height());
        return new PlanWorkspaceLayout(frame, header, summary, toolbar, content, footer, tree,
                inspector, confirm, cancel, compact);
    }
}
