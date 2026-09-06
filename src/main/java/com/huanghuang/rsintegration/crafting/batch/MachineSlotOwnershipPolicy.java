package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.world.item.ItemStack;

/** Pure slot-delta accounting shared by machine delegates. */
public final class MachineSlotOwnershipPolicy {
    private MachineSlotOwnershipPolicy() {}

    /**
     * Returns the recorded operation-owned count still present above the slot's
     * pre-operation baseline. A type or NBT change revokes removal permission.
     */
    public static int removableAddedCount(ItemStack baseline, ItemStack supplied,
                                          int addedCount, ItemStack current) {
        if (addedCount <= 0 || supplied == null || supplied.isEmpty()
                || current == null || current.isEmpty()
                || !MaterialMatcher.equivalentRuntimeFragment(supplied, current)) {
            return 0;
        }
        int baselineCount = 0;
        if (baseline != null && !baseline.isEmpty()) {
            if (!MaterialMatcher.equivalentRuntimeFragment(baseline, current)) return 0;
            baselineCount = baseline.getCount();
        }
        return Math.min(addedCount, Math.max(0, current.getCount() - baselineCount));
    }
}
