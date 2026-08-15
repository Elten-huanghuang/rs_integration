package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Prevents valuable equipment stored in backpacks from being used as generic materials. */
final class InventoryProtectionPolicy {

    private InventoryProtectionPolicy() {}

    /**
     * A damageable, enchanted/damaged/named item is treated as equipment rather
     * than a fungible material when it comes from a Sophisticated Backpack.
     */
    static boolean isProtectedBackpackItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) return false;
        return stack.isEnchanted()
                || stack.getDamageValue() > 0
                || stack.hasCustomHoverName();
    }

    /** Strict NBT ingredients are explicit requests and may consume the item. */
    static boolean mayUseFromBackpack(ItemStack stack, Ingredient ingredient) {
        return !isProtectedBackpackItem(stack)
                || (ingredient != null && IngredientMatcher.requiresNbt(ingredient));
    }
}
