package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** 固定的工作区外框与按钮，全部使用原版批次绘制并保留明确的命中区域。 */
public final class PlanWorkspaceChrome {
    private record Action(Rect bounds, Runnable callback) {}
    private final Font font;
    private final List<Action> actions = new ArrayList<>();

    public PlanWorkspaceChrome(Font font) { this.font = font; }
    public void resetActions() { actions.clear(); }

    public void begin(GuiGraphics g, PlanWorkspaceLayout layout) {
        actions.clear();
        g.fill(0, 0, layout.frame().right() + layout.frame().x(),
                layout.frame().bottom() + layout.frame().y(), Color.SHADOW.value());
        panel(g, layout.frame(), Color.FRAME, Color.NODE_BORDER);
        g.fill(layout.frame().x() + 1, layout.frame().y() + 1, layout.frame().right() - 1,
                layout.header().bottom() + 2, Color.HEADER.value());
        g.fill(layout.header().x(), layout.header().bottom() + 1,
                layout.header().right(), layout.header().bottom() + 2, Color.BORDER.value());
        panel(g, layout.summary(), Color.HEADER, Color.BORDER);
        g.fill(layout.footer().x(), layout.footer().y(), layout.footer().right(),
                layout.footer().y() + 1, Color.BORDER.value());
    }

    public static void panel(GuiGraphics g, Rect bounds, Color fill, Color border) {
        // 1.20.1 的 fill 在非托管模式下逐次 flush；两层圆角统一提交。
        g.drawManaged(() -> {
            PlanUiDraw.rounded(g, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3, border.value());
            PlanUiDraw.rounded(g, bounds.x() + 1, bounds.y() + 1, bounds.width() - 2,
                    bounds.height() - 2, 2, fill.value());
        });
    }

    public void button(GuiGraphics g, Rect bounds, String label, boolean selected, boolean enabled,
                       int mouseX, int mouseY, Runnable callback) {
        panel(g, bounds, selected ? Color.SELECTED : enabled && bounds.contains(mouseX, mouseY)
                ? Color.HOVER : Color.BUTTON, selected ? Color.ACTIVE_BORDER : Color.BORDER);
        String visible = fit(label, bounds.width() - 6);
        g.drawString(font, visible, bounds.x() + (bounds.width() - font.width(visible)) / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2,
                (enabled ? selected ? Color.ACCENT : Color.TEXT : Color.DISABLED).value(), false);
        if (enabled) actions.add(new Action(bounds, callback));
    }

    public void heading(GuiGraphics g, String value, int x, int y, int width, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, Component.literal(fit(value, (int) (width / scale))).withStyle(ChatFormatting.BOLD),
                0, 0, Color.TEXT.value(), false);
        g.pose().popPose();
    }

    public void pill(GuiGraphics g, Rect bounds, String label, Color color) {
        PlanUiDraw.rounded(g, bounds.x(), bounds.y(), bounds.width(), bounds.height(), bounds.height() / 2f,
                (color == Color.WARNING ? Color.WARNING_BORDER : Color.ACTIVE_BORDER).value());
        PlanUiDraw.rounded(g, bounds.x() + 1, bounds.y() + 1, bounds.width() - 2, bounds.height() - 2,
                bounds.height() / 2f - 1, (color == Color.WARNING ? Color.WARNING_SURFACE : Color.SELECTED).value());
        label(g, label, bounds.x() + 7, bounds.y() + (bounds.height() - font.lineHeight) / 2, bounds.width() - 14, color);
    }

    public void statusButton(GuiGraphics g, Rect bounds, String label, Color color,
                             int mouseX, int mouseY, Runnable callback) {
        boolean hover = bounds.contains(mouseX, mouseY);
        panel(g, bounds, hover ? Color.HOVER : color == Color.WARNING ? Color.WARNING_SURFACE : Color.SELECTED,
                hover ? Color.ACTIVE_BORDER : color == Color.WARNING ? Color.WARNING_BORDER : Color.BORDER);
        label(g, label, bounds.x() + 6, bounds.y() + (bounds.height() - font.lineHeight) / 2,
                bounds.width() - 12, color);
        actions.add(new Action(bounds, callback));
    }

    public void label(GuiGraphics g, String value, int x, int y, int width, Color color) {
        g.drawString(font, fit(value, width), x, y, color.value(), false);
    }

    public boolean click(double x, double y, int button) {
        if (button != 0) return false;
        List<Action> snapshot = List.copyOf(actions);
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            Action action = snapshot.get(index);
            if (action.bounds().contains(x, y)) { action.callback().run(); return true; }
        }
        return false;
    }

    public String fit(String text, int width) {
        if (width <= 0) return "";
        return font.width(text) <= width ? text
                : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
