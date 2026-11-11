package com.huanghuang.rsintegration.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** 有明确抓手的可拖拽滚动条，和鼠标滚轮使用相同的行偏移。 */
public final class ListScrollBar extends AbstractWidget {
    private final ListScrollState state;
    private final Runnable changed;
    private double grab;
    private boolean dragging;

    public ListScrollBar(int x, int y, int width, int height, ListScrollState state, Runnable changed) {
        super(x, y, width, height, Component.translatable("rsi.monitor.scroll"));
        this.state = state;
        this.changed = changed;
    }
    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScrollBarStyle.render(graphics, getX(), getY(), width, height, state.offset(), state.maximum(),
                isHoveredOrFocused() || dragging);
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return state.maximum() > 0 && super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public void onClick(double mouseX, double mouseY) {
        if (state.maximum() == 0) return;
        dragging = true;
        double relative = mouseY - getY() - state.thumbTop(height);
        grab = relative >= 0 && relative < state.thumbHeight(height) ? relative : state.thumbHeight(height) / 2.0;
        moveThumb(mouseY);
    }
    @Override protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
        if (state.maximum() == 0) { dragging = false; return; }
        moveThumb(mouseY);
    }
    private void moveThumb(double mouseY) {
        int previous = state.offset();
        state.drag(mouseY - getY() - grab, height);
        if (previous != state.offset()) changed.run();
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return dragging && super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = dragging && button == 0;
        if (button == 0) dragging = false;
        return handled;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
