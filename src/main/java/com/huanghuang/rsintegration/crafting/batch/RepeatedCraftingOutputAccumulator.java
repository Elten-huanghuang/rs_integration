package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.world.item.ItemStack;

/** Accumulates repeated recipe outputs without accepting stale or mixed variants. */
final class RepeatedCraftingOutputAccumulator {

    private ItemStack combined = ItemStack.EMPTY;

    boolean add(ItemStack output) {
        if (output == null || output.isEmpty()) return false;
        if (combined.isEmpty()) {
            combined = output.copy();
            return true;
        }
        if (!MaterialMatcher.equivalentRuntimeFragment(combined, output)) return false;
        combined.grow(output.getCount());
        return true;
    }

    ItemStack result() {
        return combined.copy();
    }
}
