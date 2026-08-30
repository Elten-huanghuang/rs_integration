package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, value-only input captured on the server thread for background planning. */
public record PlanningSnapshot(
        UUID playerId,
        long requestGeneration,
        long recipeRevision,
        ResourceLocation recipeId,
        Map<StackKey, Integer> availableItems,
        Map<ResourceLocation, ResourceLocation> forcedRecipes,
        ImmutableRecipeGraph recipeGraph,
        String networkFingerprint,
        String bindingFingerprint,
        Set<ResourceLocation> bindingBlockedOutputIds,
        boolean mainThreadOnly) {

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Integer> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, boolean mainThreadOnly) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint,
                Set.of(), mainThreadOnly);
    }

    public PlanningSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(recipeId, "recipeId");
        availableItems = Map.copyOf(availableItems);
        forcedRecipes = Map.copyOf(forcedRecipes);
        recipeGraph = recipeGraph == null ? new ImmutableRecipeGraph(Map.of()) : recipeGraph;
        networkFingerprint = Objects.requireNonNullElse(networkFingerprint, "");
        bindingFingerprint = Objects.requireNonNullElse(bindingFingerprint, "");
        bindingBlockedOutputIds = bindingBlockedOutputIds == null
                ? Set.of() : Set.copyOf(bindingBlockedOutputIds);
    }
}
