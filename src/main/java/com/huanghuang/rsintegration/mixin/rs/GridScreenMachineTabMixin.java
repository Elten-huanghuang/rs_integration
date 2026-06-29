package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubRenderer;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineStatus;
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

/**
 * Injects machine shortcut tabs / Hub button into the RS GridScreen foreground layer.
 * Target: com.refinedmods.refinedstorage.screen.grid.GridScreen
 *
 * <p>renderForeground has the PoseStack translated by (leftPos, topPos),
 * so draw coordinates are RELATIVE to the grid top-left.</p>
 */
@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMachineTabMixin {

    private static final int HUB_BUTTON_W = 18;
    private static final int HUB_BUTTON_H = 18;
    private static final int GRID_W = 174;  // RS grid image width

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderMachineTabs(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        int leftPos = acc.getLeftPos();
        int topPos = acc.getTopPos();
        List<BindingInfo> machines = MachineTabHandler.getVisibleTabs();

        if (machines.isEmpty()) {
            // Hub mode: render a single "Hub" button on the LEFT side
            List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
            if (allMachines.isEmpty()) return;
            if (MachineHub.shouldUseHub(allMachines.size())) {
                // Relative draw coords (pose is already translated by (leftPos, topPos))
                int hubRelX = -HUB_BUTTON_W - 2;
                int sideButtonBottom = 0;
                try {
                    var base = (com.refinedmods.refinedstorage.screen.BaseScreen<?>) (Object) this;
                    var sbs = base.getSideButtons();
                    if (!sbs.isEmpty()) {
                        var last = sbs.get(sbs.size() - 1);
                        sideButtonBottom = (last.getY() - topPos) + last.getHeight();
                    }
                } catch (Exception ignored) {}
                int hubRelY = sideButtonBottom + 4;

                // Hit-test in screen space
                int hubScrX = leftPos + hubRelX;
                int hubScrY = topPos + hubRelY;
                boolean hubHovered = mouseX >= hubScrX && mouseX < hubScrX + HUB_BUTTON_W
                        && mouseY >= hubScrY && mouseY < hubScrY + HUB_BUTTON_H;
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

                // Grid icon (2x2 dots) centered in the 18x18 button
                int iconX = hubRelX + 5;
                int iconY = hubRelY + 5;
                int dotColor = hubActive ? 0xFFAADDEE : 0xFFAAAACC;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        gfx.fill(iconX + c * 4, iconY + r * 4, iconX + c * 4 + 3, iconY + r * 4 + 3, dotColor);
                    }
                }

                // Machine count label
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
            // Individual tabs on the LEFT side, rendered rightward
            int tabRelX = -MachineTabRenderer.getTotalWidth(machines.size()) - 2;
            int tabRelY = 6;
            int maxRelX = GRID_W;

            // Hit-test in screen space
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

    // Hub overlay: injected at TAIL of the OBFUSCATED render() method.
    // GridScreen inherits render from vanilla AbstractContainerScreen, which is
    // named "m_88315_" in bytecode (remap=false must match the bytecode name).
    @Inject(method = "m_88315_", at = @At("TAIL"), remap = false)
    private void rsi$renderHub(GuiGraphics gfx, int mouseX, int mouseY, float partialTick,
                                CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;
        if (!MachineHub.isVisible()) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        MachineHubRenderer.render(gfx, acc.getLeftPos(), acc.getTopPos(),
                acc.getImageWidth(), mouseX, mouseY);
    }
}
