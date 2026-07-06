package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractContainerScreenAccessor;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabRenderer;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMachineTabMixin {

    private static final int HUB_BUTTON_W = 18;
    private static final int HUB_BUTTON_H = 18;
    private static final int GRID_W = 174;
    private static boolean rsi$bindingSyncRequested;

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderMachineTabs(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;

        if (!rsi$bindingSyncRequested) {
            rsi$bindingSyncRequested = true;
            MachineHub.hideImmediate();
            RSSidePanelNetworkHandler.sendRequestSync();
        }

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        int leftPos = acc.getLeftPos();
        int topPos = acc.getTopPos();
        List<BindingInfo> machines = MachineTabHandler.getVisibleTabs();
        List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();

        if (machines.isEmpty()) {
            if (allMachines.isEmpty()) return;
            int cnt = allMachines.size();
            if (MachineHub.shouldUseHub(cnt)) {
                int hubRelX = -HUB_BUTTON_W - 2;
                int sideButtonBottom = 0;
                try {
                    var base = (com.refinedmods.refinedstorage.screen.BaseScreen<?>) (Object) this;
                    var sbs = base.getSideButtons();
                    if (!sbs.isEmpty()) {
                        var last = sbs.get(sbs.size() - 1);
                        sideButtonBottom = (last.getY() - topPos) + last.getHeight();
                    }
                } catch (Exception e) { com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug("[RSI-RS-Mixin] side button layout probe failed", e); }
                int hubRelY = sideButtonBottom + 4;

                boolean hubHovered = mouseX >= hubRelX && mouseX < hubRelX + HUB_BUTTON_W
                        && mouseY >= hubRelY && mouseY < hubRelY + HUB_BUTTON_H;
                boolean hubActive = MachineHub.isVisible();
                int hubColor, outlineColor;
                if (hubActive) {
                    hubColor = hubHovered ? 0xFF558899 : 0xFF336677;
                    outlineColor = 0xFF88CCDD;
                } else {
                    hubColor = hubHovered ? 0xFF555588 : 0xFF333355;
                    outlineColor = 0xFF8888CC;
                }
                gfx.fill(hubRelX, hubRelY, hubRelX + HUB_BUTTON_W, hubRelY + HUB_BUTTON_H, hubColor);
                gfx.renderOutline(hubRelX, hubRelY, HUB_BUTTON_W, HUB_BUTTON_H, outlineColor);

                int iconX = hubRelX + 5;
                int iconY = hubRelY + 5;
                int dotColor = hubActive ? 0xFFAADDEE : 0xFFAAAACC;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        gfx.fill(iconX + c * 4, iconY + r * 4, iconX + c * 4 + 3, iconY + r * 4 + 3, dotColor);
                    }
                }

                Font font = Minecraft.getInstance().font;
                String label = allMachines.size() > 99 ? "…" : String.valueOf(allMachines.size());
                int labelW = font.width(label);
                gfx.fill(hubRelX + HUB_BUTTON_W - labelW - 3, hubRelY + HUB_BUTTON_H - 8,
                         hubRelX + HUB_BUTTON_W, hubRelY + HUB_BUTTON_H, 0xCC335588);
                gfx.drawString(font, label, hubRelX + HUB_BUTTON_W - labelW - 2,
                               hubRelY + HUB_BUTTON_H - 8, 0xFFFFFF);
                MachineTabHandler.setHoveredTabIndex(hubHovered ? 0 : -1);
            }
        } else {
            int tabRelX = -MachineTabRenderer.getTotalWidth(machines.size()) - 2;
            int tabRelY = 6;
            int maxRelX = GRID_W;

            int tabScrX = leftPos + tabRelX;
            int tabScrY = topPos + tabRelY;
            int maxScrX = leftPos + maxRelX;
            int hovered = MachineTabRenderer.getHoveredTab(mouseX, mouseY, tabScrX, tabScrY, machines, maxScrX);
            MachineTabHandler.setHoveredTabIndex(hovered);

            MachineStatusCache statusCache = MachineStatusCache.getInstance();
            for (int i = 0; i < machines.size(); i++) {
                int x = tabRelX + i * (MachineTabRenderer.getTabWidth() + 2);
                if (x + MachineTabRenderer.getTabWidth() > maxRelX) break;
                BindingInfo info = machines.get(i);
                MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());
                MachineStatus status = statusCache.get(info);
                MachineTabRenderer.drawTab(gfx, x, tabRelY, info, type, status, i == hovered);
            }
        }
    }

    @Inject(method = "removed", at = @At("HEAD"), remap = false)
    private void rsi$onClose(CallbackInfo ci) {
        rsi$bindingSyncRequested = false;
    }
}
