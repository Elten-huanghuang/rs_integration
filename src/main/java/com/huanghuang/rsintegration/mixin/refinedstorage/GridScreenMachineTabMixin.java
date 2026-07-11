package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractContainerScreenAccessor;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMachineTabMixin {

    private static final int BUTTON_SIZE = 18;
    private static final ResourceLocation MACHINE_CENTER_TEX =
            new ResourceLocation("rs_integration", "textures/gui/machine_center_sidebutton.png");
    private static final ResourceLocation MACHINE_CENTER_HOVER_TEX =
            new ResourceLocation("rs_integration", "textures/gui/machine_center_sidebutton_hover.png");
    private static final ResourceLocation RESONANCE_BACKPACK_TEX =
            new ResourceLocation("rs_integration", "textures/gui/resonance_backpack_sidebutton.png");
    private static final ResourceLocation RESONANCE_BACKPACK_HOVER_TEX =
            new ResourceLocation("rs_integration", "textures/gui/resonance_backpack_sidebutton_hover.png");

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

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderMachineTabs(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (!(ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_MACHINE_GUI_TABS
                : RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get())) return;

        if (!rsi$bindingSyncRequested) {
            rsi$bindingSyncRequested = true;
            MachineHub.hideImmediate();
            RSSidePanelNetworkHandler.sendRequestSync();
        }

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        int topPos = acc.getTopPos();

        int sideButtonBottom = 0;
        try {
            var base = (com.refinedmods.refinedstorage.screen.BaseScreen<?>) (Object) this;
            var sbs = base.getSideButtons();
            if (!sbs.isEmpty()) {
                var last = sbs.get(sbs.size() - 1);
                sideButtonBottom = (last.getY() - topPos) + last.getHeight();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-RS-Mixin] side button layout probe failed", e);
        }

        // ── Machine Center button ──
        rsi$machineCenterRelX = -BUTTON_SIZE - 2;
        rsi$machineCenterRelY = sideButtonBottom + 4;

        rsi$machineCenterHovered = mouseX >= rsi$machineCenterRelX
                && mouseX < rsi$machineCenterRelX + BUTTON_SIZE
                && mouseY >= rsi$machineCenterRelY
                && mouseY < rsi$machineCenterRelY + BUTTON_SIZE;

        boolean hasBinding = !MachineTabHandler.getAllMachines().isEmpty();
        MachineTabHandler.setMachineCenterHovered(rsi$machineCenterHovered && hasBinding);

        ResourceLocation mcTex = rsi$machineCenterHovered ? MACHINE_CENTER_HOVER_TEX : MACHINE_CENTER_TEX;
        RenderSystem.setShaderTexture(0, mcTex);
        gfx.blit(mcTex, rsi$machineCenterRelX, rsi$machineCenterRelY, 0, 0, BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE);

        List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
        if (!allMachines.isEmpty()) {
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
        rsi$resonanceBackpackRelY = rsi$machineCenterRelY + BUTTON_SIZE + 2;

        rsi$resonanceBackpackHovered = mouseX >= rsi$resonanceBackpackRelX
                && mouseX < rsi$resonanceBackpackRelX + BUTTON_SIZE
                && mouseY >= rsi$resonanceBackpackRelY
                && mouseY < rsi$resonanceBackpackRelY + BUTTON_SIZE;

        MachineTabHandler.setResonanceBackpackHovered(rsi$resonanceBackpackHovered);

        ResourceLocation rbTex = rsi$resonanceBackpackHovered ? RESONANCE_BACKPACK_HOVER_TEX : RESONANCE_BACKPACK_TEX;
        RenderSystem.setShaderTexture(0, rbTex);
        gfx.blit(rbTex, rsi$resonanceBackpackRelX, rsi$resonanceBackpackRelY, 0, 0, BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE);
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
