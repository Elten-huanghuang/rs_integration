package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSourceSelection;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 规划应用服务的入口。
 *
 * <p>网络包只负责解码和安排服务器线程；纯规划器与最大数量搜索通过此服务
 * 调用规划流程，避免继续把 {@link GenericCraftPacket} 当作业务依赖。</p>
 */
final class CraftPlanningApplicationService {
    private CraftPlanningApplicationService() {}

    record BindingAwareGraph(
            ImmutableRecipeGraph graph,
            Set<ResourceLocation> blockedOutputIds,
            boolean requiresTypedPlanning
    ) {
        BindingAwareGraph(ImmutableRecipeGraph graph,
                          Set<ResourceLocation> blockedOutputIds) {
            this(graph, blockedOutputIds, false);
        }
    }

    static BindingAwareGraph scopedBindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph, ResourceLocation targetRecipeId) {
        ImmutableRecipeGraph scoped =
                ImmutableRecipeGraphProjector.restrictToDependencies(graph, targetRecipeId);
        if (scoped.recipesById().values().stream().noneMatch(
                CraftPlanningApplicationService::hasContextualInputs)) {
            return bindingAwareGraph(player, scoped);
        }
        Set<ResourceLocation> unsupported = new HashSet<>();
        ImmutableRecipeGraph contextual =
                ImmutableRecipeGraphProjector.captureContextualDependencies(
                        graph, targetRecipeId, node -> {
                            if (!hasContextualInputs(node)) return null;
                            Recipe<?> recipe = CraftRecipeResolver.resolve(
                                    player.serverLevel(), node.recipeId());
                            if (recipe == null) return null;
                            List<IngredientSpec> declared =
                                    CraftPacketUtils.extractSingleOperationRecursiveIngredientSpecs(recipe);
                            if (declared == null || declared.isEmpty()) return null;
                            List<IngredientSpec> current =
                                    CraftPacketUtils.applyPlanningIngredientContext(
                                            recipe, player, declared);
                            if (current == null || current.equals(declared)) return null;
                            List<ImmutableRecipeGraph.IngredientRef> inputs = new ArrayList<>();
                            for (IngredientSpec spec : current) {
                                if (spec.isEmpty()) continue;
                                ImmutableRecipeGraph.IngredientRef input =
                                        ImmutableRecipeGraphProjector.projectIngredient(spec);
                                if (input == null) {
                                    unsupported.add(node.recipeId());
                                    return null;
                                }
                                inputs.add(input);
                            }
                            return List.copyOf(inputs);
                        });
        BindingAwareGraph filtered = bindingAwareGraph(player, contextual);
        boolean requiresTyped = unsupported.stream()
                .anyMatch(filtered.graph().recipesById()::containsKey);
        return new BindingAwareGraph(
                filtered.graph(), filtered.blockedOutputIds(), requiresTyped);
    }

    static boolean isSnapshotActive(
            ServerPlayer player, PlanningSnapshot snapshot, long previewGeneration) {
        return !player.hasDisconnected() && !player.isRemoved()
                && snapshot.requestGeneration() == previewGeneration
                && CraftPlanningRuntime.planRequests().isCurrent(
                        player.getUUID(), previewGeneration)
                && CraftPlanningRevision.isCurrent(snapshot.recipeRevision());
    }

    static void tryResolveTypedGraphOnly(
            ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput,
            OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks) {
        GenericCraftPacket.tryResolveTypedGraphOnly(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageReference, materialLocks);
    }

    static void tryBuildPlan(
            ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            int repeatCount,
            @Nullable ItemStack baseItem,
            @Nullable ItemStack clickedOutput,
            long requestId,
            long previewGeneration,
            @Nullable PureRecipePlanner.Result precomputedPlan,
            @Nullable PlanningSnapshot precomputedSnapshot,
            boolean asyncAttempted,
            boolean reuseValidatedSnapshot,
            CraftPlanResultDispatcher.Sink sink,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks,
            @Nullable AsyncPurePlanningService.RoutedPlan routedPlan,
            PlanningPreferences preferences) {
        GenericCraftPacket.tryBuildPlan(
                player, recipeId, forcedRecipes, dimension, position, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan,
                precomputedSnapshot, asyncAttempted, reuseValidatedSnapshot, sink,
                storageReference, materialLocks, routedPlan, preferences);
    }

    static void sendPurePlanningFailure(
            ServerPlayer player,
            PureRecipePlanner.Result result,
            Map<StackKey, ? extends Number> availableItems,
            Set<ResourceLocation> blockedOutputIds) {
        String key = CraftPlanDecisionRules.allMissingRequireBlockedOutput(
                result.missing(), blockedOutputIds)
                ? "rsi.plan.failure.no_bound_machine"
                : CraftPlanDecisionRules.purePlanningFailureKey(result, availableItems);
        if (!"rsi.plan.failure.missing_materials".equals(key)) {
            player.sendSystemMessage(
                    net.minecraft.network.chat.Component.translatable(key));
            return;
        }
        net.minecraft.network.chat.Component details =
                CraftFailureMessages.pureMissingSummary(result.missing());
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "rsi.generic.error.missing_materials", details));
    }

    static ModType modTypeForCachedExecution(Recipe<?> recipe) {
        ModRecipeHandler claimed = ModRecipeHandlers.claimFor(recipe).winner();
        return modTypeForCachedExecution(
                claimed == null ? null : claimed.modType(recipe),
                ModType.classifyRecipe(recipe));
    }

    static ModType modTypeForCachedExecution(
            @Nullable ModType claimedType, @Nullable ModType type) {
        ModType vanillaFamily = CraftMachineBindingSupport.preferVanillaFamilyType(
                claimedType, type, false);
        ModType resolved = CraftMachineBindingSupport.preferSpecificExecutionType(
                vanillaFamily, type, false);
        return resolved == null ? ModType.GENERIC : resolved;
    }

    static ExecutionPlanLookup findValidatedExecutionPlan(
            ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, int repeatCount,
            @Nullable ItemStack targetOutput, @Nullable ModType modType,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks,
            long confirmedPreviewRequestId) {
        PlanCache.Key exactKey = CraftPlanCacheKeys.key(
                player.getUUID(), recipeId, forcedRecipes, repeatCount,
                targetOutput, modType, materialLocks);
        PlanCache cache = CraftPlanningRuntime.planCache();
        boolean explicitPreview = confirmedPreviewRequestId > 0L;
        PlanCache.Entry entry = explicitPreview
                ? cache.peekForRequest(exactKey, confirmedPreviewRequestId)
                : cache.peekForExecution(exactKey);
        String mismatch = explicitPreview
                ? executionLeaseMismatch(entry, player, recipeId, forcedRecipes,
                repeatCount, targetOutput, modType)
                : null;
        if (mismatch != null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Generic] Rejected confirmed preview: recipeId={} request={} reason={} "
                            + "repeat={} forced={} terminal={} targetNbt={}",
                    recipeId, confirmedPreviewRequestId, mismatch, repeatCount, forcedRecipes,
                    modType == null ? "generic" : modType.id(),
                    targetOutput == null ? null : targetOutput.getTag());
            return new ExecutionPlanLookup(null, true);
        }
        boolean semanticLease = false;
        if (!explicitPreview && entry == null && materialLocks.isEmpty()) {
            PlanCache.Entry latest = cache.peekLatestForExecution(player.getUUID());
            if (latest != null && matchesExecutionLease(
                    latest, player, recipeId, forcedRecipes, repeatCount,
                    targetOutput, modType)) {
                entry = latest;
                semanticLease = true;
            }
        }
        if (entry == null) {
            return new ExecutionPlanLookup(null, false);
        }
        ResourceKey<net.minecraft.world.level.Level> levelKey = dimension != null
                ? ResourceKey.create(Registries.DIMENSION, dimension)
                : player.serverLevel().dimension();
        BlockPos lookupPos = position != null ? position : player.blockPosition();
        boolean valid = entry.snapshot().preferences().materialSources()
                .equals(MaterialSourceSelection.current())
                && CraftPlanDecisionRules.canExecuteCachedPlan(
                entry.plan(), entry.resolvedGraph())
                && PlanningStateValidator.revalidateForExecution(
                player, entry.snapshot(), entry.plan(),
                levelKey, lookupPos, storageReference);
        if (!valid) return new ExecutionPlanLookup(null, true);
        boolean consumed = explicitPreview
                ? cache.consumeForRequest(player.getUUID(), confirmedPreviewRequestId, entry)
                : semanticLease
                ? cache.consumeLatestForExecution(player.getUUID(), entry)
                : cache.consumeForExecution(exactKey, entry);
        return consumed
                ? new ExecutionPlanLookup(entry, false)
                : new ExecutionPlanLookup(null, true);
    }

    static boolean matchesExecutionLease(
            PlanCache.Entry entry, ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, int repeatCount,
            @Nullable ItemStack targetOutput, @Nullable ModType modType) {
        return executionLeaseMismatch(entry, player, recipeId, forcedRecipes,
                repeatCount, targetOutput, modType) == null;
    }

    @Nullable
    private static String executionLeaseMismatch(
            PlanCache.Entry entry, ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, int repeatCount,
            @Nullable ItemStack targetOutput, @Nullable ModType modType) {
        if (entry == null) return "preview-missing-or-key-mismatch";
        if (entry.plan() == null || entry.snapshot() == null) return "incomplete-preview";
        if (!entry.snapshot().playerId().equals(player.getUUID())) return "player-mismatch";
        if (!entry.snapshot().recipeId().equals(recipeId)) return "snapshot-recipe-mismatch";
        CraftForcedRecipeOverrides.Resolution forcedResolution =
                CraftForcedRecipeOverrides.resolve(player, forcedRecipes);
        if (!forcedResolution.valid()) return "invalid-forced-recipes";
        Map<ResourceLocation, ResourceLocation> forcedOverrides =
                forcedResolution.overrides() == null ? Map.of() : forcedResolution.overrides();
        if (!entry.snapshot().forcedRecipes().equals(forcedOverrides)) {
            return "forced-recipes-mismatch";
        }
        PlanResponse plan = entry.plan();
        if (!recipeId.toString().equals(plan.recipeId())) return "plan-recipe-mismatch";
        if (plan.repeatCount() != repeatCount) return "repeat-mismatch";
        String expectedType = modType == null ? "generic" : modType.id();
        if (plan.executionModTypeId() != null
                && !expectedType.equals(plan.executionModTypeId())) {
            return "terminal-type-mismatch";
        }
        ItemStack plannedOutput = plan.clickedOutput();
        if (plannedOutput == null || plannedOutput.isEmpty()) {
            if (targetOutput == null || targetOutput.isEmpty()) return null;
            plannedOutput = plan.targetResult();
        }
        return CraftConfirmationTarget.matches(plannedOutput, targetOutput)
                ? null : "target-item-or-shared-nbt-mismatch";
    }

    static Map<IngredientKey, PlanResponse.Availability> directMaterialView(
            DirectMaterialAllocator.Result allocation,
            Map<StackKey, Long> available) {
        Map<IngredientKey, Long> needed = new LinkedHashMap<>();
        Map<IngredientKey, Long> stored = new LinkedHashMap<>();
        for (DirectMaterialAllocator.Allocation fragment : allocation.allocations()) {
            IngredientKey key = IngredientKey.of(fragment.material().toStack());
            needed.merge(key, fragment.count(), SaturatingLongMath::add);
        }
        for (Map.Entry<StackKey, Long> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            IngredientKey key = IngredientKey.of(entry.getKey().toStack());
            if (needed.containsKey(key)) {
                stored.merge(key, entry.getValue(), SaturatingLongMath::add);
            }
        }
        Map<IngredientKey, PlanResponse.Availability> result = new LinkedHashMap<>();
        needed.forEach((key, count) -> result.put(key,
                new PlanResponse.Availability(
                        count, stored.getOrDefault(key, 0L))));
        return result;
    }

    static BindingAwareGraph bindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph) {
        return bindingAwareGraph(player, graph, false);
    }

    private static BindingAwareGraph bindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph, boolean requireIdleMachine) {
        Map<ResourceLocation, Optional<Recipe<?>>> recipes = new HashMap<>();
        Function<ResourceLocation, Recipe<?>> resolve = recipeId ->
                recipes.computeIfAbsent(recipeId,
                        id -> Optional.ofNullable(
                                CraftRecipeResolver.resolve(player.serverLevel(), id)))
                        .orElse(null);
        return filterRecipeGraph(graph, node -> {
            Recipe<?> recipe = resolve.apply(node.recipeId());
            return recipe == null
                    || CraftMachineBindingSupport.isRecipeAvailableForPlanning(player, recipe);
        }, node -> {
            ModType type = ModType.byId(node.modTypeId());
            if (!CraftExecutionPolicy.requiresBoundMachine(type)) return true;
            Recipe<?> recipe = resolve.apply(node.recipeId());
            if (recipe == null
                    || !CraftExecutionPolicy.requiresBoundMachine(recipe, type)
                    || CraftExecutionPolicy.isSmithingRecipe(recipe)) {
                return true;
            }
            return requireIdleMachine
                    ? CraftMachineBindingSupport.hasIdleBindingForExecutionType(
                    player, recipe, type)
                    : CraftMachineBindingSupport.hasBindingForExecutionType(
                    player, recipe, type);
        });
    }

    static BindingAwareGraph consumerBindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph) {
        return bindingAwareGraph(player, graph, true);
    }

    static BindingAwareGraph filterRecipeGraph(
            ImmutableRecipeGraph graph, Predicate<ImmutableRecipeGraph.RecipeNode> allowed) {
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> filtered =
                new LinkedHashMap<>();
        Set<ResourceLocation> blockedOutputs = new LinkedHashSet<>();
        graph.recipesByOutput().forEach((output, candidates) -> {
            List<ImmutableRecipeGraph.RecipeNode> usable = candidates.stream()
                    .filter(allowed).toList();
            if (!usable.isEmpty()) {
                filtered.put(output, usable);
            } else if (!candidates.isEmpty()) {
                blockedOutputs.add(output.itemId());
            }
        });
        return new BindingAwareGraph(new ImmutableRecipeGraph(filtered),
                Set.copyOf(blockedOutputs));
    }

    static BindingAwareGraph filterRecipeGraph(
            ImmutableRecipeGraph graph,
            Predicate<ImmutableRecipeGraph.RecipeNode> runtimeAvailable,
            Predicate<ImmutableRecipeGraph.RecipeNode> machineAvailable) {
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> filtered =
                null;
        Set<ResourceLocation> blockedOutputs = new LinkedHashSet<>();
        for (var entry : graph.recipesByOutput().entrySet()) {
            List<ImmutableRecipeGraph.RecipeNode> candidates = entry.getValue();
            List<ImmutableRecipeGraph.RecipeNode> usable = null;
            boolean hasRuntimeCandidate = false;
            for (int i = 0; i < candidates.size(); i++) {
                ImmutableRecipeGraph.RecipeNode candidate = candidates.get(i);
                boolean runtimeAllowed = runtimeAvailable.test(candidate);
                hasRuntimeCandidate |= runtimeAllowed;
                boolean allowed = runtimeAllowed && machineAvailable.test(candidate);
                if (!allowed && usable == null) {
                    usable = new ArrayList<>(candidates.size());
                    usable.addAll(candidates.subList(0, i));
                } else if (allowed && usable != null) {
                    usable.add(candidate);
                }
            }
            if (usable == null) continue;
            if (filtered == null) filtered = new LinkedHashMap<>(graph.recipesByOutput());
            if (!usable.isEmpty()) {
                filtered.put(entry.getKey(), List.copyOf(usable));
            } else {
                filtered.remove(entry.getKey());
                if (hasRuntimeCandidate) blockedOutputs.add(entry.getKey().itemId());
            }
        }
        if (filtered == null) return new BindingAwareGraph(graph, Set.of());
        filtered.values().removeIf(List::isEmpty);
        return new BindingAwareGraph(new ImmutableRecipeGraph(filtered),
                Set.copyOf(blockedOutputs));
    }

    private static boolean hasContextualInputs(ImmutableRecipeGraph.RecipeNode node) {
        return ModIds.ID_EMBERS_STAMPER.equals(node.modTypeId())
                || ModIds.ID_EMBERS_ALCHEMY.equals(node.modTypeId());
    }

    record ExecutionPlanLookup(@Nullable PlanCache.Entry entry, boolean rejected) {}
}
