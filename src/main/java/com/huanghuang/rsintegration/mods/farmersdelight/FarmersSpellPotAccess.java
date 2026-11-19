package com.huanghuang.rsintegration.mods.farmersdelight;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

public interface FarmersSpellPotAccess {
    ItemStackHandler rsi$foodInventory();

    ItemStackHandler rsi$scrollInventory();

    void rsi$setManagedCooking(boolean managed);

    /** 修正 1.0.7 从锅内首次复制到空输出槽后遗留的同一份菜。 */
    static void finishTransfer(ItemStackHandler inventory, int previousMealCount) {
        ItemStack meal = inventory.getStackInSlot(6);
        ItemStack output = inventory.getStackInSlot(8);
        if (meal.getCount() == previousMealCount && !output.isEmpty()
                && output.getCount() <= previousMealCount && ItemStack.isSameItemSameTags(meal, output)) {
            inventory.setStackInSlot(6, meal.copyWithCount(previousMealCount - output.getCount()));
        }
    }
}
