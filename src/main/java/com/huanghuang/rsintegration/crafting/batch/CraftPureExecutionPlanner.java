package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator.StalePlanningResultException;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** 将无预览缓存的执行请求提交给不可变纯配方规划器。 */
final class CraftPureExecutionPlanner {
    private CraftPureExecutionPlanner() {}

    /**
     * 没有预览缓存时通过不可变规划器提交普通工作台执行。
     * 旧路径直接进入 typed resolver，会让复杂 CraftTweaker 配方过早超时。
     */
    static boolean queue(
            ServerPlayer player, CraftingRecipe recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference) {
        if (storageEndpoint == null || !forcedRecipes.isEmpty()
                || !RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) return false;

        ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                recipe, player.serverLevel().registryAccess());
        if (recipeOutput.isEmpty()) return false;

        final GenericCraftPacket.BindingAwareGraph bindingGraph;
        try {
            bindingGraph = GenericCraftPacket.scopedBindingAwareGraph(
                    player, ImmutableRecipeGraphProjector.capture(player.serverLevel()), recipeId);
        } catch (RuntimeException unavailable) {
            RSIntegrationMod.debug("[RSI-exec] immutable graph unavailable for {}: {}",
                    recipeId, unavailable.toString());
            return false;
        }
        ImmutableRecipeGraph recipeGraph = bindingGraph.graph();
        if (!recipeGraph.recipesById().containsKey(recipeId)) return false;

        submit(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageEndpoint, storageReference, Map.of(), recipeGraph,
                bindingGraph.blockedOutputIds(), false,
                CraftTerminalSteps.genericTerminalStep(recipeId, repeatCount), "pure");
        return true;
    }

    /**
     * 机器终端在服务端线程解析超时时转入后台规划。
     * 合成根只用于规划，执行仍追加 typed 机器终端，不能绕过对应 delegate。
     */
    static boolean queuePhysical(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference, List<IngredientSpec> scaledSpecs,
            ItemStack recipeOutput, ModType terminalType,
            Map<String, ItemStack> materialLocks) {
        if (storageEndpoint == null || !forcedRecipes.isEmpty()
                || !RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()
                || recipeOutput == null || recipeOutput.isEmpty()) return false;

        final GenericCraftPacket.BindingAwareGraph bindingGraph;
        try {
            bindingGraph = GenericCraftPacket.scopedBindingAwareGraph(
                    player, ImmutableRecipeGraphProjector.capture(player.serverLevel()), recipeId);
        } catch (RuntimeException unavailable) {
            RSIntegrationMod.debug(
                    "[RSI-exec] immutable graph unavailable for physical recipe {}: {}",
                    recipeId, unavailable.toString());
            return false;
        }
        ImmutableRecipeGraph baseGraph = bindingGraph.graph();
        List<ImmutableRecipeGraph.IngredientRef> inputs = new ArrayList<>();
        for (IngredientSpec spec : scaledSpecs) {
            if (spec.isEmpty()) continue;
            ImmutableRecipeGraph.IngredientRef projected =
                    ImmutableRecipeGraphProjector.projectIngredient(spec);
            if (projected == null) return false;
            inputs.add(projected);
        }
        if (inputs.isEmpty()) return false;

        MaterialRef outputRef = ImmutableRecipeGraphProjector.material(
                recipeOutput, recipeOutput.hasTag());
        RecipeNode syntheticRoot = new RecipeNode(
                recipeId, outputRef, Math.max(1, recipeOutput.getCount()), inputs);
        ImmutableRecipeGraph planningGraph = withPlanningRoot(baseGraph, syntheticRoot);

        submit(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageEndpoint, storageReference, materialLocks, planningGraph,
                bindingGraph.blockedOutputIds(), true,
                CraftTerminalSteps.backgroundPhysicalTerminalStep(
                        recipeId, terminalType, inferMode, repeatCount),
                "physical");
        return true;
    }

    static ImmutableRecipeGraph withPlanningRoot(ImmutableRecipeGraph baseGraph, RecipeNode root) {
        // 依赖桶已经不可变，只复制新增根节点所在的桶。
        Map<MaterialRef, List<RecipeNode>> projected = new HashMap<>();
        baseGraph.recipesByOutput().forEach(projected::put);
        List<RecipeNode> candidates = new ArrayList<>(
                projected.getOrDefault(root.output(), List.of()));
        candidates.add(root);
        projected.put(root.output(), candidates);
        return new ImmutableRecipeGraph(projected);
    }

    private static void submit(
            ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks, ImmutableRecipeGraph planningGraph,
            Set<ResourceLocation> blockedOutputIds, boolean excludePlannedRoot,
            ResolutionStep terminalStep, String planKind) {
        submit(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageEndpoint, storageReference, materialLocks, planningGraph,
                blockedOutputIds, excludePlannedRoot, terminalStep, planKind, true);
    }

    private static void submit(
            ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks, ImmutableRecipeGraph planningGraph,
            Set<ResourceLocation> blockedOutputIds, boolean excludePlannedRoot,
            ResolutionStep terminalStep, String planKind, boolean retryOnStale) {
        int plannedExecutions = Math.max(1, repeatCount);
        MaterialSources.invalidateFor(player, storageEndpoint);
        Map<StackKey, Long> available = MaterialSources.listAllAvailable(player, storageEndpoint);
        var routeInputs = new AsyncPurePlanningService.RouteInputs(
                ImmutableRecipeGraphProjector.projectAvailability(available),
                RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.get(),
                RSIntegrationConfig.ENABLE_CATALYST_RECIPE_PREFERENCE.get()
                        ? RecipeIndex.reusableCatalystOutputIds(player.serverLevel()) : Set.of(),
                RecipeIndex.reusableCatalystRecipeIds(player.serverLevel()),
                RecipeIndex.pureIncompatibleOutputIds(player.serverLevel()));
        ResourceLocation effectiveDimension = dimension != null
                ? dimension : player.serverLevel().dimension().location();
        BlockPos effectivePosition = position != null ? position : player.blockPosition();
        StorageReference selectedReference = storageEndpoint.session().reference();
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(
                player.getUUID(), 0L, recipeId, available,
                Map.of(), planningGraph,
                PlanningStateValidator.networkFingerprint(selectedReference, available),
                PlanningStateValidator.bindingFingerprint(player,
                        ResourceKey.create(Registries.DIMENSION, effectiveDimension),
                        effectivePosition),
                blockedOutputIds, false);

        CraftPlanningRuntime.planRequests().submitRouted(
                snapshot, routeInputs, plannedExecutions,
                player.getServer()::execute, RSIntegrationConfig.CRAFTING_MAX_STEPS.get(),
                completed -> {
                    PureDemandTreeInspector.Result route = completed.inspection();
                    if (!route.backgroundCompatible() || completed.plan() == null) {
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-exec] immutable {} route unavailable recipe={} status={} unresolved={} catalystRoute={}",
                                planKind, recipeId, route.status(), route.unresolved(),
                                route.catalystRouteAvailable());
                        GenericCraftPacket.tryResolveTypedFallbackOnly(
                                player, recipeId, forcedRecipes, dimension, position,
                                repeatCount, inferMode, baseItem, targetOutput,
                                outputDestination, machineSelectionMode,
                                storageReference, materialLocks);
                        return;
                    }
                    PureRecipePlanner.Result result = completed.plan();
                    if (!result.feasible()) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] {} planner rejected recipe={} status={} feasibility={} missing={} states={} backtracks={} memoHits={} directReservationAttempted=true",
                                planKind, recipeId, result.status(), result.feasibility(),
                                result.missing(), result.expandedStates(), result.backtracks(),
                                result.memoHits());
                        if (CraftPlanDecisionRules.shouldRetryTypedPlanning(
                                result, snapshot.bindingBlockedOutputIds())) {
                            RSIntegrationMod.LOGGER.info(
                                    "[RSI-exec] retrying typed {} execution after pure planner rejection recipe={}",
                                    planKind, recipeId);
                            GenericCraftPacket.tryResolveTypedFallbackOnly(
                                    player, recipeId, forcedRecipes, dimension, position,
                                    repeatCount, inferMode, baseItem, targetOutput,
                                    outputDestination, machineSelectionMode,
                                    storageReference, materialLocks);
                            return;
                        }
                        if (CraftPlanDecisionRules.missingTouchesOutputSet(
                                result.missing(),
                                RecipeIndex.pureIncompatibleOutputIds(player.serverLevel()))) {
                            RSIntegrationMod.debug(
                                    "[RSI-exec] retrying typed {} execution for unprojected dependency recipe={}",
                                    planKind, recipeId);
                            GenericCraftPacket.tryResolveTypedFallbackOnly(
                                    player, recipeId, forcedRecipes, dimension, position,
                                    repeatCount, inferMode, baseItem, targetOutput,
                                    outputDestination, machineSelectionMode,
                                    storageReference, materialLocks);
                            return;
                        }
                        GenericCraftPacket.sendPurePlanningFailure(
                                player, result, snapshot.availableItems(),
                                snapshot.bindingBlockedOutputIds());
                        return;
                    }
                    if (!PlanningStateValidator.revalidateForExecution(
                            player, snapshot,
                            ResourceKey.create(Registries.DIMENSION, effectiveDimension),
                            effectivePosition, selectedReference)) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.plan.failure.missing_materials"));
                        return;
                    }
                    List<ResolutionStep> steps = new ArrayList<>(
                            PurePlanAdapter.toResolutionSteps(result, planningGraph));
                    if (excludePlannedRoot) {
                        steps.removeIf(step -> step.recipeId().equals(recipeId));
                    }
                    GenericCraftPacket.ensureTerminalRecipeStep(steps, terminalStep);
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-exec] async {} plan ready recipe={} steps={} states={}",
                            planKind, recipeId, steps.size(), result.expandedStates());
                    GenericCraftPacket.launchAsyncChain(
                            player, steps, LegacyExecutionMetrics.Reason.PLANNED_CHAIN,
                            storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dimension, position, inferMode, baseItem, targetOutput,
                            outputDestination, machineSelectionMode, materialLocks);
                },
                failure -> {
                    if (failure instanceof StalePlanningResultException && retryOnStale) {
                        MaterialSources.invalidateFor(player, storageEndpoint);
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-exec] inventory changed during async {} planning; "
                                        + "retrying once with a fresh snapshot recipe={}",
                                planKind, recipeId);
                        submit(
                                player, recipeId, forcedRecipes, dimension, position,
                                repeatCount, inferMode, baseItem, targetOutput,
                                outputDestination, machineSelectionMode, storageEndpoint,
                                storageReference, materialLocks, planningGraph,
                                blockedOutputIds, excludePlannedRoot, terminalStep,
                                planKind, false);
                        return;
                    }
                    if (!(failure instanceof CancellationException)) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] async {} planning failed recipe={} directReservationAttempted=true failure={}",
                                planKind, recipeId, failure.toString(), failure);
                        String key = failure instanceof CraftingPlanningTimeoutException
                                ? "rsi.plan.failure.complexity_limit"
                                : "rsi.plan.failure.missing_materials";
                        player.sendSystemMessage(Component.translatable(key));
                    }
                });
        RSIntegrationMod.debug("[RSI-exec] queued async {} planning recipe={} repeat={}",
                planKind, recipeId, repeatCount);
    }
}
