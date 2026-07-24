package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Applies stack-pool mutations only when the complete reservation succeeds. */
final class StackPoolTransaction {

    private StackPoolTransaction() {
    }

    @Nullable
    static <T> T execute(List<ItemStack> initialPool, List<ItemStack> producerPool,
                         BiFunction<List<ItemStack>, List<ItemStack>, T> reservation) {
        List<ItemStack> workingInitial = copy(initialPool);
        List<ItemStack> workingProducer = copy(producerPool);
        T result = reservation.apply(workingInitial, workingProducer);
        if (result == null) return null;
        replace(initialPool, workingInitial);
        replace(producerPool, workingProducer);
        return result;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            result.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        return result;
    }

    private static void replace(List<ItemStack> target, List<ItemStack> source) {
        target.clear();
        target.addAll(copy(source));
    }
}
