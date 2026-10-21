package com.huanghuang.rsintegration.machine;

/**
 * Determines how a machine tag behaves when clicked on the RS Grid.
 * <ul>
 *   <li>{@link #QUICK} — collect output / insert input without opening GUI (furnaces)</li>
 *   <li>{@link #GUI}   — always opens the machine's GUI (smithing table, stonecutter, etc.)</li>
 * </ul>
 */
public enum MachineInteractType {
    QUICK,
    GUI;

    /** Classify by binding block key so client and server use one rule. */
    public static MachineInteractType fromBlockKey(String blockKey) {
        return FurnaceMachineAccess.supportsQuickActions(blockKey)
                ? QUICK : GUI;
    }
}
