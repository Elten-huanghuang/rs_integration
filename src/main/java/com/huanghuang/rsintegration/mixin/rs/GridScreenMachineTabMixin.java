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
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects machine shortcut tabs / Hub button into the RS GridScreen foreground layer.
 * Target: com.refinedmods.refinedstorage.screen.grid.GridScreen
 */
@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMachineTabMixin {

    private static final int HUB_BUTTON_W = 18;
    private static final int HUB_BUTTON_H = 18;

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderMachineTabs(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        int leftPos = acc.getLeftPos();
        int topPos = acc.getTopPos();
        List<BindingInfo> machines = MachineTabHandler.getVisibleTabs();
        int tabX = leftPos - MachineTabRenderer.getTotalWidth(machines.size()) - 2;
        int tabY = topPos + 6;
        int maxX = leftPos;

        if (machines.isEmpty()) {
            // Hub mode: render a single "Hub" button instead of individual tabs.
            // The Hub button is placed on the LEFT side, below the RS side-buttons
            // (sort, size, search mode, etc.) so it reads as another function toggle.
            List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
            if (allMachines.isEmpty()) return;
            if (MachineHub.shouldUseHub(allMachines.size())) {
                // Left-side X: same column as RS side-buttons (leftPos - WIDTH - 2)
                int hubX = leftPos - HUB_BUTTON_W - 2;

                // Y: below the last RS side-button, or topPos + 6 if none
                int sideButtonBottom = 0;
                try {
                    var base = (com.refinedmods.refinedstorage.screen.BaseScreen<?>) (Object) this;
                    var sbs = base.getSideButtons();
                    if (!sbs.isEmpty()) {
                        var last = sbs.get(sbs.size() - 1);
                        sideButtonBottom = (last.getY() - topPos) + last.getHeight();
                    }
                } catch (Exception ignored) {}
                int hubY = topPos + sideButtonBottom + 4; // 4 px gap

                boolean hubHovered = mouseX >= hubX && mouseX < hubX + HUB_BUTTON_W
                        && mouseY >= hubY && mouseY < hubY + HUB_BUTTON_H;
                boolean hubActive = MachineHub.isVisible();
                int hubColor, outlineColor;
                if (hubActive) {
                    hubColor = hubHovered ? 0xFF558899 : 0xFF336677;
                    outlineColor = 0xFF88CCDD;
                } else {
                    hubColor = hubHovered ? 0xFF555588 : 0xFF333355;
                    outlineColor = 0xFF8888CC;
                }
                gfx.fill(hubX, hubY, hubX + HUB_BUTTON_W, hubY + HUB_BUTTON_H, hubColor);
                gfx.renderOutline(hubX, hubY, HUB_BUTTON_W, HUB_BUTTON_H, outlineColor);

                // Grid icon (3×2 dots) centered in the 18×18 button
                int iconX = hubX + 4;
                int iconY = hubY + 4;
                int dotColor = hubActive ? 0xFFAADDEE : 0xFFAAAACC;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        gfx.fill(iconX + c * 4, iconY + r * 4, iconX + c * 4 + 3, iconY + r * 4 + 3, dotColor);
                    }
                }

                // Machine count label at bottom-right of the button
                Font font = Minecraft.getInstance().font;
                String label = allMachines.size() > 99 ? "…" : String.valueOf(allMachines.size());
                int labelW = font.width(label);
                gfx.fill(hubX + HUB_BUTTON_W - labelW - 3, hubY + HUB_BUTTON_H - 8,
                         hubX + HUB_BUTTON_W, hubY + HUB_BUTTON_H, 0xCC335588);
                gfx.drawString(font, label, hubX + HUB_BUTTON_W - labelW - 2,
                               hubY + HUB_BUTTON_H - 8, 0xFFFFFF);
                MachineTabHandler.setHoveredTabIndex(hubHovered ? 0 : -1);
            }
        } else {
            int hovered = MachineTabRenderer.getHoveredTab(mouseX, mouseY, tabX, tabY, machines, maxX);
            MachineTabHandler.setHoveredTabIndex(hovered);

            MachineStatusCache statusCache = MachineStatusCache.getInstance();
            for (int i = 0; i < machines.size(); i++) {
                int x = tabX + i * (MachineTabRenderer.getTabWidth() + 2);
                if (x + MachineTabRenderer.getTabWidth() > maxX) break;
                BindingInfo info = machines.get(i);
                MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());
                MachineStatus status = statusCache.get(info);
                MachineTabRenderer.drawTab(gfx, x, tabY, info, type, status, i == hovered);
            }
        }

        // Hub overlay is rendered in rsi$renderHub — a separate @Inject on render()
        // at TAIL, because renderForeground may have scissoring that clips
        // elements drawn outside the container bounds.
    }

    // ── Hub overlay: injected at TAIL of render() to avoid scissoring ──────

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void rsi$renderHub(GuiGraphics gfx, int mouseX, int mouseY, float partialTick,
                                CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;
        if (!MachineHub.isVisible()) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        MachineHubRenderer.render(gfx, acc.getLeftPos(), acc.getTopPos(),
                acc.getImageWidth(), mouseX, mouseY);
    }
}
