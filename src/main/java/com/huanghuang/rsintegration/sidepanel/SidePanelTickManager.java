package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;

import com.huanghuang.rsintegration.machine.MachineHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Extracts per-tick maintenance from {@link RSSidePanelClient}.
 * Handles animation cleanup, extraction timeout, zeroed-item removal,
 * delta batch flushing, MachineHub animation, and periodic sync.
 */
final class SidePanelTickManager {

    private SidePanelTickManager() {}

    @SuppressWarnings("resource")
    static void onClientTick(TickEvent.ClientTickEvent event) {
        RSSidePanelClient.loadPanelPreferences();
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.phase == TickEvent.Phase.START) return;

        // Auto-close panel when the screen it was opened from is closed
        if (RSSidePanelClient.panelScreenBound && mc.screen == null && RSSidePanelClient.panelVisible) {
            RSSidePanelClient.panelVisible = false;
            RSSidePanelClient.panelScreenBound = false;
            RSSidePanelNetworkHandler.sendCloseRequest();
            return;
        }

        // MachineHub animation (always tick, even when panel is hidden)
        MachineHub.tick();

        if (!RSSidePanelClient.panelVisible) return;

        RSSidePanelClient.tickCounter++;
        RSSidePanelClient.isShifting = Screen.hasShiftDown();
        if (RSSidePanelClient.gridDragging
                && GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE)
            RSSidePanelClient.gridDragging = false;

        int guiScale = (int) mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / guiScale);
        int my = (int) (mc.mouseHandler.ypos() / guiScale);
        RSSidePanelClient.hoveredSideButton = -1;

        if (mc.screen == null) {
            if (RSSidePanelClient.scrolling && !RSSidePanelClient.panelHidden)
                RSSidePanelClient.updateScrollFromMouse(my);
            if (RSSidePanelClient.movingPanel) {
                int sw = mc.getWindow().getGuiScaledWidth();
                int sh = mc.getWindow().getGuiScaledHeight();
                RSSidePanelClient.panelX = Mth.clamp(
                        RSSidePanelClient.moveStartPanelX + (mx - RSSidePanelClient.moveStartMouseX),
                        0, sw - RSSidePanelClient.panelWidth());
                RSSidePanelClient.panelY = Mth.clamp(
                        RSSidePanelClient.moveStartPanelY + (my - RSSidePanelClient.moveStartMouseY),
                        0, sh - RSSidePanelClient.panelHeight());
            }
        }

        // Flush batched deltas once per tick
        if (RSSidePanelClient.deltaBatchDirty) {
            RSSidePanelClient.deltaBatchDirty = false;
            RSSidePanelClient.deltaBatch.clear();
            RSSidePanelClient.displayDirty = true;
        }
        if (RSSidePanelClient.dataModel.isDeltaBatchDirty()) {
            RSSidePanelClient.dataModel.flushDeltaBatch();
        }

        // Clean expired slot animations
        if (!RSSidePanelClient.slotAnims.isEmpty() && RSSidePanelClient.tickCounter % 10 == 0) {
            RSSidePanelClient.slotAnims.values().removeIf(a -> a.expired());
        }

        if (SearchController.searchMode >= 2 && RSSidePanelClient.tickCounter % 5 == 0)
            SearchController.pullJeiFilter();

        // Periodic full sync
        int syncInterval = RSIntegrationConfig.SIDE_PANEL_SYNC_INTERVAL.get();
        if (RSSidePanelClient.tickCounter % syncInterval == 0
                && RSSidePanelClient.networkAvailable && !RSSidePanelClient.panelHidden) {
            RSSidePanelNetworkHandler.sendRequestSync();
        }

        // Timeout stale pending extractions (2s)
        if (!RSSidePanelClient.pendingExtractions.isEmpty() && RSSidePanelClient.tickCounter % 20 == 0) {
            timeoutStaleExtractions();
        }

        // Zeroed items are removed immediately in SyncHandler.onDeltaReceived()
        // (matching RS GridViewImpl.postChange). No delayed cleanup needed.
    }

    private static void timeoutStaleExtractions() {
        long now = System.currentTimeMillis();
        var it = RSSidePanelClient.pendingExtractions.entrySet().iterator();
        while (it.hasNext()) {
            var pe = it.next();
            int extractionTimeout = RSIntegrationConfig.SIDE_PANEL_EXTRACTION_TIMEOUT.get();
            if (now - pe.getValue().createdAt > extractionTimeout) {
                if (!RSSidePanelClient.pendingSyncRetries.remove(pe.getKey())) {
                    RSSidePanelClient.pendingSyncRetries.add(pe.getKey());
                    RSSidePanelNetworkHandler.sendRequestSync();
                    continue;
                }
                RSSidePanelClient.PendingExtraction p = pe.getValue();
                if (p.previousStack.getCount() > 0) {
                    PanelStack ps = RSSidePanelClient.getById(pe.getKey());
                    if (ps != null) {
                        int oldCount = ps.getCount();
                        ps.setCount(p.previousStack.getCount());
                        ps.timestamp = p.timestamp;
                        ps.craftable = p.craftable;
                        RSSidePanelClient.recordSlotAnim(pe.getKey(), p.previousStack.getCount() - oldCount);
                    }
                } else {
                    RSSidePanelClient.removePanel(pe.getKey());
                }
                it.remove();
                RSSidePanelClient.displayDirty = true;
            }
        }
    }
}
