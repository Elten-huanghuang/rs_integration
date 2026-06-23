package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.crafting.Ingredient;

/**
 * Wraps an Ingredient with its required count.
 * Used for mod recipes that have {@code IngredientWithCount} (Malum, Lodestone, etc.)
 * where a single ingredient slot may require more than one item.
 */
public record IngredientSpec(Ingredient ingredient, int count) {

    public static final IngredientSpec EMPTY = new IngredientSpec(Ingredient.EMPTY, 0);

    public boolean isEmpty() {
        return ingredient.isEmpty() || count <= 0;
    }
}
