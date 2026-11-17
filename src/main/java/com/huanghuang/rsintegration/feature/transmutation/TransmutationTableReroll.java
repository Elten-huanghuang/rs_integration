package com.huanghuang.rsintegration.feature.transmutation;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/** 自动嬗变只决定是否继续，实际转换和扣费仍交给原模组。 */
public final class TransmutationTableReroll {
    public static final int BUTTON_ID = 3;

    public enum Outcome { READY, MATCHED, UNAVAILABLE }

    private TransmutationTableReroll() {}

    public static Outcome evaluate(ItemStack input, List<ItemStack> possibilities,
                                   int levels, int cost, boolean creative,
                                   Predicate<ItemStack> bookmarked) {
        for (ItemStack possibility : possibilities) {
            if (possibility != null && !possibility.isEmpty() && bookmarked.test(possibility)) {
                return Outcome.MATCHED;
            }
        }
        if (input.isEmpty() || possibilities.size() != 3
                || possibilities.stream().anyMatch(stack -> stack == null || stack.isEmpty())
                || (!creative && levels < cost)) {
            return Outcome.UNAVAILABLE;
        }
        return Outcome.READY;
    }

    public static int nextState(int previous, Outcome outcome) {
        // DataSlot 通过有符号 short 同步；序号确保连续抽到相同物品时仍有确认。
        return ((previous + 4) & 0x7FFC) | (outcome.ordinal() + 1);
    }

    public static Outcome outcome(int state) {
        return switch (state & 3) {
            case 1 -> Outcome.READY;
            case 2 -> Outcome.MATCHED;
            default -> Outcome.UNAVAILABLE;
        };
    }
}
