package com.huanghuang.rsintegration.api.recipe;

public interface RecipeAvailabilityProvider {
    RecipeAvailability check(RecipeAvailabilityContext context);
}
