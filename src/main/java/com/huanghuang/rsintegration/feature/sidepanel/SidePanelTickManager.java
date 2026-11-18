package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Extracts per-tick maintenance from {@link RSSidePanelClient}.
 * Handles animation cleanup, extraction timeout, zeroed-item removal,
 * delta batch flushing and periodic sync.
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

        if (SearchController.searchMode >= 2 && RSSidePanelClient.tickCounter % 5 == 0)
            SearchController.pullJeiFilter();

        // Periodic full sync
        int syncInterval = SidePanelSyncPolicy.requestInterval(
                RSIntegrationConfig.SIDE_PANEL_SYNC_INTERVAL.get(),
                RSSidePanelClient.dataModel.networkAvailable());
        boolean confirmation = RSSidePanelClient.dataModel.tick(RSSidePanelClient.tickCounter,
                System.currentTimeMillis(), RSIntegrationConfig.SIDE_PANEL_EXTRACTION_TIMEOUT.get());
        if (confirmation || (RSSidePanelClient.tickCounter % syncInterval == 0 && !RSSidePanelClient.panelHidden)) {
            RSSidePanelNetworkHandler.sendRequestSync();
        }
    }

}
