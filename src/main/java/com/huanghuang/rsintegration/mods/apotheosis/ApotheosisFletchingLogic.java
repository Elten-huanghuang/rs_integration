package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.world.item.ItemStack;

/** Pure stack checks shared by the fletching delegate and its tests. */
final class ApotheosisFletchingLogic {
    static final int RESULT_SLOT = 0;
    static final int FIRST_INPUT_SLOT = 1;
    static final int INPUT_COUNT = 3;

    private ApotheosisFletchingLogic() {
    }

    static int menuSlotForIngredient(int ingredientIndex) {
        if (ingredientIndex < 0 || ingredientIndex >= INPUT_COUNT) {
            throw new IndexOutOfBoundsException("ingredient index: " + ingredientIndex);
        }
        return FIRST_INPUT_SLOT + ingredientIndex;
    }

    static boolean sameExactStack(ItemStack actual, ItemStack expected) {
        return actual != null && expected != null
                && !actual.isEmpty() && !expected.isEmpty()
                && actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameTags(actual, expected);
    }
}
