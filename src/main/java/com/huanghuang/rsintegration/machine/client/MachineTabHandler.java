package com.huanghuang.rsintegration.machine.client;



import com.huanghuang.rsintegration.network.machine.MachineNetworkHandler;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.input.GuiNavStack;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.feature.resonance.backpack.OpenResonanceBackpackPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsBindingSyncRequestPacket;
import com.huanghuang.rsintegration.crafting.MachineManagementScreen;
import com.huanghuang.rsintegration.machine.client.MachineHub;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.ModList;

/** Handles machine tab click events and dispatches GUI open requests. */
public final class MachineTabHandler {

    private static final List<BindingInfo> EMPTY = List.of();
    private static int hoveredTabIndex = -1;
    private static boolean machineCenterHovered;
    private static boolean resonanceBackpackHovered;
    private static long lastClickTime;
    private static long lastBindingSyncRequest;
    private static final long CLICK_COOLDOWN_MS = 500; // prevent GUI DDoS

    private MachineTabHandler() {}

    /** Get all bound machines regardless of threshold (for Hub mode), filtered by whitelist. */
    public static List<BindingInfo> getAllMachines() {
        var cache = BindingCache.getInstance();
        if (cache.getMachineCount() == 0) return EMPTY;
        return filterEntries(new ArrayList<>(cache.getAll()));
    }

    private static List<BindingInfo> filterEntries(List<BindingInfo> list) {
        list.removeIf(info -> !MachineEntryPolicy.isSelectable(info));
        return list;
    }

    /** P 管理页使用的完整绑定列表，不受 GUI 和自动化黑名单过滤。 */
    public static List<BindingInfo> getAllBoundMachinesForManagement() {
        return BindingCache.getInstance().getAll();
    }

    public static int getHoveredTabIndex() { return hoveredTabIndex; }

    public static void setHoveredTabIndex(int idx) { hoveredTabIndex = idx; }

    public static boolean isMachineCenterHovered() { return machineCenterHovered; }

    public static void setMachineCenterHovered(boolean hovered) { machineCenterHovered = hovered; }

    public static void openMachineCenter() {
        Minecraft.getInstance().setScreen(new MachineManagementScreen(
                null, MachineManagementScreen.View.GUI_ONLY));
    }

    /** 请求服务端权威绑定快照，避免每个终端界面自行扫描背包。 */
    public static void requestBindingSync() {
        long now = System.currentTimeMillis();
        if (now - lastBindingSyncRequest < 1000L) return;
        lastBindingSyncRequest = now;
        if (ModList.get().isLoaded("refinedstorage")) {
            try {
                MachineNetworkHandler.sendBindingSyncRequest();
                return;
            } catch (Exception | LinkageError ignored) {
                // 混合安装初始化失败时继续尝试 BD 独立通道。
            }
        }
        if (ModList.get().isLoaded("beyonddimensions")) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsBindingSyncRequestPacket());
        }
    }

    public static void toggleMachineCenter() {
        List<BindingInfo> all = getAllMachines();
        if (!all.isEmpty()) MachineHub.toggle(all);
    }

    public static boolean isResonanceBackpackHovered() { return resonanceBackpackHovered; }

    public static void setResonanceBackpackHovered(boolean hovered) { resonanceBackpackHovered = hovered; }

    /** Open the Resonance Backpack GUI. Called when Resonance Backpack side button is clicked. */
    public static void toggleResonanceBackpack() {
        toggleResonanceBackpack(false);
    }

    /** Open the backend-local resonance space from a terminal-specific button. */
    public static void toggleResonanceBackpack(boolean preferBeyondDimensions) {
        RSIntegrationMod.LOGGER.info("[RSI-Backpack] Button clicked, sending open packet to server");
        NetworkHandler.CHANNEL.sendToServer(new OpenResonanceBackpackPacket(preferBeyondDimensions));
    }

    /** Handle a click on a machine tab. Sends the OpenBoundMachineGuiPacket to server. */
    public static void onClick(BindingInfo info) {
        if (info == null) return;
        if (!MachineEntryPolicy.isSelectable(info)) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Clicked: {}", info.displayName());
        MachineActionTransport.Backend backend =
                MachineActionTransport.backendOf(info);
        // GuiNavStack is an RS Grid return path. BD terminals have a different
        // menu shape and must never be inspected or restored as an RS screen.
        if (backend == MachineActionTransport.Backend.REFINED_STORAGE) {
            GuiNavStack.pushCurrent();
        }
        MachineActionTransport.open(info, backend);
    }

    /** Send collect-output packet for a Quick-type machine. */
    public static void onCollect(BindingInfo info, boolean toRS) {
        if (info == null) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Collect: {} toRS={}", info.displayName(), toRS);
        MachineActionTransport.collect(
                info, toRS, MachineActionTransport.backendOf(info));
    }

    /** Send insert-item packet for a Quick-type machine. */
    public static void onInsert(BindingInfo info, MachineSlotType slot) {
        if (info == null) return;
        if (!checkCooldown()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Insert: {} slot={}", info.displayName(), slot);
        MachineActionTransport.insert(
                info, slot, MachineActionTransport.backendOf(info));
    }

    /** Remove a machine from the connector that supplied this Hub entry. */
    public static boolean onUnbind(BindingInfo info) {
        if (info == null) return false;
        if (!checkCooldown()) return false;
        RSIntegrationMod.LOGGER.debug("[RSI-MachineTab] Unbind: {}", info.displayName());
        MachineActionTransport.unbind(
                info, MachineActionTransport.backendOf(info));
        return true;
    }

    private static boolean checkCooldown() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return false;
        lastClickTime = now;
        return true;
    }
}
