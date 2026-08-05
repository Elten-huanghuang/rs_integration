package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.crafting.batch.MachineSlotOwnershipPolicy;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Pure busy-state and slot-ownership rules for the Enchantal Cooler delegate. */
public final class EnchantalCoolerInventoryPolicy {
    private EnchantalCoolerInventoryPolicy() {}

    public static boolean isIdle(List<ItemStack> inputs, ItemStack output, int cookingProgress) {
        if (cookingProgress > 0 || output == null || !output.isEmpty()) return false;
        if (inputs == null) return false;
        for (ItemStack input : inputs) {
            if (input != null && !input.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Returns the amount that can be removed without crossing the slot count observed
     * before this operation. Item or NBT changes revoke ownership completely.
     */
    public static int removableAddedCount(ItemStack baseline, ItemStack supplied,
                                           int addedCount, ItemStack current) {
        return MachineSlotOwnershipPolicy.removableAddedCount(
                baseline, supplied, addedCount, current);
    }
}
