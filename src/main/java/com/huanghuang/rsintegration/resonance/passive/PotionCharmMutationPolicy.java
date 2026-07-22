package com.huanghuang.rsintegration.resonance.passive;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

final class PotionCharmMutationPolicy {
    private PotionCharmMutationPolicy() {}

    static ItemStack preserveIdentity(ItemStack before, ItemStack after) {
        if (before.isEmpty() || after.isEmpty()) return after;
        ItemStack preserved = before.copyWithCount(after.getCount());
        CompoundTag tag = preserved.getTag();
        if (tag != null) {
            tag.remove("RSISlot");
            if (tag.isEmpty()) preserved.setTag(null);
        }
        preserved.setDamageValue(after.getDamageValue());
        return preserved;
    }
}
