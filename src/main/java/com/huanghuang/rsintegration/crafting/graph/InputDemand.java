package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

public record InputDemand(
        InputPortId id,
        Ingredient ingredient,
        long quantity,
        InputConsumption consumption,
        ItemStack displayHint
) {
    public InputDemand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(consumption, "consumption");
        if (quantity <= 0) throw new IllegalArgumentException("input quantity must be positive");
        displayHint = displayHint == null ? ItemStack.EMPTY : displayHint.copyWithCount(1);
    }

    public InputDemand(InputPortId id, Ingredient ingredient, long quantity,
                       DemandRole role, ItemStack displayHint) {
        this(id, ingredient, quantity, InputConsumption.fromDemandRole(role), displayHint);
    }

    public DemandRole role() {
        return consumption.demandRole();
    }
}
