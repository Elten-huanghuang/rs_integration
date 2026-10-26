package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 纯规划结果与执行前路由判断，不读写世界、玩家或缓存。 */
public final class CraftPlanDecisionRules {
    private CraftPlanDecisionRules() {}

    static boolean shouldUseAsyncPurePreview(boolean pureRoute, boolean directTerminalPlan) {
        return pureRoute && !directTerminalPlan;
    }

    static boolean shouldUseAsyncPreviewRoute(
            boolean directTerminalPlan,
            boolean mainThreadOnly,
            boolean overridesProjectable,
            boolean hasMaterialLocks
    ) {
        return shouldUseAsyncPurePreview(
                !mainThreadOnly && overridesProjectable && !hasMaterialLocks,
                directTerminalPlan);
    }

    static boolean hasNbtMismatch(Map<IngredientKey, PlanResponse.Availability> materials,
                                  Map<Item, ? extends Number> itemAvailable) {
        for (Map.Entry<IngredientKey, PlanResponse.Availability> entry : materials.entrySet()) {
            PlanResponse.Availability availability = entry.getValue();
            ItemStack display = entry.getKey().stack(1);
            if (availability.missingCount() == 0 || !display.hasTag()
                    || InkFluidSupport.isToken(display)) {
                continue;
            }
            if (itemCount(itemAvailable, entry.getKey().item())
                    >= (long) availability.available() + availability.missingCount()) {
                return true;
            }
        }
        return false;
    }

    static String purePlanningFailureKey(PureRecipePlanner.Result result,
                                         Map<StackKey, ? extends Number> availableItems) {
        if (result.status() == PureRecipePlanner.Status.TIME_LIMIT
                || result.status() == PureRecipePlanner.Status.SEARCH_LIMIT
                || result.status() == PureRecipePlanner.Status.STEP_LIMIT) {
            return "rsi.plan.failure.complexity_limit";
        }
        return hasPureNbtMismatch(result.missing(), availableItems)
                ? "rsi.plan.failure.nbt_mismatch"
                : "rsi.plan.failure.missing_materials";
    }

    static String boundedPreviewFailureKey(PureRecipePlanner.Result result) {
        return result.status() == PureRecipePlanner.Status.TIME_LIMIT
                ? "rsi.plan.failure.planning_timeout"
                : "rsi.plan.failure.complexity_limit";
    }

    static String summarizePureDemands(List<ImmutableRecipeGraph.IngredientRef> demands) {
        if (demands == null || demands.isEmpty()) return "[]";
        final int maxDemands = 4;
        final int maxItems = 3;
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < Math.min(maxDemands, demands.size()); i++) {
            ImmutableRecipeGraph.IngredientRef demand = demands.get(i);
            if (demand == null) continue;
            List<String> items = demand.alternatives().stream()
                    .map(ImmutableRecipeGraph.MaterialRef::itemId)
                    .distinct()
                    .limit(maxItems)
                    .map(ResourceLocation::toString)
                    .toList();
            summaries.add("{items=" + items + ", count=" + demand.count()
                    + ", mode=" + demand.nbtMatchMode() + ", role=" + demand.role()
                    + ", alternatives=" + demand.alternatives().size() + '}');
        }
        if (demands.size() > maxDemands) {
            summaries.add("... +" + (demands.size() - maxDemands) + " demands");
        }
        return summaries.toString();
    }

    static boolean missingTouchesBlockedOutput(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Set<ResourceLocation> blockedOutputIds) {
        return missingTouchesOutputSet(missing, blockedOutputIds);
    }

    static boolean missingTouchesOutputSet(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Set<ResourceLocation> outputIds) {
        if (missing == null || missing.isEmpty() || outputIds == null
                || outputIds.isEmpty()) {
            return false;
        }
        return missing.stream().anyMatch(demand -> !demand.alternatives().isEmpty()
                && demand.alternatives().stream().allMatch(material ->
                outputIds.contains(material.itemId())));
    }

    static boolean allMissingRequireBlockedOutput(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Set<ResourceLocation> blockedOutputIds) {
        if (missing == null || missing.isEmpty() || blockedOutputIds == null
                || blockedOutputIds.isEmpty()) {
            return false;
        }
        return missing.stream().allMatch(demand -> demand != null
                && !demand.alternatives().isEmpty()
                && demand.alternatives().stream().allMatch(material ->
                blockedOutputIds.contains(material.itemId())));
    }

    static boolean shouldRetryTypedPlanning(
            PureRecipePlanner.Result result,
            Set<ResourceLocation> blockedOutputIds) {
        return result != null
                && result.feasibility() == PureRecipePlanner.Feasibility.INFEASIBLE
                && allMissingRequireBlockedOutput(result.missing(), blockedOutputIds);
    }

    static boolean requiresTypedCatalystRoute(
            boolean catalystRouteAvailable,
            boolean targetUsesReusableCatalyst,
            @Nullable PureRecipePlanner.Result purePlan,
            Set<ResourceLocation> reusableCatalystOutputIds) {
        if (!catalystRouteAvailable) return false;
        if (targetUsesReusableCatalyst || purePlan == null) return true;
        if (purePlan.feasible()) return false;
        if (purePlan.missing().isEmpty() || reusableCatalystOutputIds == null
                || reusableCatalystOutputIds.isEmpty()) {
            return true;
        }
        return purePlan.missing().stream().allMatch(demand ->
                !demand.alternatives().isEmpty()
                        && demand.alternatives().stream().allMatch(material ->
                        reusableCatalystOutputIds.contains(material.itemId())));
    }

    static boolean hasPureNbtMismatch(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Map<StackKey, ? extends Number> availableItems) {
        if (missing == null || missing.isEmpty() || availableItems == null
                || availableItems.isEmpty()) {
            return false;
        }

        Map<ImmutableRecipeGraph.MaterialRef, Long> projected =
                ImmutableRecipeGraphProjector.projectAvailability(availableItems);
        for (ImmutableRecipeGraph.IngredientRef demand : missing) {
            if (demand.nbtMatchMode() == ImmutableRecipeGraph.NbtMatchMode.ANY) continue;
            Set<ResourceLocation> itemIds = demand.alternatives().stream()
                    .map(ImmutableRecipeGraph.MaterialRef::itemId)
                    .collect(Collectors.toSet());
            long sameItemStock = projected.entrySet().stream()
                    .filter(entry -> itemIds.contains(entry.getKey().itemId())
                            && !InkFluidSupport.isToken(new ItemStack(
                                    BuiltInRegistries.ITEM.get(entry.getKey().itemId()))))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
            if (sameItemStock >= demand.count()) return true;
        }
        return false;
    }

    static boolean canUsePrecomputedPlan(@Nullable PureRecipePlanner.Result result) {
        return result != null && result.feasible();
    }

    static boolean hasCyclicPureFailure(@Nullable PureRecipePlanner.Result result) {
        return result != null
                && result.feasibility() == PureRecipePlanner.Feasibility.INFEASIBLE
                && result.failureReason() == PureRecipePlanner.FailureReason.CYCLE;
    }

    static boolean canOpenBoundedMissingPlan(
            @Nullable PureRecipePlanner.Result plan,
            PureDemandTreeInspector.Result demandTree) {
        return plan != null
                && plan.feasibility() == PureRecipePlanner.Feasibility.UNKNOWN
                && ((demandTree.status() == PureDemandTreeInspector.Status.MISSING_MATERIALS
                        && demandTree.unresolved() != null)
                    || (plan.status() == PureRecipePlanner.Status.TIME_LIMIT
                        && !plan.steps().isEmpty()
                        && !plan.missing().isEmpty()));
    }

    static boolean canUseBoundedTraceAfterTypedTimeout(
            @Nullable PureRecipePlanner.Result plan,
            PureDemandTreeInspector.Result demandTree,
            boolean directTerminalPlan) {
        return !directTerminalPlan
                && plan != null
                && !plan.missing().isEmpty()
                && demandTree != null
                && demandTree.status() == PureDemandTreeInspector.Status.MISSING_MATERIALS;
    }

    static boolean matchesAsyncRequest(
            PlanningSnapshot snapshot, UUID playerId, ResourceLocation recipeId,
            long requestGeneration, Map<ResourceLocation, ResourceLocation> forcedRecipes) {
        return matchesAsyncRequest(snapshot, playerId, recipeId, requestGeneration,
                forcedRecipes, PlanningPreferences.empty());
    }

    static boolean matchesAsyncRequest(
            PlanningSnapshot snapshot, UUID playerId, ResourceLocation recipeId,
            long requestGeneration, Map<ResourceLocation, ResourceLocation> forcedRecipes,
            PlanningPreferences preferences) {
        return snapshot.playerId().equals(playerId)
                && snapshot.recipeId().equals(recipeId)
                && snapshot.requestGeneration() == requestGeneration
                && snapshot.forcedRecipes().equals(forcedRecipes)
                && snapshot.preferences().equals(preferences);
    }

    private static long itemCount(Map<Item, ? extends Number> counts, Item item) {
        Number count = counts.get(item);
        return count == null ? 0L : count.longValue();
    }
}
