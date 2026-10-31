package com.huanghuang.rsintegration.feature.experience.client;

import com.huanghuang.rsintegration.feature.experience.ExperiencePoints;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.Action;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.StatePacket;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandSettings;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandSettings.Mode;
import com.huanghuang.rsintegration.feature.experience.ResonantExperienceWandItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 静态液态经验主题；沿用原版控件的输入、焦点与键盘操作。 */
public final class ExperienceWandScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 248;
    private static final int TEXT = 0x334337;
    private static final int MUTED = 0x68796C;
    private static final int GREEN = 0x416A37;
    private static final int CYAN = 0x336B66;
    private static final int ERROR = 0xAA4A39;
    private static final ResourceLocation LIQUID =
            new ResourceLocation("rs_integration", "textures/block/experience_still.png");

    private StatePacket state;
    private Mode mode;
    private boolean mending;
    private String targetDraft;
    private String manualDraft;
    private boolean dirty;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int overviewBottom;
    private int targetY;
    private int manualY;
    private int footerY;
    private int ticks;
    private int actionCooldown;
    private EditBox targetField;
    private EditBox manualField;
    private Button stopButton;
    private final List<WandButton> modeButtons = new ArrayList<>();
    private final List<Button> actionButtons = new ArrayList<>();

    public ExperienceWandScreen(StatePacket state) {
        super(text("title"));
        this.state = state;
        mode = state.settings().mode();
        mending = state.settings().mending();
        targetDraft = Integer.toString(state.settings().targetLevel());
        manualDraft = Integer.toString(state.settings().manualLevels());
    }

    public void accept(StatePacket packet) {
        if (state.id().equals(packet.id()) && state.hand() == packet.hand()) {
            state = packet;
            updateButtons();
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, width - 12);
        panelHeight = Math.min(PANEL_HEIGHT, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        overviewBottom = top + Math.max(76, panelHeight - 156);
        int modeY = overviewBottom + 7;
        targetY = modeY + 28;
        manualY = targetY + 34;
        footerY = top + panelHeight - 28;
        modeButtons.clear();
        actionButtons.clear();

        int modeWidth = (panelWidth - 24) / Mode.values().length;
        for (Mode candidate : Mode.values()) {
            int index = candidate.ordinal();
            int buttonX = left + 12 + index * modeWidth;
            int buttonWidth = index == Mode.values().length - 1
                    ? left + panelWidth - 12 - buttonX : modeWidth;
            WandButton button = new WandButton(buttonX, modeY, buttonWidth, 22,
                    modeName(candidate), unused -> {
                mode = candidate;
                dirty = true;
                send(Action.SAVE);
                updateButtons();
            }, GREEN, Icon.NONE);
            button.setTooltip(Tooltip.create(text("mode." + candidate.name().toLowerCase(Locale.ROOT) + ".tip")));
            modeButtons.add(addRenderableWidget(button));
        }

        int inputX = left + 100;
        targetField = numberField(inputX, targetY, panelWidth - 164, targetDraft, "target_label");
        targetField.setTooltip(Tooltip.create(text("range", format(ExperiencePoints.MAX_LEVEL))));
        targetField.setResponder(value -> {
            targetDraft = value;
            dirty = true;
            updateButtons();
        });
        addRenderableWidget(new WandButton(left + panelWidth - 60, targetY, 22, 22,
                text("decrease"), unused -> step(-1), GREEN, Icon.MINUS))
                .setTooltip(Tooltip.create(text("decrease")));
        addRenderableWidget(new WandButton(left + panelWidth - 34, targetY, 22, 22,
                text("increase"), unused -> step(1), GREEN, Icon.PLUS))
                .setTooltip(Tooltip.create(text("increase")));
        manualField = numberField(left + 52, manualY, 56, manualDraft, "manual_label");
        manualField.setTooltip(Tooltip.create(text("manual_range", format(ExperiencePoints.MAX_LEVEL))));
        manualField.setResponder(value -> {
            manualDraft = value;
            dirty = true;
            updateButtons();
        });

        int actionWidth = (panelWidth - 132) / 2;
        action("deposit", Action.STORE_LEVELS, left + 116, manualY, actionWidth, GREEN, Icon.DEPOSIT);
        action("withdraw", Action.TAKE_LEVELS, left + 120 + actionWidth, manualY,
                panelWidth - 132 - actionWidth, CYAN, Icon.WITHDRAW);
        int half = (panelWidth - 28) / 2;
        action("store_all", Action.STORE_ALL, left + 12, manualY + 26, half, GREEN, Icon.DEPOSIT);
        action("take_all", Action.TAKE_ALL, left + 16 + half, manualY + 26,
                panelWidth - 28 - half, CYAN, Icon.WITHDRAW);

        Checkbox checkbox = new Checkbox(left + 12, footerY, panelWidth - 100, 20,
                text("mending"), mending) {
            @Override
            public void onPress() {
                super.onPress();
                mending = selected();
                dirty = true;
                send(Action.SAVE);
            }

            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                int x = getX();
                int y = getY() + 4;
                int edge = isHoveredOrFocused() ? GREEN : 0x8B9E8D;
                graphics.fill(x, y, x + 24, y + 12, 0xFF000000 | edge);
                graphics.fill(x + 1, y + 1, x + 23, y + 11, selected() ? 0xFF5A8248 : 0xFFA4B1A5);
                int thumb = x + (selected() ? 14 : 2);
                graphics.fill(thumb, y + 2, thumb + 8, y + 10, 0xFFF3F6ED);
                drawTrimmed(graphics, getMessage(), x + 31, getY() + 6, getWidth() - 31, TEXT);
            }
        };
        checkbox.setTooltip(Tooltip.create(text("mending.tip")));
        addRenderableWidget(checkbox);
        addRenderableWidget(new WandButton(left + panelWidth - 76, footerY, 64, 20,
                Component.translatable("gui.done"), unused -> onClose(), GREEN, Icon.NONE));
        stopButton = addRenderableWidget(new WandButton(left + panelWidth - 54, top + 5, 18, 18,
                text("stop"), unused -> send(Action.STOP), ERROR, Icon.STOP));
        stopButton.setTooltip(Tooltip.create(text("stop")));
        addRenderableWidget(new WandButton(left + panelWidth - 30, top + 5, 18, 18,
                Component.translatable("gui.done"), unused -> onClose(), GREEN, Icon.CLOSE))
                .setTooltip(Tooltip.create(Component.translatable("gui.done")));
        setInitialFocus(targetField);
        updateButtons();
    }

    private EditBox numberField(int x, int y, int fieldWidth, String value, String label) {
        EditBox field = new EditBox(font, x + 7, y + 7, fieldWidth - 14, 10, text(label));
        field.setBordered(false);
        field.setTextColor(TEXT);
        field.setTextColorUneditable(MUTED);
        field.setMaxLength(9);
        field.setFilter(candidate -> candidate.isEmpty()
                || candidate.chars().allMatch(character -> character >= '0' && character <= '9'));
        field.setValue(value);
        addRenderableWidget(field);
        return field;
    }

    private void action(String label, Action action, int x, int y, int buttonWidth, int accent, Icon icon) {
        var button = new WandButton(x, y, buttonWidth, 22, text(label), unused -> send(action), accent, icon);
        String fullLabel = switch (action) {
            case STORE_LEVELS -> "store_levels";
            case TAKE_LEVELS -> "take_levels";
            default -> label;
        };
        button.setTooltip(Tooltip.create(text(fullLabel)));
        actionButtons.add(addRenderableWidget(button));
    }

    private void step(int direction) {
        int current = parse(targetDraft, 0);
        if (current < 0) current = state.settings().targetLevel();
        int amount = hasControlDown() ? 1000 : hasShiftDown() ? 10 : 1;
        targetField.setValue(Integer.toString(Math.max(0,
                Math.min(ExperiencePoints.MAX_LEVEL, current + direction * amount))));
    }

    private ExperienceWandSettings draft() {
        return new ExperienceWandSettings(mode, parse(targetDraft, 0), mending, parse(manualDraft, 1));
    }

    private boolean valid() {
        return parse(targetDraft, 0) >= 0 && parse(manualDraft, 1) >= 0;
    }

    private void send(Action action) {
        if (minecraft == null || minecraft.player == null) return;
        if (action == Action.REFRESH) {
            ExperienceWandNetwork.send(action, state.hand(), state.id(), state.settings());
            return;
        }
        if (action == Action.STOP) {
            ExperienceWandNetwork.send(action, state.hand(), state.id(), state.settings());
            actionCooldown = 4;
            updateButtons();
            return;
        }
        if (!valid()) return;
        ExperienceWandNetwork.send(action, state.hand(), state.id(), draft());
        dirty = false;
        if (action != Action.SAVE) actionCooldown = 4;
        updateButtons();
    }

    private void updateButtons() {
        boolean valid = valid();
        for (int index = 0; index < modeButtons.size(); index++) {
            WandButton button = modeButtons.get(index);
            Mode candidate = Mode.values()[index];
            button.selected = candidate == mode;
        }
        for (Button button : actionButtons) {
            button.active = valid && actionCooldown == 0 && !state.busy() && state.uncertainMb() == 0L;
        }
        if (targetField != null) targetField.setTextColor(parse(targetDraft, 0) < 0 ? ERROR : TEXT);
        if (manualField != null) manualField.setTextColor(parse(manualDraft, 1) < 0 ? ERROR : TEXT);
        if (stopButton != null) {
            stopButton.visible = state.busy();
            stopButton.active = actionCooldown == 0;
        }
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null) return;
        if (!(minecraft.player.getItemInHand(state.hand()).getItem() instanceof ResonantExperienceWandItem)) {
            onClose();
            return;
        }
        targetField.tick();
        manualField.tick();
        if (actionCooldown > 0) {
            actionCooldown--;
            updateButtons();
        }
        if (++ticks % 20 == 0) send(Action.REFRESH);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left + 3, top + 4, left + panelWidth + 3, top + panelHeight + 4, 0x60000000);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF788A79);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFDDE3DA);
        graphics.fill(left, top, left + panelWidth, top + 28, 0xFF2C3930);
        graphics.fill(left + 1, top, left + panelWidth - 1, top + 1, 0xFF8DA47D);
        graphics.fill(left, top + 27, left + panelWidth, top + 28, 0xFFAAC18C);
        // 仅取液态经验贴图的首帧，界面不播放动画。
        graphics.fill(left + 11, top + 6, left + 27, top + 22, 0xFF18261D);
        graphics.blit(LIQUID, left + 13, top + 8, 12, 12, 0, 0, 16, 16, 16, 512);
        graphics.fill(left + 13, top + 8, left + 25, top + 9, 0x66FFFFFF);
        drawTrimmed(graphics, title, left + 35, top + 10, panelWidth - 96, 0xF0F6E9);

        Component status = state.uncertainMb() > 0L ? text("uncertain", format(state.uncertainMb()))
                : !state.effective() ? text("other_wand")
                : state.busy() ? text("busy")
                : text(state.connected() ? "connected" : "disconnected");
        int statusColor = state.uncertainMb() > 0L ? ERROR
                : !state.effective() || !state.connected() ? 0x8E692E : state.busy() ? CYAN : GREEN;
        Component current = text("level", format(state.playerLevel()));
        Component amounts = text("amounts", state.networkMb() < 0L ? "?" : format(state.networkMb()),
                format(state.bufferedMb()));
        graphics.fill(left, top + 28, left + panelWidth, overviewBottom, 0xFFEAF0E3);
        graphics.fill(left + 12, top + 35, left + 16, top + 39, 0xFF000000 | statusColor);
        drawTrimmed(graphics, status, left + 22, top + 33, panelWidth - 34, statusColor);
        int columnWidth = (panelWidth - 24) / 3;
        int metricY = top + 47 + Math.max(0, overviewBottom - top - 76) / 3;
        metric(graphics, "network_label", state.networkMb() < 0L ? "--" : format(state.networkMb()),
                left + 12, metricY, columnWidth - 10, GREEN);
        metric(graphics, "buffer_label", format(state.bufferedMb()),
                left + 12 + columnWidth, metricY, columnWidth - 10, CYAN);
        metric(graphics, "current_label", format(state.playerLevel()),
                left + 12 + columnWidth * 2, metricY, columnWidth - 10, TEXT);
        for (int i = 1; i < 3; i++) {
            int x = left + 6 + columnWidth * i;
            graphics.fill(x, metricY, x + 1, metricY + 23, 0xFFCCD8C4);
        }
        graphics.fill(left + 12, overviewBottom - 1, left + panelWidth - 12, overviewBottom, 0xFFBCCCB1);
        graphics.drawString(font, text("target_label"), left + 12, targetY + 7, TEXT, false);
        graphics.fill(left + 12, manualY - 7, left + panelWidth - 12, manualY - 6, 0xFFB9C6B6);
        graphics.drawString(font, text("quantity_label"), left + 12, manualY + 7, TEXT, false);
        fieldFrame(graphics, targetField, parse(targetDraft, 0) >= 0);
        fieldFrame(graphics, manualField, parse(manualDraft, 1) >= 0);
        graphics.fill(left, footerY - 5, left + panelWidth, top + panelHeight, 0xFFCFD9CB);
        graphics.fill(left + 12, footerY - 5, left + panelWidth - 12, footerY - 4, 0xFFB7C4B2);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= left + 12 && mouseX < left + panelWidth - 12
                && mouseY >= top + 29 && mouseY < overviewBottom) {
            graphics.renderTooltip(font, List.of(status, amounts, current).stream()
                    .map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private void metric(GuiGraphics graphics, String label, String value, int x, int y, int maxWidth, int color) {
        drawTrimmed(graphics, text(label), x, y, maxWidth, MUTED);
        drawTrimmed(graphics, Component.literal(value), x, y + 14, maxWidth, color);
    }

    private void fieldFrame(GuiGraphics graphics, EditBox field, boolean valid) {
        int x = field.getX() - 7;
        int y = field.getY() - 7;
        int edge = !valid ? ERROR : field.isFocused() ? GREEN : 0x9DAC97;
        graphics.fill(x, y, x + field.getWidth() + 14, y + 22, 0xFF000000 | edge);
        graphics.fill(x + 1, y + 1, x + field.getWidth() + 13, y + 21, 0xFFF5F7F0);
    }

    private void drawTrimmed(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        String value = text.getString();
        if (font.width(value) > maxWidth) {
            value = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
        }
        graphics.drawString(font, value, x, y, color, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 无边框输入框的外壳也可点击，不要求精确点到文字区域。
        for (EditBox field : List.of(targetField, manualField)) {
            if (button == 0 && mouseX >= field.getX() - 7 && mouseX < field.getX() + field.getWidth() + 7
                    && mouseY >= field.getY() - 7 && mouseY < field.getY() + 15) {
                setFocused(field);
                return field.mouseClicked(Math.max(field.getX(), Math.min(mouseX,
                        field.getX() + field.getWidth() - 1)), field.getY() + 2, button);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            if (dirty && valid()) send(Action.SAVE);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (dirty && valid()) send(Action.SAVE);
        super.onClose();
    }

    @Override
    public void removed() {
        if (dirty && valid()) send(Action.SAVE);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int parse(String value, int minimum) {
        try {
            int number = Integer.parseInt(value);
            return number >= minimum && number <= ExperiencePoints.MAX_LEVEL ? number : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("experience.rs_integration." + key, arguments);
    }

    private static Component modeName(Mode mode) {
        return text("mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(amount);
    }

    private enum Icon { NONE, MINUS, PLUS, CLOSE, STOP, DEPOSIT, WITHDRAW }

    private final class WandButton extends Button {
        private final int accent;
        private final Icon icon;
        private boolean selected;

        private WandButton(int x, int y, int width, int height, Component label, OnPress press,
                           int accent, Icon icon) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.accent = accent;
            this.icon = icon;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            boolean header = icon == Icon.CLOSE || icon == Icon.STOP;
            boolean hovered = active && isHoveredOrFocused();
            int ink = !active ? 0x8C978A : selected ? 0xF1F8E8 : header ? 0xDCE8D3 : accent;
            int edge = selected || hovered ? accent : 0xA9B8A1;
            int fill = !active ? 0xD2DACE : selected ? accent : hovered ? 0xFCFFF6 : 0xEEF2E8;
            if (!header) {
                graphics.fill(x, y, x + getWidth(), y + getHeight(), 0xFF000000 | edge);
                graphics.fill(x + 1, y + 1, x + getWidth() - 1, y + getHeight() - 1, 0xFF000000 | fill);
                if (selected) {
                    graphics.fill(x + 3, y + getHeight() - 3, x + getWidth() - 3,
                            y + getHeight() - 2, 0xFFB9DB85);
                } else if (active) {
                    graphics.fill(x + 1, y + 1, x + getWidth() - 1, y + 2, 0xFFFAFCF5);
                }
            } else if (hovered) {
                graphics.fill(x, y, x + getWidth(), y + getHeight(), 0xFF526449);
            }
            boolean onlyIcon = icon != Icon.NONE && icon != Icon.DEPOSIT && icon != Icon.WITHDRAW;
            if (onlyIcon) {
                drawIcon(graphics, x + getWidth() / 2 - 4, y + getHeight() / 2 - 4, ink);
            } else {
                int iconWidth = icon == Icon.NONE ? 0 : 13;
                int labelWidth = Math.min(font.width(getMessage()), getWidth() - 12 - iconWidth);
                int contentX = x + (getWidth() - labelWidth - iconWidth) / 2;
                if (iconWidth > 0) drawIcon(graphics, contentX, y + getHeight() / 2 - 4, ink);
                drawTrimmed(graphics, getMessage(), contentX + iconWidth, y + (getHeight() - 8) / 2,
                        getWidth() - 12 - iconWidth, ink);
            }
            if (isFocused()) {
                graphics.fill(x + 2, y + getHeight() - 2, x + getWidth() - 2,
                        y + getHeight() - 1, 0xFF000000 | ink);
            }
        }

        private void drawIcon(GuiGraphics graphics, int x, int y, int color) {
            int ink = 0xFF000000 | color;
            switch (icon) {
                case MINUS, PLUS -> {
                    graphics.fill(x, y + 3, x + 9, y + 4, ink);
                    if (icon == Icon.PLUS) graphics.fill(x + 4, y - 1, x + 5, y + 8, ink);
                }
                case CLOSE -> {
                    for (int i = 0; i < 7; i++) {
                        graphics.fill(x + i, y + i, x + i + 1, y + i + 1, ink);
                        graphics.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, ink);
                    }
                }
                case STOP -> graphics.fill(x + 1, y + 1, x + 8, y + 8, ink);
                case DEPOSIT, WITHDRAW -> {
                    boolean down = icon == Icon.DEPOSIT;
                    graphics.fill(x + 4, y + 1, x + 5, y + 7, ink);
                    for (int i = 0; i < 3; i++) {
                        int row = y + (down ? 4 + i : 3 - i);
                        graphics.fill(x + 2 + i, row, x + 7 - i, row + 1, ink);
                    }
                    graphics.fill(x + 1, y + (down ? 8 : -1), x + 8, y + (down ? 9 : 0), ink);
                }
                default -> { }
            }
        }
    }
}
