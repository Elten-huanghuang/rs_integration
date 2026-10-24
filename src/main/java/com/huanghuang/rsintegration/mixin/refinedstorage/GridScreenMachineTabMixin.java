package com.huanghuang.rsintegration.mixin.refinedstorage;


import com.huanghuang.rsintegration.network.machine.MachineNetworkHandler;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.client.MachineHub;
import com.huanghuang.rsintegration.feature.sidepanel.RSSidePanelModule;
import com.huanghuang.rsintegration.feature.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.machine.client.MachineTabHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMachineTabMixin {

    private static final int BUTTON_SIZE = 18;
    private static final int BUTTON_GAP = 2;
    private static final ResourceLocation MACHINE_CENTER_ICON_TEX =
            new ResourceLocation("rs_integration", "textures/gui/machine_center_.png");
    private static final ResourceLocation RS_ICONS =
            new ResourceLocation("refinedstorage", "textures/icons.png");

    @Unique
    private boolean rsi$bindingSyncRequested;
    @Unique
    private int rsi$machineCenterRelX;
    @Unique
    private int rsi$machineCenterRelY;
    @Unique
    private boolean rsi$machineCenterHovered;
    @Unique
    private int rsi$resonanceBackpackRelX;
    @Unique
    private int rsi$resonanceBackpackRelY;
    @Unique
    private boolean rsi$resonanceBackpackHovered;
    @Unique
    private ItemStack rsi$resonanceDiskIcon;

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderMachineTabs(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (!(ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_MACHINE_GUI_TABS
                : RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get())) return;

        var screen = (com.refinedmods.refinedstorage.screen.grid.GridScreen) (Object) this;
        // 样板终端必须完整保留 RS 原生编辑器的输入、槽位和保存按钮。
        if (screen.getGrid().getGridType() == GridType.PATTERN) return;

        if (!rsi$bindingSyncRequested) {
            rsi$bindingSyncRequested = true;
            MachineHub.hideImmediate();
            if (RSSidePanelModule.isEnabled()) {
                RSSidePanelNetworkHandler.sendRequestSync();
            } else {
                MachineNetworkHandler.sendBindingSyncRequest();
            }
        }

        int topPos = screen.getGuiTop();

        int sideButtonBottom = 4;
        try {
            var base = (com.refinedmods.refinedstorage.screen.BaseScreen<?>) (Object) this;
            var sbs = base.getSideButtons();
            for (var sideButton : sbs) {
                sideButtonBottom = Math.max(sideButtonBottom,
                        (sideButton.getY() - topPos) + sideButton.getHeight());
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-RS-Mixin] side button layout probe failed", e);
        }

        // ── Machine Center button ──
        rsi$machineCenterRelX = -BUTTON_SIZE - 2;
        rsi$machineCenterRelY = sideButtonBottom + BUTTON_GAP;

        rsi$machineCenterHovered = mouseX >= rsi$machineCenterRelX
                && mouseX < rsi$machineCenterRelX + BUTTON_SIZE
                && mouseY >= rsi$machineCenterRelY
                && mouseY < rsi$machineCenterRelY + BUTTON_SIZE;

        List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
        boolean hasBinding = !allMachines.isEmpty();
        MachineTabHandler.setMachineCenterHovered(rsi$machineCenterHovered && hasBinding);

        if (hasBinding) {
            int buttonTextureY = rsi$machineCenterHovered ? 35 : 16;
            gfx.blit(RS_ICONS, rsi$machineCenterRelX, rsi$machineCenterRelY,
                    238, buttonTextureY, BUTTON_SIZE, BUTTON_SIZE);
            if (rsi$machineCenterHovered) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                gfx.setColor(1.0F, 1.0F, 1.0F, 0.5F);
                gfx.blit(RS_ICONS, rsi$machineCenterRelX, rsi$machineCenterRelY,
                        238, 54, BUTTON_SIZE, BUTTON_SIZE);
                gfx.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableBlend();
            }
            gfx.blit(MACHINE_CENTER_ICON_TEX, rsi$machineCenterRelX + 1,
                    rsi$machineCenterRelY + 1, 0, 0, 16, 16, 16, 16);
            int cnt = allMachines.size();
            String label = cnt > 99 ? "…" : String.valueOf(cnt);
            int labelW = Minecraft.getInstance().font.width(label);
            int bx = rsi$machineCenterRelX + BUTTON_SIZE - labelW - 2;
            int by = rsi$machineCenterRelY + BUTTON_SIZE - 7;
            gfx.fill(bx - 1, by - 1, rsi$machineCenterRelX + BUTTON_SIZE, rsi$machineCenterRelY + BUTTON_SIZE, 0xCC335588);
            gfx.drawString(Minecraft.getInstance().font, label, bx, by, 0xFFFFFF);
        }

        // ── Resonance Backpack button ──
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return;

        rsi$resonanceBackpackRelX = -BUTTON_SIZE - 2;
        rsi$resonanceBackpackRelY = rsi$machineCenterRelY
                + (hasBinding ? BUTTON_SIZE + BUTTON_GAP : 0);

        rsi$resonanceBackpackHovered = mouseX >= rsi$resonanceBackpackRelX
                && mouseX < rsi$resonanceBackpackRelX + BUTTON_SIZE
                && mouseY >= rsi$resonanceBackpackRelY
                && mouseY < rsi$resonanceBackpackRelY + BUTTON_SIZE;

        MachineTabHandler.setResonanceBackpackHovered(rsi$resonanceBackpackHovered);

        int buttonTextureY = rsi$resonanceBackpackHovered ? 35 : 16;
        gfx.blit(RS_ICONS, rsi$resonanceBackpackRelX, rsi$resonanceBackpackRelY,
                238, buttonTextureY, BUTTON_SIZE, BUTTON_SIZE);
        if (rsi$resonanceBackpackHovered) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            gfx.setColor(1.0F, 1.0F, 1.0F, 0.5F);
            gfx.blit(RS_ICONS, rsi$resonanceBackpackRelX, rsi$resonanceBackpackRelY,
                    238, 54, BUTTON_SIZE, BUTTON_SIZE);
            gfx.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }
        if (rsi$resonanceDiskIcon == null && ModItems.RESONANCE_STORAGE_DISK != null) {
            rsi$resonanceDiskIcon = new ItemStack(ModItems.RESONANCE_STORAGE_DISK.get());
        }
        if (rsi$resonanceDiskIcon != null) {
            gfx.renderItem(rsi$resonanceDiskIcon, rsi$resonanceBackpackRelX + 1,
                    rsi$resonanceBackpackRelY + 1);
        }
        if (rsi$resonanceBackpackHovered && !MachineHub.isVisible()) {
            gfx.renderTooltip(Minecraft.getInstance().font,
                    Component.translatable("rsi.resonance_backpack.tooltip_click"), mouseX, mouseY);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"), remap = false)
    private void rsi$onClose(CallbackInfo ci) {
        rsi$bindingSyncRequested = false;
    }

    @Unique
    public int rsi$getMachineCenterButtonX() {
        return rsi$machineCenterRelX;
    }

    @Unique
    public int rsi$getMachineCenterButtonY() {
        return rsi$machineCenterRelY;
    }

    @Unique
    public boolean rsi$isMachineCenterHovered() {
        return rsi$machineCenterHovered;
    }

    @Unique
    public int rsi$getResonanceBackpackButtonX() {
        return rsi$resonanceBackpackRelX;
    }

    @Unique
    public int rsi$getResonanceBackpackButtonY() {
        return rsi$resonanceBackpackRelY;
    }

    @Unique
    public boolean rsi$isResonanceBackpackHovered() {
        return rsi$resonanceBackpackHovered;
    }
}
