package com.huanghuang.rsintegration.util;

import net.minecraft.world.item.ItemStack;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Pure simulate-record-perform sequence used by tracked external insertions. */
public final class TrackedInsertionSequence {
    private TrackedInsertionSequence() {}

    public enum Phase {
        SIMULATE,
        PERFORM
    }

    public static ItemStack insert(ItemStack input,
                                   BiFunction<ItemStack, Phase, ItemStack> insertion,
                                   Consumer<ItemStack> changeRecorder) {
        if (input == null || input.isEmpty()) return input == null ? ItemStack.EMPTY : input.copy();
        ItemStack simulatedRemainder = insertion.apply(input.copy(), Phase.SIMULATE);
        ItemStack accepted = InsertedStackDelta.between(input, simulatedRemainder);
        if (!accepted.isEmpty()) changeRecorder.accept(accepted.copy());
        return insertion.apply(input.copy(), Phase.PERFORM);
    }
}
