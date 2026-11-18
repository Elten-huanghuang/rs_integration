package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
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
        Map<StackKey, Long> availableItems,
        Map<ResourceLocation, ResourceLocation> forcedRecipes,
        ImmutableRecipeGraph recipeGraph,
        String networkFingerprint,
        String bindingFingerprint,
        Set<ResourceLocation> bindingBlockedOutputIds,
        boolean mainThreadOnly,
        PlanningPreferences preferences,
        @Nullable BindingAnchor bindingAnchor) {

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, Set<ResourceLocation> bindingBlockedOutputIds,
                            boolean mainThreadOnly, PlanningPreferences preferences) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems, forcedRecipes,
                recipeGraph, networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly, preferences, null);
    }

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, boolean mainThreadOnly) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint,
                Set.of(), mainThreadOnly, PlanningPreferences.empty());
    }

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, Set<ResourceLocation> bindingBlockedOutputIds,
                            boolean mainThreadOnly) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint,
                bindingBlockedOutputIds, mainThreadOnly, PlanningPreferences.empty());
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
        preferences = preferences == null ? PlanningPreferences.empty() : preferences;
    }

    public PlanningSnapshot withRecipeGraph(ImmutableRecipeGraph graph, Set<ResourceLocation> blockedOutputs) {
        return new PlanningSnapshot(playerId, requestGeneration, recipeRevision, recipeId,
                availableItems, forcedRecipes, graph, networkFingerprint, bindingFingerprint,
                blockedOutputs, mainThreadOnly, preferences, bindingAnchor);
    }

    public PlanningSnapshot withBindingAnchor(ResourceKey<Level> dimension, @Nullable BlockPos position) {
        return new PlanningSnapshot(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly, preferences, new BindingAnchor(dimension, position == null ? null : position.immutable()));
    }

    /** 自动模式没有指定机器，不把玩家脚下方块当成某台机器。 */
    public record BindingAnchor(ResourceKey<Level> dimension, @Nullable BlockPos position) {}
}
