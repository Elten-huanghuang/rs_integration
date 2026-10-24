package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 材料快照复制工具，保留调用方需要的空槽语义。 */
public final class StackCopies {
    private StackCopies() {}

    public static List<ItemStack> includingEmpty(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copies;
    }

    public static List<ItemStack> excludingEmpty(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) copies.add(stack.copy());
        }
        return copies;
    }
}
