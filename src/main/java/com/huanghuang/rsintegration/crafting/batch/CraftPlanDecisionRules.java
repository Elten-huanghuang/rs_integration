package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PurePlanningCoverage;
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

    static boolean canExecuteCachedPlan(PlanResponse plan,
                                        @Nullable CraftPlanGraph resolvedGraph) {
        if (plan.success()) return true;
        // 机器租用只影响即时可用性，完整计划可交给调度器等待；执行前仍须复验库存和绑定。
        return plan.outcome() == PlanOutcome.RUNTIME_UNAVAILABLE
                && !plan.executionBlocked()
                && !plan.diagnostics().isEmpty()
                && plan.diagnostics().stream().allMatch(diagnostic ->
                        diagnostic.code() == IntegrationDiagnosticCode.MACHINES_LEASED)
                && plan.missing().isEmpty()
                && plan.materials().values().stream()
                        .allMatch(material -> material.missingCount() == 0)
                && plan.stepIssues().values().stream().noneMatch(PlanResponse.StepIssue::blocked)
                && plan.graph() != null
                && plan.graph().unresolved().isEmpty()
                && plan.graph().roots().stream().allMatch(root -> root.unresolvedQuantity() == 0)
                && resolvedGraph != null
                && resolvedGraph.unresolvedDemands().isEmpty()
                && resolvedGraph.rootDemands().stream()
                        .allMatch(root -> root.unresolvedQuantity() == 0);
    }

    enum PlannerAction { RUN_PURE, RUN_TYPED, USE_PURE_RESULT, REPORT_FAILURE }

    enum RouteReason {
        PROJECTABLE, MAIN_THREAD_REQUIRED, MATERIAL_LOCK, FORCED_RECIPE_UNPROJECTED,
        TARGET_NOT_PROJECTED, TYPED_CATALYST_REQUIRED, UNPROJECTED_DEPENDENCY,
        COVERAGE_UNCERTAIN, BINDING_BLOCKED_DEPENDENCY, PURE_SUCCESS,
        PROVEN_INFEASIBLE, BUDGET_EXHAUSTED, PURE_NOT_RUN
    }

    record RouteDecision(PlannerAction action, RouteReason reason) {}

    /** 首次准入与计算完成分开，避免用 null 结果同时表示“尚未提交”和“未能计算”。 */
    static RouteDecision initialRoute(boolean mainThreadOnly, boolean hasMaterialLocks,
                                      boolean forcedRecipesProjected, boolean targetProjected) {
        if (mainThreadOnly) return typed(RouteReason.MAIN_THREAD_REQUIRED);
        if (hasMaterialLocks) return typed(RouteReason.MATERIAL_LOCK);
        if (!forcedRecipesProjected) return typed(RouteReason.FORCED_RECIPE_UNPROJECTED);
        if (!targetProjected) return typed(RouteReason.TARGET_NOT_PROJECTED);
        return new RouteDecision(PlannerAction.RUN_PURE, RouteReason.PROJECTABLE);
    }

    static RouteDecision completedRoute(PlanningSnapshot snapshot, boolean hasMaterialLocks,
                                         boolean forcedRecipesProjected,
                                         PureDemandTreeInspector.Result inspection,
                                         @Nullable PureRecipePlanner.Result result,
                                         PurePlanningCoverage.Probe coverage,
                                         Set<ResourceLocation> catalystOutputs,
                                         boolean targetUsesReusableCatalyst) {
        RouteDecision eligibility = initialRoute(snapshot.mainThreadOnly(), hasMaterialLocks,
                forcedRecipesProjected,
                inspection.status() != PureDemandTreeInspector.Status.TARGET_NOT_PROJECTED);
        if (eligibility.action() == PlannerAction.RUN_TYPED) return eligibility;
        if (requiresTypedCatalystRoute(inspection.catalystRouteAvailable(),
                targetUsesReusableCatalyst, result, catalystOutputs)) {
            return typed(RouteReason.TYPED_CATALYST_REQUIRED);
        }
        if (!inspection.backgroundCompatible()) return typed(RouteReason.UNPROJECTED_DEPENDENCY);
        if (result == null) return typed(RouteReason.PURE_NOT_RUN);
        if (result.feasible()) return new RouteDecision(PlannerAction.USE_PURE_RESULT, RouteReason.PURE_SUCCESS);
        // 成功解不受未使用生产者的覆盖缺口影响；失败解必须保留未知与已证实缺口的区别。
        if (coverage.status() == PurePlanningCoverage.Status.GAP) {
            return typed(RouteReason.UNPROJECTED_DEPENDENCY);
        }
        if (coverage.status() == PurePlanningCoverage.Status.UNCERTAIN) {
            return typed(RouteReason.COVERAGE_UNCERTAIN);
        }
        if (shouldRetryTypedPlanning(result, snapshot.bindingBlockedOutputIds())) {
            return typed(RouteReason.BINDING_BLOCKED_DEPENDENCY);
        }
        if (result.feasibility() == PureRecipePlanner.Feasibility.UNKNOWN) {
            return new RouteDecision(PlannerAction.REPORT_FAILURE, RouteReason.BUDGET_EXHAUSTED);
        }
        return new RouteDecision(PlannerAction.USE_PURE_RESULT, RouteReason.PROVEN_INFEASIBLE);
    }

    private static RouteDecision typed(RouteReason reason) {
        return new RouteDecision(PlannerAction.RUN_TYPED, reason);
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
            boolean directTerminalPlan,
            PurePlanningCoverage.Probe coverage) {
        return !directTerminalPlan
                && coverage.status() != PurePlanningCoverage.Status.GAP
                && coverage.status() != PurePlanningCoverage.Status.UNCERTAIN
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
