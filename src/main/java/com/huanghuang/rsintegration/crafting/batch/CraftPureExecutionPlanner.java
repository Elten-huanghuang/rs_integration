package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
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
        if (storageEndpoint == null
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
                bindingGraph.blockedOutputIds(), "pure");
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
            @Nullable StorageReference storageReference,
            List<ScaledIngredientSpec> scaledSpecs,
            ItemStack recipeOutput,
            Map<String, ItemStack> materialLocks) {
        if (storageEndpoint == null
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
        List<ImmutableRecipeGraph.IngredientRef> inputs = physicalRootInputs(
                baseGraph, recipeId, scaledSpecs, repeatCount);
        if (inputs == null || inputs.isEmpty()) return false;

        MaterialRef outputRef = ImmutableRecipeGraphProjector.material(
                recipeOutput, recipeOutput.hasTag());
        RecipeNode syntheticRoot = new RecipeNode(
                recipeId, outputRef, Math.max(1, recipeOutput.getCount()), inputs);
        ImmutableRecipeGraph planningGraph = withPlanningRoot(baseGraph, syntheticRoot);

        submit(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageEndpoint, storageReference, materialLocks, planningGraph,
                bindingGraph.blockedOutputIds(),
                "physical");
        return true;
    }

    /**
     * 优先使用带运行时上下文的输入；TACZ 等配方可能含不可投影的自定义槽，
     * 此时回退到已冻结配方节点的标准输入，避免整条机器链退回 typed 解析。
     */
    @Nullable
    static List<ImmutableRecipeGraph.IngredientRef> physicalRootInputs(
            ImmutableRecipeGraph baseGraph, ResourceLocation recipeId,
            List<ScaledIngredientSpec> scaledSpecs, int repeatCount) {
        List<ImmutableRecipeGraph.IngredientRef> inputs = new ArrayList<>();
        boolean projectable = true;
        for (ScaledIngredientSpec spec : scaledSpecs) {
            if (spec.isEmpty()) continue;
            ImmutableRecipeGraph.IngredientRef projected =
                    ImmutableRecipeGraphProjector.projectIngredient(spec);
            if (projected == null) {
                projectable = false;
                break;
            }
            inputs.add(projected);
        }
        if (projectable && !inputs.isEmpty()) return List.copyOf(inputs);

        RecipeNode indexed = baseGraph.recipesById().get(recipeId);
        if (indexed == null) return null;
        List<ImmutableRecipeGraph.IngredientRef> canonical =
                SelfAmplifyingRecipePolicy.scaleTargetInputs(indexed, repeatCount);
        return canonical.isEmpty() ? null : canonical;
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
            Set<ResourceLocation> blockedOutputIds,
            String planKind) {
        submit(player, recipeId, forcedRecipes, dimension, position, repeatCount,
                inferMode, baseItem, targetOutput, outputDestination,
                machineSelectionMode, storageEndpoint, storageReference,
                materialLocks, planningGraph, blockedOutputIds, planKind, true);
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
            Set<ResourceLocation> blockedOutputIds,
            String planKind, boolean retryOnStale) {
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
                forcedRecipeOverrides(player, forcedRecipes), planningGraph,
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
                        GenericCraftPacket.tryResolveTypedGraphOnly(
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
                            GenericCraftPacket.tryResolveTypedGraphOnly(
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
                            GenericCraftPacket.tryResolveTypedGraphOnly(
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
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-exec] async {} plan validated recipe={} states={}; "
                                    + "execution will be rebuilt as a complete graph",
                            planKind, recipeId, result.expandedStates());
                    GenericCraftPacket.tryResolveTypedGraphOnly(
                            player, recipeId, forcedRecipes, dimension, position,
                            repeatCount, inferMode, baseItem, targetOutput,
                            outputDestination, machineSelectionMode,
                            storageReference, materialLocks);
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
                                blockedOutputIds, planKind, false);
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

    private static Map<ResourceLocation, ResourceLocation> forcedRecipeOverrides(
            ServerPlayer player, Map<String, String> forcedRecipes) {
        if (forcedRecipes.isEmpty()) return Map.of();
        CraftForcedRecipeOverrides.Resolution resolved =
                CraftForcedRecipeOverrides.resolve(player, forcedRecipes);
        if (!resolved.valid() || resolved.overrides() == null) return Map.of();
        return resolved.overrides();
    }
}
