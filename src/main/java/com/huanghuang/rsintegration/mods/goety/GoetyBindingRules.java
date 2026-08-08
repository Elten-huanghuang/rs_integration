package com.huanghuang.rsintegration.mods.goety;

/** Distinguishes Goety's Necro Brazier and Dark Altar binding identities. */
public final class GoetyBindingRules {

    public static final String BRAZIER_FILTER = "goety";
    public static final String ALTAR_FILTER = "goety_altar";

    private GoetyBindingRules() {}

    public static boolean isGoetyMachineFilter(String filter) {
        return BRAZIER_FILTER.equals(filter) || ALTAR_FILTER.equals(filter);
    }

    public static boolean matches(String blockKey, String blockRegistryKey, String filter) {
        if (blockKey == null || !isGoetyMachineFilter(filter)) return false;

        int separator = blockKey.indexOf("||");
        if (separator >= 0) {
            return filter.equals(blockKey.substring(0, separator));
        }

        // Legacy bindings predate the explicit machine prefix. Their registry
        // or description key still distinguishes the two Goety machines.
        String machineName = BRAZIER_FILTER.equals(filter) ? "necro_brazier" : "dark_altar";
        return ("goety:" + machineName).equals(blockRegistryKey)
                || blockKey.endsWith("." + machineName)
                || blockKey.equals(machineName);
    }
}
