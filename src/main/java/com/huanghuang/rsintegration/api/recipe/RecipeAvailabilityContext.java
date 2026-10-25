package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;

public record RecipeAvailabilityContext(
        RecipeContext recipeContext,
        Recipe<?> liveRecipe,
        RecipeView recipeView,
        ServerPlayer player,
        @Nullable MachineContext machine,
        @Nullable IntegrationResourceKey targetOutput
) {
    public RecipeAvailabilityContext {
        if (recipeContext == null || liveRecipe == null
                || recipeView == null || player == null) {
            throw new IllegalArgumentException("recipe availability context is incomplete");
        }
    }
}
