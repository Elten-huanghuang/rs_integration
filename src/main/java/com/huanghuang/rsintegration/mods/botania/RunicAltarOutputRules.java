package com.huanghuang.rsintegration.mods.botania;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Pure ownership rules for physical outputs spawned by a Botania runic altar. */
final class RunicAltarOutputRules {
    private RunicAltarOutputRules() {}

    static boolean isOwnedOutput(ItemStack candidate, ItemStack primary,
                                 List<ItemStack> reusableInputs) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (primary != null && !primary.isEmpty()
                && ItemStack.isSameItemSameTags(candidate, primary)) {
            return true;
        }
        for (ItemStack reusable : reusableInputs) {
            if (reusable != null && !reusable.isEmpty()
                    && ItemStack.isSameItemSameTags(candidate, reusable)) {
                return true;
            }
        }
        return false;
    }
}
