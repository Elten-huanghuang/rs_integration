package com.huanghuang.rsintegration.mods.biomancy;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class BiomancyBioForgeInventory implements AutoCloseable {
    private final List<ItemStack> inventory;
    private final List<ItemStack> original;
    private boolean closed;

    private BiomancyBioForgeInventory(List<ItemStack> inventory, List<ItemStack> staged) {
        this.inventory = inventory;
        original = List.copyOf(inventory);
        for (int slot = 0; slot < inventory.size(); slot++) inventory.set(slot, staged.get(slot));
    }

    @Nullable
    static BiomancyBioForgeInventory open(List<ItemStack> inventory, List<ItemStack> materials) {
        List<ItemStack> staged = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) staged.add(ItemStack.EMPTY);
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) continue;
            int remaining = material.getCount();
            for (int slot = 0; slot < staged.size() && remaining > 0; slot++) {
                ItemStack current = staged.get(slot);
                if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, material)) continue;
                int amount = Math.min(remaining, material.getMaxStackSize() - current.getCount());
                if (amount <= 0) continue;
                if (current.isEmpty()) staged.set(slot, material.copyWithCount(amount));
                else current.grow(amount);
                remaining -= amount;
            }
            if (remaining > 0) return null;
        }
        return new BiomancyBioForgeInventory(inventory, staged);
    }

    List<ItemStack> remainingInputs() {
        return inventory.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
    }

    @Override
    public void close() {
        if (closed) return;
        for (int slot = 0; slot < inventory.size(); slot++) inventory.set(slot, original.get(slot));
        closed = true;
    }
}
