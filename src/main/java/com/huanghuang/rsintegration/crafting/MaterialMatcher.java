package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

/** Shared matching semantics for graph planning and runtime material ownership. */
public final class MaterialMatcher {
    private MaterialMatcher() {}

    public static boolean matchesIngredient(Ingredient ingredient, ItemStack stack) {
        return IngredientMatcher.test(Objects.requireNonNull(ingredient, "ingredient"),
                Objects.requireNonNull(stack, "stack"));
    }

    public static boolean matchesExact(MaterialKey material, ItemStack stack) {
        Objects.requireNonNull(material, "material");
        return stack != null && !stack.isEmpty() && MaterialKey.of(stack).equals(material);
    }

    public static boolean sameRuntimeFragment(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && ItemStack.isSameItemSameTags(first, second);
    }

    /** Capture accepts exact NBT when specified and otherwise conservatively accepts the same item. */
    public static boolean matchesCaptureExpectation(MaterialKey expected, ItemStack stack) {
        if (stack == null || stack.isEmpty() || expected == null || stack.getItem() != expected.item()) {
            return false;
        }
        return expected.tag() == null || MaterialKey.of(stack).equals(expected);
    }
}
