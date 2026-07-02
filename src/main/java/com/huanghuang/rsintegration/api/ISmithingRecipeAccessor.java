package com.huanghuang.rsintegration.api;

import net.minecraft.world.item.crafting.Ingredient;

/**
 * Accessor interface for SmithingTransformRecipe / SmithingTrimRecipe that exposes
 * template, base, and addition fields directly.
 * <p>
 * Lives OUTSIDE the mixin package so non-mixin code can safely {@code instanceof}-check
 * against it without triggering Mixin's illegal-reference guard.
 */
public interface ISmithingRecipeAccessor {
    Ingredient rsi$getTemplate();
    Ingredient rsi$getBase();
    Ingredient rsi$getAddition();
}
