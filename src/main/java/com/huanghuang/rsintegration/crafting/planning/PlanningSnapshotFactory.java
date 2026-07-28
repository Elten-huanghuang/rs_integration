package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;

/** Server-thread boundary for constructing planning inputs. No world or mod objects escape here. */
public final class PlanningSnapshotFactory {
    private PlanningSnapshotFactory() {}

    public static PlanningSnapshot capture(UUID playerId,
                                            long requestGeneration,
                                            ResourceLocation recipeId,
                                            Map<StackKey, Integer> availableItems,
                                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                            ImmutableRecipeGraph recipeGraph,
                                            String networkFingerprint,
                                            String bindingFingerprint,
                                            boolean mainThreadOnly) {
        return new PlanningSnapshot(playerId, requestGeneration,
                CraftPlanningRevision.current(), recipeId, availableItems, forcedRecipes,
                recipeGraph,
                networkFingerprint, bindingFingerprint, mainThreadOnly);
    }
}
