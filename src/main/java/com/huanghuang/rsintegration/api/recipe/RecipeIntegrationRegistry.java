package com.huanghuang.rsintegration.api.recipe;

import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.Optional;

public interface RecipeIntegrationRegistry {
    List<RecipeIntegration<?>> findAll(Recipe<?> recipe);

    RecipeClaim claim(Recipe<?> recipe);

    default Optional<RecipeIntegration<?>> find(Recipe<?> recipe) {
        return Optional.ofNullable(claim(recipe).winner());
    }
}
