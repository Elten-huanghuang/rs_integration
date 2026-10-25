package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.UUID;

public record ExecutionRequestContext(
        RecipeContext recipeContext,
        Recipe<?> liveRecipe,
        RecipeView recipe,
        MachineContext machine,
        ReservedMaterialView materials,
        UUID transactionId,
        UUID operationId,
        int attempt,
        @Nullable IntegrationResourceKey targetOutput
) {
    public ExecutionRequestContext {
        if (recipeContext == null || liveRecipe == null
                || recipe == null || machine == null || materials == null
                || transactionId == null || operationId == null || attempt < 1) {
            throw new IllegalArgumentException(
                    "execution request context is incomplete");
        }
    }
}
