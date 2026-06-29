package com.huanghuang.rsintegration.machine;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

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

    /** Classify a machine by its BlockEntity type. */
    public static MachineInteractType fromBe(BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity) return QUICK;
        // CampfireBlockEntity also extends AbstractFurnaceBlockEntity
        return GUI;
    }

    /** Classify by block class name (off-thread / no BE available). */
    public static MachineInteractType fromBlockKey(String blockKey) {
        if (blockKey == null) return GUI;
        String lower = blockKey.toLowerCase();
        if (lower.contains("furnace") || lower.contains("smoker")
                || lower.contains("blast_furnace") || lower.contains("campfire")
                || lower.contains("aetherium_anvil"))
            return QUICK;
        return GUI;
    }
}
