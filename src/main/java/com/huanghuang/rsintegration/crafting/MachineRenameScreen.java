package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.config.CraftingUiColors;
import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.machine.client.MachineDisplayNameResolver;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.MachineRenamePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** 为单条机器绑定设置名称；留空恢复方块原名。 */
final class MachineRenameScreen extends Screen {
    private final Screen parent;
    private final BindingInfo machine;
    private String draft;
    private EditBox nameBox;
    private int panelX;
    private int panelY;
    private int panelWidth;

    MachineRenameScreen(Screen parent, BindingInfo machine) {
        super(Component.translatable("rsi.control_center.rename"));
        this.parent = parent;
        this.machine = machine;
        this.draft = machine.customName();
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(320, width - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - 146) / 2;
        nameBox = new EditBox(font, panelX + 18, panelY + 65,
                Math.max(1, panelWidth - 36), 12, title);
        nameBox.setBordered(false);
        nameBox.setTextColor(CraftingUiStyle.text());
        nameBox.setMaxLength(BindingStorage.MAX_CUSTOM_NAME_LENGTH);
        nameBox.setHint(MachineDisplayNameResolver.defaultDisplayName(machine));
        nameBox.setValue(draft);
        nameBox.setResponder(value -> draft = value);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int buttonWidth = (panelWidth - 30) / 2;
        addRenderableWidget(new ActionButton(panelX + 12, panelY + 112,
                buttonWidth, 22, Component.translatable("gui.cancel"), false,
                button -> onClose()));
        addRenderableWidget(new ActionButton(panelX + 18 + buttonWidth, panelY + 112,
                buttonWidth, 22, Component.translatable("rsi.control_center.rename.save"), true,
                button -> save()));
    }

    @Override
    public void tick() {
        nameBox.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        nameBox.setTextColor(CraftingUiStyle.text());
        renderBackground(graphics);
        CraftingUiStyle.panel(graphics, panelX, panelY, panelWidth, 146);
        graphics.drawString(font, title, panelX + 12, panelY + 14, CraftingUiStyle.text(), false);
        String original = MachineDisplayNameResolver.defaultDisplayName(machine).getString();
        graphics.drawString(font, CraftingUiStyle.fit(font, original, panelWidth - 24),
                panelX + 12, panelY + 36, CraftingUiStyle.muted(), false);
        CraftingUiStyle.surface(graphics, panelX + 12, panelY + 58, panelWidth - 24, 24,
                CraftingUiColors.ROW.argb(), nameBox.isFocused() ? CraftingUiStyle.accent() : CraftingUiStyle.border());
        graphics.drawString(font, Component.translatable("rsi.control_center.rename.hint"),
                panelX + 12, panelY + 91, CraftingUiStyle.muted(), false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void save() {
        if (machine.bindingBookId() != null && machine.endpointId() != null) {
            NetworkHandler.CHANNEL.sendToServer(new MachineRenamePacket(
                    machine.bindingBookId(), machine.endpointId(), draft));
        }
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
