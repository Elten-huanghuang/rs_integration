package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Captures items synchronously inserted into the player's main inventory by a native menu. */
public final class InventoryDeltaCapture {
    private final List<ItemStack> before;

    private InventoryDeltaCapture(List<ItemStack> before) {
        this.before = before;
    }

    public static InventoryDeltaCapture snapshot(ServerPlayer player) {
        return new InventoryDeltaCapture(copy(player.getInventory().items));
    }

    public List<ItemStack> removeAdded(ServerPlayer player) {
        List<ItemStack> results = new ArrayList<>();
        List<ItemStack> after = copy(player.getInventory().items);
        for (ItemStack candidate : after) {
            if (candidate.isEmpty() || containsIdentity(results, candidate)) continue;
            int added = count(after, candidate) - count(before, candidate);
            if (added <= 0) continue;
            int removed = remove(player, candidate, added);
            if (removed > 0) results.add(candidate.copyWithCount(removed));
        }
        return List.copyOf(results);
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    private static boolean containsIdentity(List<ItemStack> stacks, ItemStack candidate) {
        return stacks.stream().anyMatch(stack -> MaterialMatcher.equivalentRuntimeFragment(stack, candidate));
    }

    private static int count(List<ItemStack> stacks, ItemStack candidate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (MaterialMatcher.equivalentRuntimeFragment(stack, candidate)) total += stack.getCount();
        }
        return total;
    }

    private static int remove(ServerPlayer player, ItemStack candidate, int requested) {
        int remaining = requested;
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!MaterialMatcher.equivalentRuntimeFragment(stack, candidate)) continue;
            int taken = Math.min(stack.getCount(), remaining);
            stack.shrink(taken);
            remaining -= taken;
            if (stack.isEmpty()) player.getInventory().items.set(i, ItemStack.EMPTY);
        }
        if (remaining != requested) player.getInventory().setChanged();
        return requested - remaining;
    }
}
