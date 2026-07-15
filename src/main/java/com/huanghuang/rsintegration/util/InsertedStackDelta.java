package com.huanghuang.rsintegration.util;

import net.minecraft.world.item.ItemStack;

/** Calculates the immutable part of an input stack accepted by an external inventory. */
public final class InsertedStackDelta {

    private InsertedStackDelta() {}

    public static ItemStack between(ItemStack input, ItemStack remainder) {
        if (input == null || input.isEmpty()) return ItemStack.EMPTY;
        int remaining = remainder == null || remainder.isEmpty() ? 0 : remainder.getCount();
        if (remainder != null && !remainder.isEmpty()
                && !ItemStack.isSameItemSameTags(input, remainder)) {
            return ItemStack.EMPTY;
        }
        if (remaining < 0 || remaining > input.getCount()) return ItemStack.EMPTY;
        int inserted = input.getCount() - remaining;
        return inserted <= 0 ? ItemStack.EMPTY : input.copyWithCount(inserted);
    }
}
