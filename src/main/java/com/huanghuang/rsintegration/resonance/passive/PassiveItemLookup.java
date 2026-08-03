package com.huanghuang.rsintegration.resonance.passive;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Extends an optional mod's inventory lookup with matching resonance-disk stacks. */
public final class PassiveItemLookup {

    private PassiveItemLookup() {}

    public static List<ItemStack> appendMatching(List<ItemStack> original,
                                                  Collection<ItemStack> diskStacks,
                                                  Item item) {
        List<ItemStack> combined = new ArrayList<>(original);
        for (ItemStack stack : diskStacks) {
            if (!stack.isEmpty() && stack.is(item)) combined.add(stack.copy());
        }
        return combined;
    }
}
