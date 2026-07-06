package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.network.MachineCollectPacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineInsertPacket;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;

import java.util.ArrayList;
import java.util.List;

/** Handles machine tab click events and dispatches GUI open requests. */
public final class MachineTabHandler {

    private static final List<BindingInfo> EMPTY = List.of();
    private static int hoveredTabIndex = -1;
    private static long lastClickTime;
    private static final long CLICK_COOLDOWN_MS = 500; // prevent GUI DDoS

    private MachineTabHandler() {}

    /** Get the list of machines to render as tabs (respects threshold + whitelist config). */
    public static List<BindingInfo> getVisibleTabs() {
        var cache = BindingCache.getInstance();
        if (cache.getMachineCount() == 0) return EMPTY;
        List<BindingInfo> filtered = filterByWhitelist(new ArrayList<>(cache.getAll()));
        if (filtered.isEmpty()) return EMPTY;
        if (MachineHub.shouldUseHub(filtered.size())) return EMPTY;
        return filtered;
    }

    /** Get all bound machines regardless of threshold (for Hub mode), filtered by whitelist. */
    public static List<BindingInfo> getAllMachines() {
        var cache = BindingCache.getInstance();
        if (cache.getMachineCount() == 0) return EMPTY;
        return filterByWhitelist(new ArrayList<>(cache.getAll()));
    }

    private static List<BindingInfo> filterByWhitelist(List<BindingInfo> list) {
        list.removeIf(info -> !com.huanghuang.rsintegration.network.binding.BindingEventHandler
                .supportsGuiByInfo(info));
        return list;
    }

    public static int getHoveredTabIndex() { return hoveredTabIndex; }

    public static void setHoveredTabIndex(int idx) { hoveredTabIndex = idx; }

    /** Handle a click on a machine tab. Sends the OpenBoundMachineGuiPacket to server. */
    public static void onClick(BindingInfo info) {
        if (info == null) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Clicked: {}", info.displayName());
        GuiNavStack.pushCurrent();
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
            new OpenBoundMachineGuiPacket(info.dim(), info.pos(), info.itemKey()));
    }

    /** Send collect-output packet for a Quick-type machine. */
    public static void onCollect(BindingInfo info, boolean toRS) {
        if (info == null) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Collect: {} toRS={}", info.displayName(), toRS);
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
            new MachineCollectPacket(info.dim(), info.pos(), toRS));
    }

    /** Send insert-item packet for a Quick-type machine. */
    public static void onInsert(BindingInfo info, MachineSlotType slot) {
        if (info == null) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Insert: {} slot={}", info.displayName(), slot);
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
            new MachineInsertPacket(info.dim(), info.pos(), slot));
    }

    private static boolean checkCooldown() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return false;
        lastClickTime = now;
        return true;
    }
}
