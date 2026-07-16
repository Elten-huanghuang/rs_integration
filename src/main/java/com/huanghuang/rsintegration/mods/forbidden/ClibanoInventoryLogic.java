package com.huanghuang.rsintegration.mods.forbidden;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Pure slot and ownership calculations for the two-lane Clibano inventory. */
public final class ClibanoInventoryLogic {

    public static final int ENHANCER_SLOT = 0;
    public static final int SOUL_SLOT = 1;
    public static final int FUEL_SLOT = 2;
    public static final int FIRST_INPUT_SLOT = 3;
    public static final int SECOND_INPUT_SLOT = 4;
    public static final int FIRST_OUTPUT_SLOT = 5;
    public static final int SECOND_OUTPUT_SLOT = 6;

    private ClibanoInventoryLogic() {}

    public static int chooseInputSlot(ItemStack first, ItemStack second,
                                      int firstProgress, int secondProgress) {
        if (firstProgress > 0 || secondProgress > 0) return -1;
        if (first == null || first.isEmpty()) return FIRST_INPUT_SLOT;
        if (second == null || second.isEmpty()) return SECOND_INPUT_SLOT;
        return -1;
    }

    public static int countMatching(List<ItemStack> stacks, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return 0;
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()
                    && ItemStack.isSameItemSameTags(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int refundableAddedCount(ItemStack baseline, int addedCount, ItemStack current) {
        if (addedCount <= 0 || current == null || current.isEmpty()) return 0;
        int baselineCount = 0;
        if (baseline != null && !baseline.isEmpty()) {
            if (!ItemStack.isSameItemSameTags(baseline, current)) return 0;
            baselineCount = baseline.getCount();
        }
        return Math.min(addedCount, Math.max(0, current.getCount() - baselineCount));
    }

    public static boolean fireSatisfies(int currentOrdinal, int requiredOrdinal) {
        return currentOrdinal >= requiredOrdinal;
    }
}
