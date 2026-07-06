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

    private ClientSyncedConfig() {}

    public static void apply(ConfigSyncPacket packet) {
        synced = true;
        ENABLE_MACHINE_GUI_TABS = packet.enableMachineGuiTabs;
        MACHINE_TAB_THRESHOLD = packet.machineTabThreshold;
    }

    /** Whether the server has sent a config sync. If not, fall back to local config. */
    public static boolean isSynced() { return synced; }

    /** Reset on disconnect. */
    public static void reset() {
        synced = false;
        ENABLE_MACHINE_GUI_TABS = true;
        MACHINE_TAB_THRESHOLD = 5;
    }
}
