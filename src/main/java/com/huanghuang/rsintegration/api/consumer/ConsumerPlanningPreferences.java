package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ConsumerPlanningPreferences(
        Map<String, ResourceLocation> forcedRecipes,
        Map<String, IntegrationResourceKey> materialLocks,
        Set<ResourceLocation> excludedRecipes
) {
    public ConsumerPlanningPreferences {
        if (forcedRecipes != null && forcedRecipes.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null)
                || materialLocks != null && materialLocks.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null)
                || excludedRecipes != null
                && excludedRecipes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "planning preferences contain null or blank entries");
        }
        forcedRecipes = forcedRecipes == null
                ? Map.of() : Map.copyOf(forcedRecipes);
        materialLocks = materialLocks == null
                ? Map.of() : Map.copyOf(materialLocks);
        excludedRecipes = excludedRecipes == null
                ? Set.of() : Set.copyOf(excludedRecipes);
    }

    public static ConsumerPlanningPreferences empty() {
        return new ConsumerPlanningPreferences(Map.of(), Map.of(), Set.of());
    }
}
