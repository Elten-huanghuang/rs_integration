package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** 像素调色盘；直接换色不重建界面，保留搜索、缩放、材料选择与监控滚动位置。 */
public final class CraftingThemeButton extends Button {
    public static final int SIZE = 18;
    private static final String[] ICON = {
            "   #####   ", " ######### ", "###########", "###########",
            "###########", "########## ", "########   ", " #######   ", "  #####    "
    };
    private final boolean tree;
    private final CraftingUiTheme.Scope scope;
    private final CraftingThemeWheel wheel = new CraftingThemeWheel();
    private boolean open;
    private int highlighted;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;
    private CraftingUiTheme lastTheme;
    private boolean saveFailed;

    public CraftingThemeButton(int x, int y, boolean tree) {
        super(x, y, SIZE, SIZE, Component.empty(), button -> ((CraftingThemeButton) button).toggleWheel(), DEFAULT_NARRATION);
        this.tree = tree;
        this.scope = tree ? CraftingUiTheme.Scope.TREE : CraftingUiTheme.Scope.MONITOR;
        updateState();
    }

    public static Component themeName(CraftingUiTheme theme) {
        return Component.translatable("config.rs_integration.crafting_theme." + theme.name().toLowerCase(Locale.ROOT));
    }

    private void toggleWheel() {
        if (!RSIntegrationConfig.CLIENT_SPEC.isLoaded()) return;
        open = !open;
        highlighted = CraftingUiTheme.current(scope).ordinal();
        lastMouseX = lastMouseY = Integer.MIN_VALUE;
        saveFailed = false;
        lastTheme = null;
        updateState();
    }

    public boolean isWheelOpen() { return open; }
    public void setWheelViewport(int width, int height) { wheel.resize(width, height); }

    private void choose(CraftingUiTheme theme) {
        try {
            if (!CraftingUiTheme.select(scope, theme)) return;
            saveFailed = false;
            open = false;
        } catch (RuntimeException failure) {
            saveFailed = true;
            LogUtils.getLogger().warn("[RSI] 主题保存失败，已恢复原配色", failure);
        }
        lastTheme = null;
        updateState();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) return super.mouseClicked(mouseX, mouseY, button);
        int requestedPage = wheel.layout().pageAt(mouseX, mouseY);
        if (button == 0 && requestedPage >= 0) {
            showPage(requestedPage);
            return true;
        }
        int pageStep = wheel.layout().pageStepAt(mouseX, mouseY);
        if (button == 0 && pageStep != 0) {
            int page = Math.floorMod(highlighted / CraftingThemeWheel.PAGE_SIZE + pageStep, CraftingThemeWheel.PAGE_COUNT);
            showPage(page);
            return true;
        }
        int index = wheel.layout().themeAt(mouseX, mouseY, highlighted / CraftingThemeWheel.PAGE_SIZE);
        if (button == 0 && index >= 0) choose(CraftingUiTheme.values()[index]);
        else { open = false; lastTheme = null; updateState(); }
        return true;
    }

    public boolean wheelScrolled(double delta) {
        if (!open) return false;
        if (delta != 0) move(delta < 0 ? 1 : -1);
        return true;
    }

    private void move(int step) {
        highlighted = Math.floorMod(highlighted + step, CraftingUiTheme.values().length);
    }

    private void showPage(int page) {
        highlighted = Math.min(CraftingUiTheme.values().length - 1, page * CraftingThemeWheel.PAGE_SIZE);
    }

    public boolean wheelKeyPressed(int key) {
        if (!open) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> { open = false; lastTheme = null; updateState(); }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> move(1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> move(-1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> choose(CraftingUiTheme.values()[highlighted]);
            default -> { }
        }
        return true;
    }

    public void renderWheel(GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        if (!open) return;
        setWheelViewport(width, height);
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            int hovered = wheel.layout().themeAt(mouseX, mouseY, highlighted / CraftingThemeWheel.PAGE_SIZE);
            if (hovered >= 0) highlighted = hovered;
            lastMouseX = mouseX; lastMouseY = mouseY;
        }
        wheel.render(graphics, font, width, height, scope, highlighted, CraftingUiTheme.current(scope), saveFailed, mouseX, mouseY);
    }

    private void updateState() {
        active = RSIntegrationConfig.CLIENT_SPEC.isLoaded();
        if (!active) open = false;
        CraftingUiTheme theme = CraftingUiTheme.current(scope);
        if (lastTheme == theme) return;
        lastTheme = theme;
        setMessage(Component.translatable("rsi.theme.current", themeName(theme)));
        Component hint = Component.translatable(tree ? "rsi.theme.tree" : "rsi.theme.monitor").append("\n")
                .append(getMessage()).append("\n").append(Component.translatable("rsi.theme.cycle"));
        if (saveFailed) hint = hint.copy().append("\n").append(Component.translatable("rsi.theme.save_failed"));
        setTooltip(open ? null : Tooltip.create(hint));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateState();
        int background = tree ? (isHoveredOrFocused() ? Color.HOVER : Color.BUTTON).value()
                : isHoveredOrFocused() ? CraftingUiStyle.highlight() : CraftingUiStyle.panel();
        int border = tree ? Color.BORDER.value() : CraftingUiStyle.border();
        graphics.drawManaged(() -> {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, border);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, background);
            int x = getX() + 3, y = getY() + 4;
            for (int row = 0; row < ICON.length; row++) {
                int left = ICON[row].indexOf('#'), right = ICON[row].lastIndexOf('#') + 1;
                graphics.fill(x + left, y + row, x + right, y + row + 1, 0xFF493A2B);
                if (row > 0 && row < ICON.length - 1) {
                    graphics.fill(x + left + 1, y + row, x + right - 1, y + row + 1, 0xFFE7C88E);
                }
            }
            graphics.fill(x + 3, y + 1, x + 5, y + 3, 0xFFD95354);
            graphics.fill(x + 6, y + 1, x + 8, y + 3, 0xFFF1D45C);
            graphics.fill(x + 1, y + 4, x + 3, y + 6, 0xFF5AABCF);
            graphics.fill(x + 4, y + 5, x + 6, y + 7, 0xFF8861BA);
            graphics.fill(x + 8, y + 4, x + 10, y + 6, background);
        });
    }
}
