package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record ConsumerRecipeCandidate(
        ResourceLocation recipeId,
        ResourceLocation processId,
        RecipeExecutionKind executionKind,
        List<IntegrationResourceKey> declaredOutputs
) {
    public ConsumerRecipeCandidate {
        if (recipeId == null || processId == null || executionKind == null) {
            throw new IllegalArgumentException(
                    "candidate identity is incomplete");
        }
        if (declaredOutputs != null
                && declaredOutputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "declared outputs must not contain null");
        }
        declaredOutputs = declaredOutputs == null
                ? List.of() : List.copyOf(declaredOutputs);
    }
}
