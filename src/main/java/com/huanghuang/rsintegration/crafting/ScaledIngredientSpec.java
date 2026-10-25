package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

/** 整批规划需求。数量为 long，但不会回写为单槽 {@link IngredientSpec#count()}。 */
public record ScaledIngredientSpec(
        Ingredient ingredient,
        long count,
        InputConsumption consumption
) {
    public ScaledIngredientSpec {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(consumption, "consumption");
    }

    public static ScaledIngredientSpec of(IngredientSpec spec, long count) {
        return new ScaledIngredientSpec(spec.ingredient(), count, spec.consumption());
    }

    public boolean isEmpty() {
        return ingredient.isEmpty() || count <= 0L;
    }

    public DemandRole role() {
        return consumption.demandRole();
    }
}
