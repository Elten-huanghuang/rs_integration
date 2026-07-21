package com.huanghuang.rsintegration.resonance.disk;

import net.minecraft.world.item.ItemStack;

public final class ResonanceStackIdentity {
    private ResonanceStackIdentity() {}

    /** Item identity used by logical backpack slots; NBT variants must never merge. */
    public static boolean isSameVariant(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty()
                && ItemStack.isSameItemSameTags(first, second);
    }
}
