package com.huanghuang.rsintegration.api.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;

public record RecipeContext(
        ResourceLocation integrationId,
        RegistryAccess registries,
        long recipeRevision
) {
    public RecipeContext {
        if (integrationId == null || registries == null) {
            throw new IllegalArgumentException("recipe context is incomplete");
        }
        if (recipeRevision < 0L) {
            throw new IllegalArgumentException("recipeRevision must be non-negative");
        }
    }
}
