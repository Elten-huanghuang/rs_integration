package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Config screen for Aetherium Anvil automation settings.
 * Opened by Shift+Right-clicking an anvil with a Tinker Hammer.
 */
public final class AnvilConfigScreen extends Screen {

    private static final int WIDTH = 210;
    private static final int HEIGHT = 150;

    private EditBox tempMinField;
    private EditBox tempMaxField;
    private boolean leverControl;
    private boolean autoRefill;
    private boolean autoHammer;

    public AnvilConfigScreen() {
        super(Component.literal("天华砧 设置"));
        this.leverControl = LeverBinder.isLeverControlEnabled();
        this.autoRefill = LeverBinder.isAutoRefillEnabled();
        this.autoHammer = AetherworksClientSetup.isAutoHammerEnabled();
    }

    @Override
    protected void init() {
        super.init();
        int cx = (this.width - WIDTH) / 2;
        int cy = (this.height - HEIGHT) / 2;

        tempMinField = new EditBox(font, cx + 80, cy + 20, 90, 20, Component.literal("最低温度"));
        tempMinField.setValue(String.valueOf(LeverBinder.getTempMin()));
        addRenderableWidget(tempMinField);

        tempMaxField = new EditBox(font, cx + 80, cy + 46, 90, 20, Component.literal("最高温度"));
        tempMaxField.setValue(String.valueOf(LeverBinder.getTempMax()));
        addRenderableWidget(tempMaxField);

        addRenderableWidget(Button.builder(
                Component.literal(autoHammer ? "自动锤炼: 开" : "自动锤炼: 关"),
                b -> {
                    autoHammer = !autoHammer;
                    b.setMessage(Component.literal(autoHammer ? "自动锤炼: 开" : "自动锤炼: 关"));
                })
                .pos(cx + 10, cy + 74).size(95, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(autoRefill ? "自动补货: 开" : "自动补货: 关"),
                b -> {
                    autoRefill = !autoRefill;
                    b.setMessage(Component.literal(autoRefill ? "自动补货: 开" : "自动补货: 关"));
                })
                .pos(cx + 112, cy + 74).size(90, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(leverControl ? "控温开关: 开" : "控温开关: 关"),
                b -> {
                    leverControl = !leverControl;
                    b.setMessage(Component.literal(leverControl ? "控温开关: 开" : "控温开关: 关"));
                })
                .pos(cx + 10, cy + 98).size(95, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("完成"),
                b -> {
                    saveSettings();
                    Minecraft.getInstance().setScreen(null);
                })
                .pos(cx + 112, cy + 98).size(90, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = (this.width - WIDTH) / 2;
        int cy = (this.height - HEIGHT) / 2;
        graphics.drawCenteredString(font, this.title, cx + WIDTH / 2 + 25, cy + 5, 0xFFFFFF);
        graphics.drawString(font, "最低温度:", cx + 10, cy + 26, 0xAAAAAA);
        graphics.drawString(font, "最高温度:", cx + 10, cy + 52, 0xAAAAAA);
    }

    private void saveSettings() {
        try {
            int min = Integer.parseInt(tempMinField.getValue());
            int max = Integer.parseInt(tempMaxField.getValue());
            LeverBinder.setTempRange(min, max);
        } catch (NumberFormatException ignored) {}
        AetherworksClientSetup.setAutoHammerEnabled(autoHammer);
        LeverBinder.setLeverControlEnabled(leverControl);
        LeverBinder.setAutoRefill(autoRefill);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
