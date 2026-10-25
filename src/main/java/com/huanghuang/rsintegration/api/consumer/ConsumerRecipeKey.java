package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record ConsumerRecipeKey(
        ResourceLocation recipeId,
        @Nullable IntegrationResourceKey targetOutput,
        ConsumerRequestAmount amount,
        ConsumerPlanningPreferences preferences
) {
    public ConsumerRecipeKey {
        if (recipeId == null || amount == null) {
            throw new IllegalArgumentException("recipeId and amount are required");
        }
        preferences = preferences == null
                ? ConsumerPlanningPreferences.empty() : preferences;
    }
}
