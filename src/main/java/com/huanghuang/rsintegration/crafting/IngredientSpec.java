package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

/**
 * Wraps an Ingredient with its required count.
 * Used for mod recipes that have {@code IngredientWithCount} (Malum, Lodestone, etc.)
 * where a single ingredient slot may require more than one item.
 */
/** 单配方槽位数量；跨批次总量必须由图或计划层使用 long 聚合。 */
public record IngredientSpec(Ingredient ingredient, int count, InputConsumption consumption) {

    public static final IngredientSpec EMPTY = new IngredientSpec(Ingredient.EMPTY, 0);

    public IngredientSpec(Ingredient ingredient, int count) {
        this(ingredient, count, InputConsumption.consumed());
    }

    public IngredientSpec(Ingredient ingredient, int count, DemandRole role) {
        this(ingredient, count, InputConsumption.fromDemandRole(role));
    }

    public IngredientSpec {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(consumption, "consumption");
    }

    public boolean isEmpty() {
        return ingredient.isEmpty() || count <= 0;
    }

    public DemandRole role() {
        return consumption.demandRole();
    }
}
