package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.Objects;

public record RootDemand(
        Ingredient ingredient,
        int quantity,
        int unresolvedQuantity,
        ItemStack displayHint,
        List<RootAllocation> allocations
) {
    public RootDemand {
        Objects.requireNonNull(ingredient, "ingredient");
        if (quantity <= 0) throw new IllegalArgumentException("root quantity must be positive");
        if (unresolvedQuantity < 0 || unresolvedQuantity > quantity) {
            throw new IllegalArgumentException("invalid unresolved root quantity");
        }
        displayHint = displayHint == null ? ItemStack.EMPTY : displayHint.copyWithCount(1);
        allocations = List.copyOf(allocations);
    }
}
