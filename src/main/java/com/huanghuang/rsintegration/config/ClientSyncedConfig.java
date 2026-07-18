package com.huanghuang.rsintegration.config;

import com.huanghuang.rsintegration.network.packet.ConfigSyncPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Holds server-authoritative config values in client memory.
 * These override the local config file values. Never persisted to disk.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientSyncedConfig {
    private static boolean synced;
    public static boolean ENABLE_MACHINE_GUI_TABS = true;
    public static int MACHINE_TAB_THRESHOLD = 5;
    public static boolean ENABLE_AUTO_EAT = true;
    public static boolean ENABLE_JEI = true;
    public static boolean ENABLE_JEI_MARQUEE = true;
    public static boolean ENABLE_JEI_BOOKMARK_MARQUEE = true;
    public static boolean ENABLE_GRID_SWIPE_EXTRACT = true;

    private ClientSyncedConfig() {}

    public static void apply(ConfigSyncPacket packet) {
        synced = true;
        ENABLE_MACHINE_GUI_TABS = packet.enableMachineGuiTabs;
        MACHINE_TAB_THRESHOLD = packet.machineTabThreshold;
        ENABLE_AUTO_EAT = packet.enableAutoEat;
        ENABLE_JEI = packet.enableJei;
        ENABLE_JEI_MARQUEE = packet.enableJeiMarquee;
        ENABLE_JEI_BOOKMARK_MARQUEE = packet.enableJeiBookmarkMarquee;
        ENABLE_GRID_SWIPE_EXTRACT = packet.enableGridSwipeExtract;
    }

    /** Whether the server has sent a config sync. If not, fall back to local config. */
    public static boolean isSynced() { return synced; }

    /** Reset on disconnect. */
    public static void reset() {
        synced = false;
        ENABLE_MACHINE_GUI_TABS = true;
        MACHINE_TAB_THRESHOLD = 5;
        ENABLE_AUTO_EAT = true;
        ENABLE_JEI = true;
        ENABLE_JEI_MARQUEE = true;
        ENABLE_JEI_BOOKMARK_MARQUEE = true;
        ENABLE_GRID_SWIPE_EXTRACT = true;
    }
}