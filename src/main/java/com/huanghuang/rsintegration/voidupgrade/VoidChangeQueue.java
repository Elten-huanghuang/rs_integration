package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class VoidChangeQueue {
    private final Map<StackKey, Entry> pending = new LinkedHashMap<>();

    void record(ItemStack stack, int delta, boolean matches) {
        StackKey key = StackKey.of(stack);
        if (key == null || delta == 0) return;
        Entry existing = pending.get(key);
        if (delta < 0) {
            if (existing == null) return;
            int remaining = existing.amount + delta;
            if (remaining <= 0) pending.remove(key);
            else pending.put(key, new Entry(existing.stack, remaining));
            return;
        }
        if (!matches) return;
        int old = existing == null ? 0 : existing.amount;
        int merged = delta > Integer.MAX_VALUE - old ? Integer.MAX_VALUE : old + delta;
        pending.put(key, new Entry(stack.copyWithCount(1), merged));
    }

    Entry poll(int itemBudget) {
        if (pending.isEmpty() || itemBudget <= 0) return null;
        Map.Entry<StackKey, Entry> first = pending.entrySet().iterator().next();
        Entry change = first.getValue();
        int amount = Math.min(change.amount, itemBudget);
        pending.remove(first.getKey());
        if (change.amount > amount) {
            pending.put(first.getKey(), new Entry(change.stack, change.amount - amount));
        }
        return new Entry(change.stack, amount);
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    void clear() {
        pending.clear();
    }

    record Entry(ItemStack stack, int amount) {}

    private record StackKey(ResourceLocation item, CompoundTag nbt) {
        static StackKey of(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return null;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return null;
            return new StackKey(id, stack.hasTag() ? stack.getTag().copy() : null);
        }

        StackKey {
            nbt = nbt == null ? null : nbt.copy();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey key && item.equals(key.item)
                    && Objects.equals(nbt, key.nbt);
        }

        @Override
        public int hashCode() {
            return 31 * item.hashCode() + Objects.hashCode(nbt);
        }
    }
}
