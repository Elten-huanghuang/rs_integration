package com.huanghuang.rsintegration.api.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Optional;

public interface RecipeIntegration<R extends Recipe<?>> {
    ResourceLocation id();

    boolean supports(Recipe<?> recipe);

    RecipeInspectionResult inspect(RecipeContext context, R recipe);

    default RecipeProjectionThread projectionThread() {
        return RecipeProjectionThread.SERVER_MAIN;
    }

    default RecipeExecutionKind executionKind() {
        return RecipeExecutionKind.NONE;
    }

    default Optional<RecipeAvailabilityProvider> availabilityProvider() {
        return Optional.empty();
    }
}
