package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

public record InputDemand(
        InputPortId id,
        Ingredient ingredient,
        int quantity,
        DemandRole role,
        ItemStack displayHint
) {
    public InputDemand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(role, "role");
        if (quantity <= 0) throw new IllegalArgumentException("input quantity must be positive");
        displayHint = displayHint == null ? ItemStack.EMPTY : displayHint.copyWithCount(1);
    }
}
