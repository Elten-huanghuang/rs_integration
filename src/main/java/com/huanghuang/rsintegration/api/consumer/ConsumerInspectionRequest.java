package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record ConsumerInspectionRequest(
        ResourceLocation consumerId,
        ConsumerInspectionRequestId requestId,
        List<ConsumerRecipeKey> recipes,
        ConsumerMachineSelector machineSelector,
        ConsumerStorageSelector storageSelector,
        ConsumerOutputDestination outputDestination,
        ConsumerInspectionOptions options
) {
    public ConsumerInspectionRequest {
        if (consumerId == null || requestId == null) {
            throw new IllegalArgumentException(
                    "consumerId and requestId are required");
        }
        if (recipes != null
                && recipes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "recipes must not contain null");
        }
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        if (recipes.isEmpty()) {
            throw new IllegalArgumentException("recipes must not be empty");
        }
        if (machineSelector == null || storageSelector == null
                || outputDestination == null || options == null) {
            throw new IllegalArgumentException("inspection context is incomplete");
        }
    }
}
