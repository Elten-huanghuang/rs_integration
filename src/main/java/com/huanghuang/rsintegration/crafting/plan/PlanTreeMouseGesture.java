package com.huanghuang.rsintegration.crafting.plan;

import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

final class PlanTreeMouseGesture<T> {
    private static final double DRAG_THRESHOLD_SQUARED = 16.0;
    private static final PanDelta NO_MOVEMENT = new PanDelta(0.0, 0.0);

    record PanDelta(double deltaX, double deltaY) {}

    private int activeButton = -1;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private boolean dragging;
    @Nullable
    private T clickTarget;

    boolean begin(double mouseX, double mouseY, int button, @Nullable T target) {
        cancel();
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return false;
        activeButton = button;
        pressX = lastX = mouseX;
        pressY = lastY = mouseY;
        clickTarget = button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE ? target : null;
        return true;
    }

    boolean captures(int button) {
        return activeButton >= 0 && activeButton == button;
    }

    PanDelta drag(double mouseX, double mouseY, int button) {
        if (!captures(button)) return NO_MOVEMENT;
        double previousX = lastX;
        double previousY = lastY;
        lastX = mouseX;
        lastY = mouseY;
        if (!dragging) {
            if (!exceedsDragThreshold(mouseX, mouseY)) return NO_MOVEMENT;
            dragging = true;
            return new PanDelta(mouseX - pressX, mouseY - pressY);
        }
        return new PanDelta(mouseX - previousX, mouseY - previousY);
    }

    @Nullable
    T release(double mouseX, double mouseY, int button, @Nullable T releasedTarget) {
        if (!captures(button)) return null;
        T selected = !dragging && !exceedsDragThreshold(mouseX, mouseY)
                && clickTarget == releasedTarget ? clickTarget : null;
        cancel();
        return selected;
    }

    void cancel() {
        activeButton = -1;
        dragging = false;
        clickTarget = null;
    }

    private boolean exceedsDragThreshold(double mouseX, double mouseY) {
        double distanceX = mouseX - pressX;
        double distanceY = mouseY - pressY;
        return distanceX * distanceX + distanceY * distanceY > DRAG_THRESHOLD_SQUARED;
    }
}
