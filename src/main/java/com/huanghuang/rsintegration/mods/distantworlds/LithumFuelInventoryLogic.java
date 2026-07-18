package com.huanghuang.rsintegration.mods.distantworlds;

import net.minecraft.world.item.ItemStack;

public final class LithumFuelInventoryLogic {
    private LithumFuelInventoryLogic() {}

    public static int insertionRoom(ItemStack current, ItemStack candidate, int slotLimit) {
        if (candidate == null || candidate.isEmpty()) return 0;
        if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, candidate)) return 0;
        int limit = Math.min(slotLimit, candidate.getMaxStackSize());
        return Math.max(0, limit - current.getCount());
    }

    public static int refundableAddedCount(ItemStack baseline, int insertedCount, ItemStack current) {
        if (insertedCount <= 0 || current == null || current.isEmpty()) return 0;
        if (!baseline.isEmpty() && !ItemStack.isSameItemSameTags(baseline, current)) return 0;
        int baselineCount = baseline.isEmpty() ? 0 : baseline.getCount();
        return Math.min(insertedCount, Math.max(0, current.getCount() - baselineCount));
    }
}
