package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

public record UnresolvedDemand(
        InputPortId consumer,
        Ingredient ingredient,
        int quantity,
        ItemStack displayHint
) {
    public UnresolvedDemand {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(ingredient, "ingredient");
        if (quantity <= 0) throw new IllegalArgumentException("unresolved quantity must be positive");
        displayHint = displayHint == null ? ItemStack.EMPTY : displayHint.copyWithCount(1);
    }
}
