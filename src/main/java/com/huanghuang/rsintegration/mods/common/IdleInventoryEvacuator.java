package com.huanghuang.rsintegration.mods.common;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Shared idle-machine slot evacuation with explicit per-slot ownership semantics. */
public final class IdleInventoryEvacuator {
    private IdleInventoryEvacuator() {}

    public enum SlotPolicy {
        /** A real item that must be returned to RS (or the player fallback). */
        RETURN,
        /** A derived preview that must be cleared without creating an item. */
        DISCARD,
        /** Persistent machine state such as fuel that must remain untouched. */
        PRESERVE
    }

    public record Result(boolean cleared, int returnedCount) {
        public static Result blocked() { return new Result(false, 0); }
    }

    public static Result evacuate(IItemHandler inventory, boolean idle,
                                  IntFunction<SlotPolicy> slotPolicy,
                                  Consumer<ItemStack> returnItem) {
        if (inventory == null || !idle) return Result.blocked();
        Objects.requireNonNull(slotPolicy, "slotPolicy");
        Objects.requireNonNull(returnItem, "returnItem");
        int returned = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            SlotPolicy policy = Objects.requireNonNull(slotPolicy.apply(slot), "slot policy");
            if (policy == SlotPolicy.PRESERVE) continue;
            while (!inventory.getStackInSlot(slot).isEmpty()) {
                ItemStack existing = inventory.getStackInSlot(slot);
                ItemStack removed = inventory.extractItem(slot, existing.getCount(), false);
                if (removed.isEmpty()) return new Result(false, returned);
                if (policy == SlotPolicy.RETURN) {
                    returned += removed.getCount();
                    returnItem.accept(removed.copy());
                }
            }
        }
        return new Result(true, returned);
    }

    public static Result evacuate(Container inventory, boolean idle,
                                  IntFunction<SlotPolicy> slotPolicy,
                                  Consumer<ItemStack> returnItem) {
        if (inventory == null || !idle) return Result.blocked();
        Objects.requireNonNull(slotPolicy, "slotPolicy");
        Objects.requireNonNull(returnItem, "returnItem");
        int returned = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            SlotPolicy policy = Objects.requireNonNull(slotPolicy.apply(slot), "slot policy");
            if (policy == SlotPolicy.PRESERVE) continue;
            while (!inventory.getItem(slot).isEmpty()) {
                ItemStack existing = inventory.getItem(slot);
                ItemStack removed = inventory.removeItem(slot, existing.getCount());
                if (removed.isEmpty()) return new Result(false, returned);
                if (policy == SlotPolicy.RETURN) {
                    returned += removed.getCount();
                    returnItem.accept(removed.copy());
                }
            }
        }
        return new Result(true, returned);
    }
}
