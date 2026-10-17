package com.huanghuang.rsintegration.mods.common;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;

/**
 * 把一个外部容器暴露出来的 {@code getItems()} 列表包装成 {@code IItemHandler}。
 * <p>
 * Let's Do 系列（farm_and_charm / candlelight）的锅具只在 Forge 事件里走原生
 * {@code WorldlyContainer}，没有注册 {@code IItemHandler} 能力，因此委托无法像
 * 原版烹饪锅那样直接取能力。这里只做"按槽位读写同一份列表"的转发，
 * 既不复制库存也不缓存：原版机器改哪个槽，这里读到的就是哪个槽。
 */
public final class InventoryListItemHandler implements IItemHandler {

    /** 每次访问时都重新取得的库存列表，避免机器重建容器后拿到过期引用。 */
    private final Supplier<List<ItemStack>> inventory;

    private InventoryListItemHandler(Supplier<List<ItemStack>> inventory) {
        this.inventory = inventory;
    }

    /** 用外部容器的 {@code getItems()} 列表构造；返回非空列表时必定非 null。 */
    public static IItemHandler of(Supplier<NonNullList<ItemStack>> items) {
        if (items == null) return null;
        NonNullList<ItemStack> first = items.get();
        if (first == null || first.isEmpty()) return null;
        return new InventoryListItemHandler(items::get);
    }

    @Override
    public int getSlots() {
        return inventory.get().size();
    }

    @Override
    @NotNull
    public ItemStack getStackInSlot(int slot) {
        List<ItemStack> items = inventory.get();
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    @NotNull
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
        List<ItemStack> items = inventory.get();
        if (slot < 0 || slot >= items.size()) return stack;
        ItemStack existing = items.get(slot);
        if (existing.isEmpty()) {
            int accepted = Math.min(Math.min(getSlotLimit(slot), stack.getMaxStackSize()),
                    stack.getCount());
            ItemStack remainder = accepted >= stack.getCount()
                    ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
            if (!simulate && accepted > 0) {
                items.set(slot, stack.copyWithCount(accepted));
            }
            return remainder;
        }
        if (!ItemStack.isSameItemSameTags(existing, stack)) return stack;
        int limit = Math.min(getSlotLimit(slot), existing.getMaxStackSize());
        int room = limit - existing.getCount();
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (!simulate) existing.grow(moved);
        return moved >= stack.getCount() ? ItemStack.EMPTY
                : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    @NotNull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        List<ItemStack> items = inventory.get();
        if (slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
        ItemStack existing = items.get(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(amount, existing.getCount());
        if (!simulate) {
            ItemStack remainder = existing.copyWithCount(existing.getCount() - taken);
            items.set(slot, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
        }
        return existing.copyWithCount(taken);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return !stack.isEmpty();
    }
}
