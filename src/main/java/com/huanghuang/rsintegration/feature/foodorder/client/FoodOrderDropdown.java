package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** 菜单选项在屏幕最后绘制，展开时优先处理输入，避免点击穿透到食物列表。 */
final class FoodOrderDropdown extends Button {
    private static final int OPTION_HEIGHT = 18;
    private static final FoodOrderViewMode[] MODES = FoodOrderViewMode.values();
    private final Font font;
    private final Consumer<FoodOrderViewMode> onChange;
    private FoodOrderViewMode mode;
    private boolean expanded;
    private int highlighted;

    FoodOrderDropdown(Font font, int x, int y, int width, int height,
                      FoodOrderViewMode mode, Consumer<FoodOrderViewMode> onChange) {
        super(x, y, width, height, mode.label(), button -> {}, DEFAULT_NARRATION);
        this.font = font;
        this.onChange = onChange;
        setMode(mode);
    }

    void setMode(FoodOrderViewMode mode) {
        this.mode = mode;
        setMessage(mode.label());
        if (!expanded) {
            highlighted = mode.ordinal();
        }
    }

    boolean isExpanded() {
        return expanded;
    }

    @Override
    public void onPress() {
        expanded = !expanded;
        highlighted = mode.ordinal();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            expanded = false;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int border = expanded || isHoveredOrFocused() ? FoodOrderTheme.ACCENT : FoodOrderTheme.CARD_EDGE;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, border);
        graphics.fill(x, y, x + width, y + height, FoodOrderTheme.CARD);
        String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 22));
        graphics.drawString(font, label, x + 7, y + (height - 8) / 2, FoodOrderTheme.TEXT, false);
        for (int row = 0; row < 3; row++) {
            int arrowY = y + height / 2 - 1 + (expanded ? 2 - row : row);
            graphics.fill(x + width - 11 + row, arrowY,
                    x + width - 6 - row, arrowY + 1, FoodOrderTheme.TEXT_DIM);
        }
    }

    void renderPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!expanded) {
            return;
        }
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(0, 0, 400);
            int top = popupY();
            int bottom = top + MODES.length * OPTION_HEIGHT;
            graphics.fill(getX() + 2, top + 2, getX() + width + 2, bottom + 2, 0x45000000);
            graphics.fill(getX() - 1, top - 1, getX() + width + 1, bottom + 1, FoodOrderTheme.CARD_EDGE);
            graphics.fill(getX(), top, getX() + width, bottom, FoodOrderTheme.CARD);
            int hovered = optionAt(mouseX, mouseY);
            for (int index = 0; index < MODES.length; index++) {
                int y = top + index * OPTION_HEIGHT;
                if (index == hovered || (hovered < 0 && index == highlighted)) {
                    graphics.fill(getX(), y, getX() + width, y + OPTION_HEIGHT, FoodOrderTheme.ROW_HOVER);
                }
                if (MODES[index] == mode) {
                    graphics.fill(getX() + 2, y + 4, getX() + 4, y + OPTION_HEIGHT - 4,
                            FoodOrderTheme.ACCENT);
                }
                String label = font.plainSubstrByWidth(MODES[index].label().getString(),
                        Math.max(0, width - 12));
                graphics.drawString(font, label, getX() + 7, y + 5, FoodOrderTheme.TEXT, false);
            }
            graphics.flush();
        } finally {
            pose.popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!expanded) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int option = optionAt(mouseX, mouseY);
        if (button == 0 && option >= 0) {
            select(option);
        } else {
            expanded = false;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!expanded) {
            return false;
        }
        highlighted = Math.floorMod(highlighted - (int) Math.signum(delta), MODES.length);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !visible) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            if (expanded) {
                select(highlighted);
            } else {
                onPress();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            if (!expanded) {
                onPress();
            } else {
                highlighted = Math.floorMod(highlighted + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1),
                        MODES.length);
            }
            return true;
        }
        if (expanded && (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END)) {
            highlighted = keyCode == GLFW.GLFW_KEY_HOME ? 0 : MODES.length - 1;
            return true;
        }
        if (expanded && (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_TAB)) {
            expanded = false;
            return keyCode == GLFW.GLFW_KEY_ESCAPE;
        }
        return false;
    }

    private void select(int index) {
        expanded = false;
        FoodOrderViewMode previous = mode;
        setMode(MODES[index]);
        if (mode != previous) {
            onChange.accept(mode);
        }
    }

    private int popupY() {
        return getY() + height + 2;
    }

    private int optionAt(double mouseX, double mouseY) {
        if (mouseX < getX() || mouseX >= getX() + width || mouseY < popupY()
                || mouseY >= popupY() + MODES.length * OPTION_HEIGHT) {
            return -1;
        }
        return (int) ((mouseY - popupY()) / OPTION_HEIGHT);
    }
}
