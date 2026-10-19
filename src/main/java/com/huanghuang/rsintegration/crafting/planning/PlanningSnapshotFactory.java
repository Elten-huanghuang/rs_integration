package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Server-thread boundary for constructing planning inputs. No world or mod objects escape here. */
public final class PlanningSnapshotFactory {
    private PlanningSnapshotFactory() {}

    public static PlanningSnapshot capture(UUID playerId,
                                            long requestGeneration,
                                            ResourceLocation recipeId,
                                            Map<StackKey, Integer> availableItems,
                                            Map<StackKey, Long> preciseAvailableItems,
                                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                            ImmutableRecipeGraph recipeGraph,
                                            String networkFingerprint,
                                            String bindingFingerprint,
                                            Set<ResourceLocation> bindingBlockedOutputIds,
                                            boolean mainThreadOnly) {
        return new PlanningSnapshot(playerId, requestGeneration,
                CraftPlanningRevision.current(), recipeId, availableItems,
                preciseAvailableItems, forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint, bindingBlockedOutputIds, mainThreadOnly);
    }

    public static PlanningSnapshot capture(UUID playerId,
                                            long requestGeneration,
                                            ResourceLocation recipeId,
                                            Map<StackKey, Integer> availableItems,
                                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                            ImmutableRecipeGraph recipeGraph,
                                            String networkFingerprint,
                                            String bindingFingerprint,
                                            Set<ResourceLocation> bindingBlockedOutputIds,
                                            boolean mainThreadOnly) {
        return capture(playerId, requestGeneration, recipeId, availableItems,
                derivePrecise(availableItems), forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly);
    }

    public static PlanningSnapshot capture(UUID playerId,
                                            long requestGeneration,
                                            ResourceLocation recipeId,
                                            Map<StackKey, Integer> availableItems,
                                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                            ImmutableRecipeGraph recipeGraph,
                                            String networkFingerprint,
                                            String bindingFingerprint,
                                            boolean mainThreadOnly) {
        return capture(playerId, requestGeneration, recipeId, availableItems,
                derivePrecise(availableItems), forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint, Set.of(), mainThreadOnly);
    }

    private static Map<StackKey, Long> derivePrecise(
            Map<StackKey, Integer> availableItems) {
        return availableItems.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue() == null ? 0L : Math.max(0L, entry.getValue()),
                Long::sum));
    }
}
