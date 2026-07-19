package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

/**
 * Wraps an Ingredient with its required count.
 * Used for mod recipes that have {@code IngredientWithCount} (Malum, Lodestone, etc.)
 * where a single ingredient slot may require more than one item.
 */
public record IngredientSpec(Ingredient ingredient, int count, DemandRole role) {

    public static final IngredientSpec EMPTY = new IngredientSpec(Ingredient.EMPTY, 0);

    public IngredientSpec(Ingredient ingredient, int count) {
        this(ingredient, count, DemandRole.CONSUMED);
    }

    public IngredientSpec {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(role, "role");
    }

    public boolean isEmpty() {
        return ingredient.isEmpty() || count <= 0;
    }
}
