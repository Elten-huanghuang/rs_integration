package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialAccess;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.CraftPlanDecisionRules.PlannerAction;
import com.huanghuang.rsintegration.crafting.batch.CraftPlanDecisionRules.RouteDecision;
import com.huanghuang.rsintegration.crafting.batch.CraftPlanDecisionRules.RouteReason;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingPlanningLimitException;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.MaterialSourceSelection;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlanEstimator;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlan;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator.StalePlanningResultException;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.ForcedRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningRouteHint;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PurePlanGraphAdapter;
import com.huanghuang.rsintegration.crafting.planning.PurePlanningCoverage;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.mods.aether.AetherFurnaceBatchDelegate;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsDynamicApparatusRecipe;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotCategoryPlanning;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotInventory;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.mods.embers.EmbersPlanInfo;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyRecipeHandler;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.mods.immortalersdelight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRRecipeHandler;
import com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.machine.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.viewer.LongTravailCrownRecipes;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.util.Reflect;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.TickTask;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;


/**
 * 规划应用服务的入口。
 *
 * <p>组织预览快照、规划路由与结果组装，并提供确认预览所需的校验。</p>
 */
final class CraftPlanningApplicationService {
    private static final int IMMEDIATE_BINDING_SCAN_RECIPES = 64;
    private static final LogSampler PREVIEW_FAILURE_LOGS = new LogSampler(2_000);

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
            player.sendSystemMessage(Component.translatable(key));
            return;
        }
        Component details =
                CraftFailureMessages.pureMissingSummary(result.missing());
        player.sendSystemMessage(Component.translatable(
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
        ResourceKey<Level> levelKey = dimension != null
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
        PreviewBindingCapture capture = bindingCapture(player, graph, requireIdleMachine);
        capture.advance(() -> true);
        return capture.result();
    }

    private static PreviewBindingCapture bindingCapture(
            ServerPlayer player, ImmutableRecipeGraph graph, boolean requireIdleMachine) {
        Map<ResourceLocation, Optional<Recipe<?>>> recipes = new HashMap<>();
        Function<ResourceLocation, Recipe<?>> resolve = recipeId ->
                recipes.computeIfAbsent(recipeId,
                        id -> Optional.ofNullable(
                                CraftRecipeResolver.resolve(player.serverLevel(), id)))
                        .orElse(null);
        return new PreviewBindingCapture(graph, node -> {
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
    private static void recordMissingMaterial(
            List<String> missing,
            ImmutableRecipeGraph.IngredientRef unresolved) {
        if (unresolved.alternatives().isEmpty()) return;
        ImmutableRecipeGraph.MaterialRef material = unresolved.alternatives().get(0);
        missing.add(CraftPacketUtils.missingMaterialKey(material.itemId()));
    }

    static boolean hasSelectedCraftingPlanner(boolean pure, boolean directTerminal,
                                               boolean typed) {
        return pure || directTerminal || typed;
    }

    private record CompletedTypedPreview(CraftPlanGraph graph, List<String> missing,
                                         ItemStack output, List<TypedRootKey> roots,
                                         Map<Integer, ParallelPlan> parallelPlans,
                                         Map<ParallelPlanEstimator.DisplayKey, ParallelPlan> displayPlans) {}

    private record TypedRootKey(Class<?> predicateType, Object predicate, long count,
                                InputConsumption consumption) {}

    private static void scheduleBindingCapture(
            ServerPlayer player, ResourceLocation recipeId, long generation,
            PreviewBindingCapture capture, PlanningSnapshot snapshot,
            BooleanSupplier sameContext, Consumer<PlanningSnapshot> completed,
            CraftPlanResultDispatcher.Sink sink) {
        schedulePreviewWork(player, recipeId, generation, snapshot, sameContext,
                budget -> {
                    long start = System.nanoTime();
                    try {
                        return capture.advance(() -> budget - (System.nanoTime() - start) > 0L);
                    } finally {
                        PerformanceMonitor.recordPlanningLatency(
                                PerformanceMonitor.PlanningLatencyPhase.BINDING_CAPTURE, System.nanoTime() - start);
                    }
                }, () -> {
                    var result = capture.result();
                    completed.accept(snapshot.withRecipeGraph(result.graph(), result.blockedOutputIds()));
                }, sink);
    }

    private static void schedulePreviewWork(
            ServerPlayer player, ResourceLocation recipeId, long generation, PlanningSnapshot snapshot,
            BooleanSupplier sameContext, LongPredicate advance, Runnable completed,
            CraftPlanResultDispatcher.Sink sink) {
        var queue = CraftPlanningRuntime.typedPreviewRequests();
        class CaptureTask implements Runnable {
            private boolean ready;

            void enqueue() {
                Runnable busy = () -> sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY));
                var offered = queue.offer(new TypedPreviewAdmissionQueue.Request(
                        player.getUUID(), generation, System.nanoTime(), this, busy, failure -> {
                    RSIntegrationMod.LOGGER.error("[RSI-plan] 预览分片失败：recipe={}", recipeId, failure);
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.INTERNAL_ERROR));
                }));
                if (offered == TypedPreviewAdmissionQueue.OfferResult.FULL) busy.run();
            }

            @Override
            public void run() {
                if (!isSnapshotActive(player, snapshot, generation) || !sameContext.getAsBoolean()) {
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.STALE_PLAN));
                    return;
                }
                try (var ignored = snapshot.preferences().materialSources().enter()) {
                    if (!ready) {
                        long budget = queue.remainingTickNanos();
                        if (budget > 0L) ready = advance.test(budget);
                        enqueue();
                        return;
                    }
                    completed.run();
                }
            }
        }
        new CaptureTask().enqueue();
    }

    private static List<TypedRootKey> typedRootKeys(List<ScaledIngredientSpec> roots) {
        return roots.stream().filter(root -> root != null && !root.isEmpty())
                .map(root -> {
                    Object predicate;
                    try {
                        JsonElement serialized = root.ingredient().toJson();
                        predicate = serialized.deepCopy();
                    } catch (RuntimeException | LinkageError unsupported) {
                        // 无法序列化的原生谓词只认同一实例，不能当作相同的材料集合。
                        predicate = root.ingredient();
                    }
                    return new TypedRootKey(root.ingredient().getClass(), predicate, root.count(), root.consumption());
                })
                .toList();
    }

    private static void scheduleTypedPreview(ServerPlayer player, ResourceLocation recipeId, long generation,
                                             PlanningSnapshot snapshot, CraftPlanResultDispatcher.Sink sink,
                                             CraftingResolver.TypedPreview job, BooleanSupplier sameWorldContext,
                                             Consumer<CompletedTypedPreview> completed,
                                             List<String> missing, ItemStack output, List<TypedRootKey> roots) {
        var queue = CraftPlanningRuntime.typedPreviewRequests();
        class PreviewTask implements Runnable {
            private boolean solved;
            private CraftPlanGraph graph;
            private long computeNanos;
            private long maxSliceNanos;
            private ParallelPlanEstimator.Request.GraphEstimate estimates;
            private Map<Integer, ParallelPlan> parallelPlans;

            void enqueue() {
                var offered = queue.offer(new TypedPreviewAdmissionQueue.Request(player.getUUID(), generation,
                        System.nanoTime(), this, () -> sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY)), this::failed));
                if (offered == TypedPreviewAdmissionQueue.OfferResult.FULL) {
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY));
                }
            }

            @Override
            public void run() {
                if (!isSnapshotActive(player, snapshot, generation) || !sameWorldContext.getAsBoolean()) {
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.STALE_PLAN));
                    return;
                }
                try (var scope = snapshot.preferences().materialSources().enter()) {
                    if (!solved) {
                        long budget = queue.remainingTickNanos();
                        if (budget > 0L) {
                            long start = System.nanoTime();
                            try {
                                solved = job.advance(budget);
                            } finally {
                                long elapsed = System.nanoTime() - start;
                                computeNanos += elapsed;
                                maxSliceNanos = Math.max(maxSliceNanos, elapsed);
                                PerformanceMonitor.recordPlanningLatency(
                                        PerformanceMonitor.PlanningLatencyPhase.TYPED_SLICE, elapsed);
                            }
                        }
                        // 整图校验和响应组装分到后续 tick，避免与求解的最后切片叠加。
                        enqueue();
                        return;
                    }
                    if (graph == null) {
                        long start = System.nanoTime();
                        try {
                            graph = job.graph();
                        } finally {
                            long elapsed = System.nanoTime() - start;
                            PerformanceMonitor.recordTypedResolver(computeNanos + elapsed);
                            PerformanceMonitor.recordPlanningLatency(
                                    PerformanceMonitor.PlanningLatencyPhase.TYPED_GRAPH, elapsed);
                        }
                        enqueue();
                        return;
                    }
                    if (parallelPlans == null) {
                        long start = System.nanoTime();
                        try {
                            if (estimates == null) {
                                var estimator = new ParallelPlanEstimator.Request(player,
                                        aggregateAvailableItems(snapshot.availableItems()));
                                estimates = estimator.start(graph);
                            }
                            long budget = queue.remainingTickNanos();
                            if (budget > 0L && estimates.advance(budget)) parallelPlans = estimates.result();
                        } finally {
                            PerformanceMonitor.recordPlanningLatency(
                                    PerformanceMonitor.PlanningLatencyPhase.TYPED_ESTIMATE, System.nanoTime() - start);
                        }
                        // 每个 tick 只推进一批机器探针，不与最终的展示组装叠加。
                        enqueue();
                        return;
                    }
                    long start = System.nanoTime();
                    try {
                        completed.accept(new CompletedTypedPreview(graph, missing, output, roots,
                                parallelPlans, estimates.displayResult()));
                    } finally {
                        PerformanceMonitor.recordPlanningLatency(
                                PerformanceMonitor.PlanningLatencyPhase.TYPED_ASSEMBLY, System.nanoTime() - start);
                    }
                }
            }

            private void failed(Throwable failure) {
                if (failure instanceof CraftingPlanningLimitException) {
                    RSIntegrationMod.LOGGER.debug("[RSI-plan] 兼容规划达到上限：recipe={} {}", recipeId, failure.getMessage());
                    sink.error(PlanOutcome.BUDGET_EXHAUSTED,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.COMPLEXITY_LIMIT));
                } else if (failure instanceof CraftingPlanningTimeoutException) {
                    PerformanceMonitor.recordResolveTimeout();
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-plan] 类型规划超时 recipe={} request={} activeMs={} maxSliceMs={} stockKeys={} projectedRecipes={} {}",
                            recipeId, generation, computeNanos / 1_000_000L,
                            maxSliceNanos / 1_000_000L, snapshot.availableItems().size(),
                            snapshot.recipeGraph().recipesById().size(), job.timeoutSummary());
                    sink.error(PlanOutcome.BUDGET_EXHAUSTED,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNING_TIMEOUT));
                } else {
                    RSIntegrationMod.LOGGER.error("[RSI-plan] 兼容规划失败：recipe={}", recipeId, failure);
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.INTERNAL_ERROR));
                }
            }
        }
        new PreviewTask().enqueue();
    }

    static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable BlockPos pos,
                                      int repeatCount, @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks,
                                      PlanningPreferences preferences) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false,
                false, CraftPlanResultDispatcher.networkSink(player, requestId, previewGeneration), storageReference,
                materialLocks, null, preferences);
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable PureRecipePlanner.Result precomputedPlan,
                                      @Nullable PlanningSnapshot precomputedSnapshot,
                                      boolean asyncAttempted,
                                      boolean reuseValidatedSnapshot,
                                      CraftPlanResultDispatcher.Sink sink,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks,
                                      PlanningPreferences preferences) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan, precomputedSnapshot,
                asyncAttempted, reuseValidatedSnapshot, sink,
                storageReference, materialLocks, null, preferences);
    }

    static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                             Map<String, String> forcedRecipes,
                             @Nullable ResourceLocation dim,
                             @Nullable BlockPos pos, int repeatCount,
                             @Nullable ItemStack baseItem, @Nullable ItemStack clickedOutput,
                             long requestId, long previewGeneration,
                             @Nullable PureRecipePlanner.Result precomputedPlan,
                             @Nullable PlanningSnapshot precomputedSnapshot,
                             boolean asyncAttempted,
                             boolean reuseValidatedSnapshot, CraftPlanResultDispatcher.Sink sink,
                             @Nullable StorageReference storageReference,
                             Map<String, ItemStack> materialLocks,
                             @Nullable AsyncPurePlanningService.RoutedPlan routedPlan,
                             PlanningPreferences preferences) {
        PlanningPreferences selected = preferences == null ? PlanningPreferences.empty() : preferences;
        try (MaterialSourceSelection.Scope ignored = selected.materialSources().enter()) {
            tryBuildPlanWithSources(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                    clickedOutput, requestId, previewGeneration, precomputedPlan, precomputedSnapshot,
                    asyncAttempted, reuseValidatedSnapshot, sink, storageReference, materialLocks,
                    routedPlan, selected, null);
        }
    }

    private static void tryBuildPlanWithSources(ServerPlayer player, ResourceLocation recipeId,
                             Map<String, String> forcedRecipes,
                             @Nullable ResourceLocation dim, @Nullable BlockPos pos, int repeatCount,
                             @Nullable ItemStack baseItem, @Nullable ItemStack clickedOutput,
                             long requestId, long previewGeneration,
                             @Nullable PureRecipePlanner.Result precomputedPlan,
                             @Nullable PlanningSnapshot precomputedSnapshot, boolean asyncAttempted,
                             boolean reuseValidatedSnapshot, CraftPlanResultDispatcher.Sink sink,
                             @Nullable StorageReference storageReference,
                             Map<String, ItemStack> materialLocks,
                             @Nullable AsyncPurePlanningService.RoutedPlan routedPlan,
                             PlanningPreferences preferences,
                             @Nullable CompletedTypedPreview completedTyped) {
        long planStartNanos = System.nanoTime();
        PlanningPreferences safePreferences = preferences == null
                ? PlanningPreferences.empty() : preferences;
        if (QuestSubmissionTargetIds
                .isQuestSubmission(recipeId)) {
            // FTB quest targets are synthetic JEI entries. Their dedicated
            // request already built the plan; never send them through the
            // ordinary recipe resolver during repeat-count refreshes.
            RSIntegrationMod.debug("[RSI-Generic] Skipping synthetic FTB quest plan refresh: {}",
                    recipeId);
            return;
        }
        if (!(sink instanceof CraftPreviewInventoryRefresh)) {
            ResourceKey<Level> refreshDimension = dim == null ? player.serverLevel().dimension()
                    : ResourceKey.create(Registries.DIMENSION, dim);
            BlockPos refreshPosition = pos == null ? player.blockPosition() : pos;
            CraftPlanResultDispatcher.Sink refreshing = new CraftPreviewInventoryRefresh(sink,
                    RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES),
                    () -> !player.hasDisconnected() && !player.isRemoved()
                            && CraftPlanningRuntime.planRequests().isCurrent(player.getUUID(), previewGeneration),
                    (snapshot, plan) -> PlanningStateValidator.previewInventoryChanged(player, snapshot, plan,
                            refreshDimension, refreshPosition,
                            plan.storageReference() == null ? storageReference : plan.storageReference()),
                    snapshot -> {
                        CraftPlanningRuntime.planCache().invalidateSnapshot(snapshot);
                        MaterialSources.invalidateFor(player);
                    },
                    task -> player.getServer().tell(new TickTask(player.getServer().getTickCount(), task)),
                    (previousPlan, nextSink) -> tryBuildPlan(player, recipeId, forcedRecipes, dim, pos,
                            repeatCount, baseItem, clickedOutput, requestId, previewGeneration,
                            null, null, false, false, nextSink,
                            previousPlan.storageReference() == null ? storageReference : previousPlan.storageReference(),
                            materialLocks, null, safePreferences));
            tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                    clickedOutput, requestId, previewGeneration, precomputedPlan, precomputedSnapshot,
                    asyncAttempted, reuseValidatedSnapshot, refreshing, storageReference, materialLocks,
                    routedPlan, safePreferences);
            return;
        }
        Recipe<?> recipe = CraftRecipeResolver.resolve(player.serverLevel(), recipeId);
        if (recipe == null) {
            sink.error(PlanOutcome.NO_RECIPE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.RECIPE_NOT_FOUND,
                            recipeId.toString()));
            return;
        }
        if (HistoryStagesCompat.isRecipeLocked(recipe, player)) {
            sink.error(PlanOutcome.NO_RECIPE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.RECIPE_STAGE_MISSING));
            return;
        }
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !CraftPacketUtils.isCraftingRecipeAvailable(craftingRecipe, player)) {
            sink.error(PlanOutcome.NO_RECIPE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.RECIPE_STAGE_MISSING));
            return;
        }
        ModType previewModType = CraftMachineBindingSupport.resolveExecutionModType(player, recipe, dim, pos);
        if (CraftExecutionPolicy.requiresBoundMachine(recipe, previewModType)
                && !CraftExecutionPolicy.isSmithingRecipe(recipe)
                && !CraftMachineBindingSupport.hasBindingForExecutionType(player, recipe, previewModType)) {
            CraftMachineBindingSupport.logBindingRejection("preview-entry", player, recipe, previewModType, dim, pos);
            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.NO_BOUND_MACHINE));
            return;
        }

        // FA ApplyModifierRecipe: if JEI provides a base item, build a full
        // recursive plan so the player can see what materials are needed.
        // Without a base item (e.g. manual /craft command), redirect to the
        // smithing table GUI instead.
        boolean faRecipe = OpenBoundMachineGuiPacket.isFaApplyModifier(recipe);
        if (faRecipe && (baseItem == null || baseItem.isEmpty())) {
            sink.error(PlanOutcome.NO_RECIPE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.SMITHING_BASE_REQUIRED));
            return;
        }
        boolean arsDynamic = ArsDynamicApparatusRecipe.isSupported(recipe);
        boolean goetyDynamic = GoetyDynamicRitualRecipe.isSupported(recipe);
        if ((arsDynamic || goetyDynamic) && (clickedOutput == null || clickedOutput.isEmpty())) {
            sink.error(PlanOutcome.NO_RECIPE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE,
                            recipe.getClass().getSimpleName()));
            return;
        }

        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.AUTO_CRAFT_DISABLED));
            return;
        }

        // Determine ingredients, output, and mod type for both vanilla and mod recipes
        List<ScaledIngredientSpec> recipeSpecs;
        ItemStack targetOutput;
        ModType recipeModType = previewModType;
        CraftPlanStorageService.Context storageContext = null;

        // Arcane Iterator per-level chain: when the player clicks a level-N enchant
        // book (N>=2), the machine reaches it by leveling a book one step per craft
        // (plain → I → ... → N). levelBooks[k] holds the book at level k+1; a non-null
        // array here drives the target-step block to emit N chained PlanSteps so the
        // plan tree shows the full leveling chain. Null for every other recipe.
        ItemStack[] levelBooks = null;
        ItemStack selectedSmithingBase = ItemStack.EMPTY;
        // Highest matching intermediate book already present in RS/player storage.
        // Zero means the chain must start from a plain book.
        int iteratorStartLevel = 0;

        // Un-expanded ingredients for PlanStep display (avoids 64× icon spam).
        List<Ingredient> displayIngredients;
        List<DemandRole> displayInputRoles;

        if (faRecipe) {
            // Build ingredient specs from FA recipe: template + baseItem + addition
            try {
                Method getTemplate = recipe.getClass().getMethod("getTemplate");
                Method getAddition = recipe.getClass().getMethod("getAddition");
                Ingredient template = (Ingredient) getTemplate.invoke(recipe);
                Ingredient addition = (Ingredient) getAddition.invoke(recipe);

                Ingredient baseIngredient = Ingredient.of(baseItem);
                List<IngredientSpec> specs = new ArrayList<>();
                if (!template.isEmpty()) specs.add(new IngredientSpec(template, 1));
                specs.add(new IngredientSpec(baseIngredient, 1));
                if (!addition.isEmpty()) specs.add(new IngredientSpec(addition, 1));

                List<Ingredient> perRecipe = new ArrayList<>();
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    perRecipe.add(spec.ingredient());
                }
                displayIngredients = perRecipe;
                displayInputRoles = CraftIngredientPlanMath.nonEmptyInputRoles(specs);
                recipeSpecs = CraftIngredientPlanMath.scaleIngredientSpecs(specs, repeatCount);
                // Apply modifier to the JEI-provided base item so the plan
                // shows the actual modified output (e.g. sword + eternal),
                // not the unmodified base material.
                targetOutput = baseItem.copy();
                try {
                    Method getModifier = recipe.getClass().getMethod("getModifier");
                    Object modifier = getModifier.invoke(recipe);
                    if (modifier != null) {
                        Class<?> helperClass = Class.forName(
                                "com.stal111.forbidden_arcanus.common.item.modifier.ModifierHelper");
                        Method setModifier = null;
                        for (Method m : helperClass.getMethods()) {
                            if (m.getName().equals("setModifier")
                                    && m.getParameterCount() == 2
                                    && m.getParameterTypes()[0].isAssignableFrom(ItemStack.class)) {
                                setModifier = m;
                                break;
                            }
                        }
                        if (setModifier != null) {
                            setModifier.invoke(null, targetOutput, modifier);
                        }
                    }
                } catch (Exception ex) {
                    RSIntegrationMod.LOGGER.warn("[RSI-tryBuildPlan] Failed to apply FA modifier", ex);
                }
                recipeModType = ModType.byId("smithing");
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-tryBuildPlan] FA reflection failed", e);
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(
                                IntegrationDiagnosticCode.SMITHING_BASE_REQUIRED));
                return;
            }
        } else if (arsDynamic) {
            ItemStack validated = ArsDynamicApparatusRecipe.validatedOutput(recipe, clickedOutput);
            List<IngredientSpec> specs = ArsDynamicApparatusRecipe.buildMaterials(recipe, clickedOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE,
                                recipe.getClass().getSimpleName()));
                return;
            }
            displayIngredients = specs.stream()
                    .filter(spec -> !spec.isEmpty())
                    .map(IngredientSpec::ingredient)
                    .toList();
            displayInputRoles = CraftIngredientPlanMath.nonEmptyInputRoles(specs);
            recipeSpecs = CraftIngredientPlanMath.scaleIngredientSpecs(specs, repeatCount);
            targetOutput = validated;
            recipeModType = previewModType;
        } else if (goetyDynamic) {
            ItemStack validated = GoetyDynamicRitualRecipe.validatedOutput(recipe, clickedOutput);
            List<IngredientSpec> specs = GoetyDynamicRitualRecipe.buildMaterials(recipe, clickedOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE,
                                recipe.getClass().getSimpleName()));
                return;
            }
            displayIngredients = specs.stream()
                    .filter(spec -> !spec.isEmpty())
                    .map(IngredientSpec::ingredient)
                    .toList();
            displayInputRoles = CraftIngredientPlanMath.nonEmptyInputRoles(specs);
            recipeSpecs = CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, specs, validated, repeatCount);
            targetOutput = validated;
            recipeModType = previewModType;
        } else if (recipe instanceof CraftingRecipe cr) {
            List<Ingredient> raw = cr.getIngredients();
            List<IngredientSpec> extractedSpecs = CraftIngredientPlanMath.extractPlanIngredientSpecs(cr);
            if (cr instanceof CustomRecipe
                    && extractedSpecs.stream().allMatch(IngredientSpec::isEmpty)) {
                // 特殊配方（CustomRecipe 及其子类）不提供 getIngredients() 数据，
                // 例如 Farmer's Delight 的 wheat_dough_from_water。这类配方既没有可推导
                // 的材料，也绝不能按空材料放行（否则会变成免材料合成），只能如实说明原因。
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.NO_INGREDIENT_DATA,
                                recipeId.toString()));
                return;
            }
            if (cr instanceof ShapedRecipe) {
                // Preserve empty slots for the shaped-grid renderer.
                displayIngredients = raw;
                displayInputRoles = CraftIngredientPlanMath.alignInputRoles(raw, extractedSpecs);
            } else {
                // Some mod recipes implement CraftingRecipe for JEI integration but
                // their handler supplies semantic inputs absent from getIngredients(),
                // such as Goety's activation item or Botania's reagent.
                displayIngredients = extractedSpecs.stream()
                        .filter(spec -> !spec.isEmpty())
                        .map(IngredientSpec::ingredient)
                        .toList();
                displayInputRoles = CraftIngredientPlanMath.nonEmptyInputRoles(extractedSpecs);
            }
            targetOutput = ModRecipeHandlers.tryGetResultItem(
                    cr, player.serverLevel().registryAccess());
            recipeSpecs = CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, extractedSpecs, targetOutput, repeatCount);
        } else {
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            specs = CraftPacketUtils.applyPlanningIngredientContext(recipe, player, specs);
            if (recipeModType != null && ModIds.ID_MD_COPPER_POT.equals(recipeModType.id())) {
                specs = MinersDelightCopperPotSupport.adaptIngredientSpecs(
                        specs, recipe, player.serverLevel().registryAccess());
            }
            boolean crockCategory = CrockPotRecipeHandler.hasCategoryConstraints(recipe);
            if (crockCategory) {
                storageContext = CraftPlanStorageService.resolvePlanContext(player, dim, pos, recipeModType, storageReference);
                if (storageContext.failed()) {
                    sink.error(PlanOutcome.RUNTIME_UNAVAILABLE, IntegrationDiagnostic.error(storageContext.failure()));
                    return;
                }
                var level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                specs = CrockPotCategoryPlanning.ingredients(CrockPotCategoryPlanning.capture(
                        MaterialAccess.EXTERNAL, recipe, clickedOutput, storageContext.endpoint(), player, level, CrockPotInventory.planningSlots(level, pos)));
                if (specs == null || specs.isEmpty()) {
                    sink.error(PlanOutcome.NO_RECIPE,
                            IntegrationDiagnostic.error(
                                    IntegrationDiagnosticCode.FOOD_VALUES_UNAVAILABLE));
                    return;
                }
            } else if (specs == null || specs.isEmpty()) {
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.NO_INGREDIENTS));
                return;
            }
            List<Ingredient> perRecipe = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                perRecipe.add(spec.ingredient());
            }
            displayIngredients = perRecipe;
            displayInputRoles = CraftIngredientPlanMath.nonEmptyInputRoles(specs);
            targetOutput = ModRecipeHandlers.tryGetResultItem(recipe, player.serverLevel().registryAccess());
            targetOutput = CraftMachineBindingSupport.routeRecipeOutput(recipeModType, targetOutput);
            recipeSpecs = CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, specs, targetOutput, repeatCount);
            boolean manualGoetyRitual = GoetyRecipeHandler.requiresManualConfirmation(recipe);
            RSIntegrationMod.debug("[RSI-tryBuildPlan] targetOutput: recipeId={} class={} result={}x{} isEmpty={} modType={}",
                    recipeId,
                    recipe.getClass().getSimpleName(),
                    targetOutput.isEmpty() ? "EMPTY"
                            : BuiltInRegistries.ITEM.getKey(targetOutput.getItem()),
                    targetOutput.getCount(), targetOutput.isEmpty(),
                    recipeModType != null ? recipeModType.id() : "null");
            ModRecipeHandler previewHandler =
                    ModRecipeHandlers.claimFor(recipe).winner();
            boolean structuredEmptyOutput =
                    previewHandler != null
                            && previewHandler.hasStructuredOutputContract(recipe);
            if (targetOutput.isEmpty() && recipeModType != null
                    && !structuredEmptyOutput
                    && !ModIds.ID_EMBERS_ALCHEMY.equals(recipeModType.id())
                    && !ModIds.ID_AETHERWORKS_ANVIL.equals(recipeModType.id())
                    && !ModIds.TOUHOU_LITTLE_MAID.equals(recipeModType.id())
                    && !ModIds.FORBIDDEN_ARCANUS.equals(recipeModType.id())
                    && !ModIds.AETHER.equals(recipeModType.id())
                    && !recipeModType.id().startsWith(ModIds.AETHER + "_")
                    && !ModIds.ID_YHK_KETTLE.equals(recipeModType.id())
                    && !ModIds.ID_YHK_FERMENT.equals(recipeModType.id())
                    && !ModIds.ID_FR_KETTLE.equals(recipeModType.id())
                    && !ModIds.ID_SUMMONING_RITUALS.equals(recipeModType.id())
                    && !manualGoetyRitual) {
                sink.error(PlanOutcome.NO_RECIPE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE,
                                recipe.getClass().getSimpleName()));
                return;
            }
        }

        targetOutput = CraftMachineBindingSupport.routeRecipeOutput(recipeModType, targetOutput);
        recipeSpecs = MaterialLocks.narrowScaledSpecs(recipeId, recipeSpecs, materialLocks);
        if (recipeModType != null && ModIds.ID_SUMMONING_RITUALS.equals(recipeModType.id())) {
            // 必须先按整批缩放再扣祭坛库存，催化剂只需补齐一份。
            recipeSpecs = SummoningRitualAltarBatchDelegate.planningScaledMaterials(
                    player, recipe, dim, pos, recipeSpecs);
        }

        // ── Arcane Iterator per-level chain detection ──
        // All levels of one enchant share this recipeId and declare no static output,
        // so tryGetResultItem above returned the level-I book. If the player clicked a
        // higher level (Curse V), rebuild targetOutput to that level and record the
        // book at every level 1..N so the target-step block can emit N chained steps
        // (each level-k book crafted from the level-(k-1) book + one side set). This
        // makes PlanTreeModel auto-nest the full leveling chain. Guards: WR must be
        // present (buildEnchantedBookOutput returns EMPTY otherwise), the clicked stack
        // must resolve to N>=2, and the recipe must actually be an enchant recipe
        // (arcanum_lens etc. return EMPTY at level 1 → no chain).
        if (clickedOutput != null && !clickedOutput.isEmpty()
                && recipe.getClass().getName().endsWith("ArcaneIteratorRecipe")) {
            int targetLevel = WRRecipeHandler.inferTargetLevel(recipe, clickedOutput);
            if (targetLevel >= 2) {
                ItemStack[] books = new ItemStack[targetLevel];
                boolean ok = true;
                for (int lvl = 1; lvl <= targetLevel; lvl++) {
                    ItemStack book = WRRecipeHandler.buildEnchantedBookOutput(recipe, lvl);
                    if (book.isEmpty()) { ok = false; break; }
                    books[lvl - 1] = book;
                }
                if (ok) {
                    levelBooks = books;
                    targetOutput = books[targetLevel - 1].copy();
                    RSIntegrationMod.debug("[RSI-tryBuildPlan] ArcaneIterator per-level chain: recipeId={} targetLevel={}",
                            recipeId, targetLevel);
                }
            }
        }

        CraftForcedRecipeOverrides.Resolution forcedResolution =
                CraftForcedRecipeOverrides.resolve(player, forcedRecipes);
        if (!forcedResolution.valid()) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.invalid_forced_recipe",
                    forcedResolution.invalidKey(), forcedResolution.invalidRecipe()));
            return;
        }
        Map<ResourceLocation, ResourceLocation> forcedOverrides =
                forcedResolution.overrides();

        if (forcedOverrides != null && !forcedOverrides.isEmpty()) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-plan] explicit recipe choices recipe={} choices={}",
                    recipeId, forcedOverrides);
        }
        final PlanCache.Key cacheKey = CraftPlanCacheKeys.key(player.getUUID(), recipeId,
                forcedRecipes, repeatCount, clickedOutput, recipeModType, materialLocks,
                safePreferences);
        ResourceKey<Level> planDimKey = dim != null
                ? ResourceKey.create(
                        Registries.DIMENSION, dim)
                : player.serverLevel().dimension();
        BlockPos planLookupPos = pos != null ? pos : player.blockPosition();
        if (storageContext == null) {
            storageContext = CraftPlanStorageService.resolvePlanContext(player, dim, pos, recipeModType, storageReference);
        }
        if (storageContext.failed()) {
            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(storageContext.failure()));
            return;
        }
        CraftStorageEndpoint planningEndpoint = storageContext.endpoint();
        final StorageReference planStorageReference = storageContext.reference();
        List<StorageNetworkDescriptor> storageNetworks = storageContext.networks();

        PlanCache.Entry cached = CraftPlanningRuntime.planCache().get(cacheKey, System.nanoTime());
        // 新出现的食材也可能改变最优选料，只检查旧计划用过的材料不够。
        if (requiresFreshPreview(previewModType, cached == null ? null : cached.plan())) {
            cached = null;
        }
        if (cached != null && cached.snapshot().preferences().equals(safePreferences)
                && cached.plan().success() && cached.plan().graph() != null
                && PlanningStateValidator.revalidateForExecution(player, cached.snapshot(),
                cached.plan(), planDimKey, planLookupPos, planStorageReference)) {
            if (CraftPlanningRuntime.planCache().grantExecutionLease(cacheKey, System.nanoTime())) {
                RSIntegrationMod.debug(
                        "[RSI-tryBuildPlan] Relevant-state cache hit: recipeId={}", recipeId);
                sink.success(cached.plan(), cached.snapshot());
                return;
            }
        }

        Map<ResourceLocation, ResourceLocation> effectiveOverrides =
                forcedOverrides == null ? Map.of() : forcedOverrides;
        Map<StackKey, Long> available;
        PlanningSnapshot planningSnapshot;
        if (precomputedSnapshot != null) {
            if (!CraftPlanDecisionRules.matchesAsyncRequest(precomputedSnapshot, player.getUUID(), recipeId,
                    previewGeneration, effectiveOverrides, safePreferences)
                    || !isSnapshotActive(player, precomputedSnapshot, previewGeneration)) {
                RSIntegrationMod.debug("[RSI-plan] Discarding stale async result before assembly: recipeId={}",
                        recipeId);
                return;
            }
            planningSnapshot = precomputedSnapshot;
            available = precomputedSnapshot.availableItems();
        } else {
            long snapshotStarted = System.nanoTime();
            try {
                available = MaterialSources.listAllAvailable(player, planningEndpoint);
                boolean backgroundProjectable = RecipeIndex.isBackgroundProjectable(recipe);
                boolean projectable = recipe instanceof CraftingRecipe || backgroundProjectable;
                var level = player.serverLevel();
                ImmutableRecipeGraph graph = projectable
                        ? ImmutableRecipeGraphProjector.capture(level) : new ImmutableRecipeGraph(Map.of());
                ImmutableRecipeGraph scoped = projectable
                        ? ImmutableRecipeGraphProjector.restrictToDependencies(graph, recipeId) : graph;
                String network = PlanningStateValidator.networkFingerprint(planStorageReference, available);
                String binding = PlanningStateValidator.bindingFingerprint(player, planDimKey, pos);
                boolean contextual = scoped.recipesById().values().stream()
                        .anyMatch(CraftPlanningApplicationService::hasContextualInputs);
                if (previewGeneration > 0L && !contextual
                        && scoped.recipesById().size() > IMMEDIATE_BINDING_SCAN_RECIPES) {
                    var captured = PlanningSnapshotFactory.capture(player.getUUID(), previewGeneration, recipeId,
                            available, effectiveOverrides, scoped, network, binding, Set.of(), false,
                            safePreferences).withBindingAnchor(planDimKey, pos);
                    scheduleBindingCapture(player, recipeId, previewGeneration,
                            bindingCapture(player, scoped, false), captured,
                            () -> player.serverLevel() == level && binding.equals(
                                    PlanningStateValidator.bindingFingerprint(player, planDimKey, pos)),
                            current -> tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                    baseItem, clickedOutput, requestId, previewGeneration, null, current,
                                    false, reuseValidatedSnapshot, sink, planStorageReference, materialLocks,
                                    null, safePreferences), sink);
                    return;
                }
                BindingAwareGraph bindingGraph = contextual
                        ? scopedBindingAwareGraph(player, graph, recipeId)
                        : bindingAwareGraph(player, scoped);
                planningSnapshot = PlanningSnapshotFactory.capture(
                        player.getUUID(), previewGeneration, recipeId, available,
                        effectiveOverrides,
                        bindingGraph.graph(),
                        network, binding,
                        bindingGraph.blockedOutputIds(),
                        bindingGraph.requiresTypedPlanning()
                                || !(recipe instanceof CraftingRecipe) && !backgroundProjectable,
                        safePreferences).withBindingAnchor(planDimKey, pos);
            } finally {
                long elapsed = System.nanoTime() - snapshotStarted;
                PerformanceMonitor.recordPlanningSnapshot(elapsed);
                if (elapsed >= 1_000_000_000L) {
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-plan] Slow snapshot request={} recipe={} wallMs={}",
                            previewGeneration, recipeId, elapsed / 1_000_000L);
                }
            }
        }

        ItemStack rootTarget = targetOutput;
        if (safePreferences.excludes(recipeId)) {
            PlanResponse excluded = CraftPlanPreferenceResponses.excluded(
                    recipeId, rootTarget, repeatCount, available);
            sink.success(excluded, planningSnapshot);
            return;
        }
        if (safePreferences.isNoRecipe(rootTarget)) {
            PlanResponse noRecipe = CraftPlanPreferenceResponses.noRecipe(
                    recipeId, rootTarget, repeatCount, available);
            sink.success(noRecipe, planningSnapshot);
            return;
        }

        ItemStack smithingOutput = targetOutput;
        if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
            selectedSmithingBase = SmithingRecipeHandler.selectAvailableBase(
                    smithingRecipe, available, repeatCount);
            if (!selectedSmithingBase.isEmpty()) {
                recipeSpecs = SmithingRecipeHandler.requireExactScaledBase(
                        smithingRecipe, recipeSpecs, selectedSmithingBase);
                displayIngredients = recipeSpecs.stream()
                        .filter(spec -> !spec.isEmpty())
                        .map(ScaledIngredientSpec::ingredient)
                        .toList();
                displayInputRoles =
                        CraftIngredientPlanMath.nonEmptyScaledInputRoles(recipeSpecs);
                ItemStack assembled = SmithingRecipeHandler.assembleWithBase(
                        smithingRecipe, selectedSmithingBase,
                        player.serverLevel().registryAccess());
                if (!assembled.isEmpty()) smithingOutput = assembled;
            }
        }

        final ItemStack planTargetOutput = smithingOutput;

        if (cached != null && (PlanningStateValidator.sameState(
                cached.snapshot(), planningSnapshot)
                || cached.plan().graph() != null && PlanningStateValidator.sameRelevantState(
                cached.snapshot(), planningSnapshot, cached.plan()))) {
            RSIntegrationMod.debug("[RSI-tryBuildPlan] Validated cache hit: recipeId={}", recipeId);
            sink.success(cached.plan(), cached.snapshot());
            return;
        }
        if (cached != null) {
            RSIntegrationMod.debug(
                    "[RSI-tryBuildPlan] Rejected preview cache because the planning snapshot changed: recipeId={}",
                    recipeId);
        }

        // 贸易站按整批直接分配成本并生成执行图，避免逐次兑换或进入递归搜索。
        if (recipe instanceof MarketRecipeWrapper market
                && effectiveOverrides.isEmpty()
                && CraftMarketPlanService.tryBuildDirectPlan(
                        player, market, recipeId, repeatCount, planTargetOutput,
                        clickedOutput, recipeSpecs, dim, pos, available, planningSnapshot,
                        cacheKey, planStorageReference, storageNetworks, planStartNanos, sink)) {
            return;
        }

        boolean hasFixedTerminalInputs = recipeSpecs.stream()
                .anyMatch(spec -> spec != null && !spec.isEmpty());
        boolean altarInputsPrepared = recipeModType != null
                && ModIds.ID_SUMMONING_RITUALS.equals(recipeModType.id())
                && recipeSpecs.isEmpty();
        boolean executionMachineAvailable = !CraftExecutionPolicy.requiresBoundMachine(recipe, recipeModType)
                || CraftMachineBindingSupport.hasBindingForExecutionType(player, recipe, recipeModType);
        DirectMaterialAllocator.Result directPreviewAllocation =
                executionMachineAvailable && effectiveOverrides.isEmpty()
                && (hasFixedTerminalInputs || altarInputsPrepared)
                        ? (CraftExecutionPolicy.requiresBoundMachine(recipe, recipeModType)
                                ? DirectMaterialAllocator.allocateScaledSingleVariant(
                                        recipeSpecs, available)
                                : DirectMaterialAllocator.allocateScaled(recipeSpecs, available))
                : new DirectMaterialAllocator.Result(false, List.of(), -1, 0);
        boolean directTerminalPlan = CraftIngredientPlanMath.useDirectTerminalPlan(
                directPreviewAllocation, recipeSpecs, planTargetOutput);
        if (directTerminalPlan) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-plan] direct terminal preview recipe={} specs={} fragments={} recursivePlannerBypassed=true",
                    recipeId, recipeSpecs.size(), directPreviewAllocation.allocations().size());
        }

        PerformanceMonitor.recordPlanningLatency(PerformanceMonitor.PlanningLatencyPhase.PREPARATION,
                System.nanoTime() - planStartNanos);
        long demandTreeElapsed = 0L;
        PureDemandTreeInspector.Result demandTree;
        Set<ResourceLocation> reusableCatalystOutputIds =
                RSIntegrationConfig.ENABLE_CATALYST_RECIPE_PREFERENCE.get()
                        ? RecipeIndex.reusableCatalystOutputIds(player.serverLevel()) : Set.of();
        Set<ResourceLocation> reusableCatalystRecipeIds =
                RecipeIndex.reusableCatalystRecipeIds(player.serverLevel());
        boolean targetUsesReusableCatalyst = reusableCatalystRecipeIds.contains(recipeId);
        Set<ResourceLocation> pureIncompatibleOutputIds = directTerminalPlan ? Set.of()
                : RecipeIndex.pureIncompatibleOutputIds(player.serverLevel());
        int demandMaxNodes = RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.get();
        if (!asyncAttempted && !directTerminalPlan && CraftPlanDecisionRules.initialRoute(
                planningSnapshot.mainThreadOnly(), !materialLocks.isEmpty(),
                effectiveOverrides.isEmpty() || ForcedRecipeGraphProjector.apply(
                        planningSnapshot.recipeGraph(), effectiveOverrides) != null,
                planningSnapshot.recipeGraph().recipesById().containsKey(recipeId))
                .action() == PlannerAction.RUN_PURE) {
            var routing = AsyncPurePlanningService.RouteInputs.fromItems(
                    planningSnapshot.availableItems(), demandMaxNodes,
                    reusableCatalystOutputIds, reusableCatalystRecipeIds, pureIncompatibleOutputIds);
            PlanCache.Entry previous = CraftPlanningRuntime.planCache().findForReplan(cacheKey);
            if (previous != null && !requiresFreshPreview(previewModType, previous.plan())) {
                routing = routing.withPreviousRoute(PlanningRouteHint.from(previous));
            }
            CraftPlanningRuntime.planRequests().submitRouted(planningSnapshot, routing, repeatCount, player.getServer()::execute,
                    RSIntegrationConfig.CRAFTING_MAX_STEPS.get(), completed ->
                            tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                    baseItem, clickedOutput, requestId, previewGeneration,
                                    completed.plan(), completed.snapshot(), true,
                                    reuseValidatedSnapshot, sink, planStorageReference, materialLocks,
                                    completed, safePreferences),
                    failure -> {
                        if (failure instanceof RejectedExecutionException) {
                            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                    IntegrationDiagnostic.error(
                                            IntegrationDiagnosticCode.PLANNER_BUSY));
                            return;
                        }
                        if (failure instanceof CancellationException
                                || failure instanceof StalePlanningResultException) {
                            return;
                        }
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-plan] Background preview planning failed recipe={}",
                                recipeId, failure);
                        sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                IntegrationDiagnostic.error(
                                        IntegrationDiagnosticCode.INTERNAL_ERROR));
                    });
            return;
        }
        if (directTerminalPlan) {
            demandTree = new PureDemandTreeInspector.Result(
                    PureDemandTreeInspector.Status.COMPLETE, 0, null, false);
            demandTreeElapsed = 0L;
        } else if (planningSnapshot.mainThreadOnly()) {
            // Dynamic machine recipes cannot be safely inspected through the pure graph.
            // Do not walk the global graph on the server thread before the typed adapter
            // gets a chance to inspect the live machine.
            demandTree = new PureDemandTreeInspector.Result(
                    PureDemandTreeInspector.Status.TARGET_NOT_PROJECTED, 0,
                    null, false);
            demandTreeElapsed = 0L;
        } else if (routedPlan != null && routedPlan.snapshot().equals(planningSnapshot)
                && routedPlan.routing().matchesPolicy(demandMaxNodes, reusableCatalystOutputIds,
                        reusableCatalystRecipeIds, pureIncompatibleOutputIds)) {
            demandTree = routedPlan.inspection();
        } else {
            if (routedPlan != null) {
                tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                        baseItem, clickedOutput, requestId, previewGeneration,
                        null, null, false, false, sink, planStorageReference, materialLocks,
                        safePreferences);
                return;
            }
            long demandTreeStarted = System.nanoTime();
            try {
                demandTree = PureDemandTreeInspector.inspect(
                        planningSnapshot.recipeGraph(), routingAvailability(planningSnapshot.availableItems()),
                        recipeId, repeatCount, demandMaxNodes,
                        reusableCatalystOutputIds, reusableCatalystRecipeIds,
                        pureIncompatibleOutputIds);
            } finally {
                demandTreeElapsed = System.nanoTime() - demandTreeStarted;
                PerformanceMonitor.recordDemandTreeInspection(demandTreeElapsed);
            }
        }
        if (demandTreeElapsed >= 100_000_000L) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-plan] Slow demand-tree inspection: recipe={} elapsedMs={} status={} nodes={} unresolved={}",
                    recipeId, demandTreeElapsed / 1_000_000L, demandTree.status(),
                    demandTree.visitedNodes(), demandTree.unresolved());
        }
        boolean projectedOverrides = effectiveOverrides.isEmpty()
                || routedPlan != null
                && routedPlan.snapshot().equals(planningSnapshot)
                && routedPlan.forcedRecipesProjected();
        RouteDecision routeDecision = directTerminalPlan
                ? new RouteDecision(PlannerAction.USE_PURE_RESULT, RouteReason.PURE_SUCCESS)
                : CraftPlanDecisionRules.completedRoute(planningSnapshot, !materialLocks.isEmpty(),
                        projectedOverrides, demandTree, precomputedPlan,
                        routedPlan == null ? PurePlanningCoverage.Probe.notChecked() : routedPlan.coverage(),
                        reusableCatalystOutputIds, targetUsesReusableCatalyst);
        // 规划只需要可用的存储端点，不能再用 RS 原生网络做可用性判断。
        boolean typedResolverAvailable = RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()
                && planningEndpoint != null;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-plan] planner route recipe={} action={} reason={} coverage={} nodes={} unresolved={} overrides={} mainThreadOnly={} catalystRoute={} targetCatalyst={}",
                recipeId, routeDecision.action(), routeDecision.reason(), demandTree.status(), demandTree.visitedNodes(),
                demandTree.unresolved(), !effectiveOverrides.isEmpty(), planningSnapshot.mainThreadOnly(),
                demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst);
        // 有界轨迹只控制预览展示，不授权执行，也不启动另一轮求解。
        boolean timedOutMissingRoute = routeDecision.action() == PlannerAction.REPORT_FAILURE
                && CraftPlanDecisionRules.canOpenBoundedMissingPlan(precomputedPlan, demandTree);
        if (routeDecision.action() == PlannerAction.REPORT_FAILURE
                && !timedOutMissingRoute) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-plan] bounded planner result recipe={} status={} feasibility={} missing={} states={} backtracks={} memoHits={} directReservation=false",
                    recipeId, precomputedPlan.status(), precomputedPlan.feasibility(),
                    CraftPlanDecisionRules.summarizePureDemands(precomputedPlan.missing()),
                    precomputedPlan.expandedStates(),
                    precomputedPlan.backtracks(), precomputedPlan.memoHits());
            // UNKNOWN means the planner did not prove feasibility. Its
            // diagnostic trace is not an authoritative shortage report.
            sink.error(PlanOutcome.BUDGET_EXHAUSTED,
                    CraftFailureMessages.purePreviewDiagnostic(precomputedPlan));
            return;
        }
        boolean selectedPureResolver = routeDecision.action() == PlannerAction.USE_PURE_RESULT
                || timedOutMissingRoute;
        boolean needsTypedResolver = routeDecision.action() == PlannerAction.RUN_TYPED;
        if (needsTypedResolver && !typedResolverAvailable) {
            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(planningEndpoint == null
                            ? IntegrationDiagnosticCode.NETWORK_UNAVAILABLE
                            : IntegrationDiagnosticCode.MULTIBLOCK_DISABLED));
            return;
        }
        if (needsTypedResolver
                && !CraftPlanningRuntime.typedPreviewRequests().isAdmitted(player.getUUID(), previewGeneration)) {
            PlanningProgressServer.queued(player.getUUID(), previewGeneration,
                    PlanningProgressSnapshot.Phase.SPECIAL_RECIPE);
            PlanningSnapshot queuedSnapshot = planningSnapshot;
            TypedPreviewAdmissionQueue queue = CraftPlanningRuntime.typedPreviewRequests();
            TypedPreviewAdmissionQueue.OfferResult queued = queue.offer(
                    new TypedPreviewAdmissionQueue.Request(
                            player.getUUID(), previewGeneration, System.nanoTime(),
                            () -> tryBuildPlan(player, recipeId, forcedRecipes, dim, pos,
                                    repeatCount, baseItem, clickedOutput, requestId,
                                    previewGeneration, precomputedPlan, queuedSnapshot, true,
                                    true, sink, planStorageReference,
                                    materialLocks, routedPlan, safePreferences),
                            () -> {
                                PerformanceMonitor.recordTypedPreviewRejected(queue.size());
                                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                        IntegrationDiagnostic.error(
                                                IntegrationDiagnosticCode.PLANNER_BUSY));
                            }, failure -> {
                                RSIntegrationMod.LOGGER.error(
                                        "[RSI-plan] Deferred typed preview failed for {}",
                                        recipeId, failure);
                                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                                        IntegrationDiagnostic.error(
                                                IntegrationDiagnosticCode.INTERNAL_ERROR));
                            }));
            if (queued == TypedPreviewAdmissionQueue.OfferResult.FULL) {
                PerformanceMonitor.recordTypedPreviewRejected(queue.size());
                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY));
                return;
            }
            PerformanceMonitor.recordTypedPreviewQueued(
                    queued == TypedPreviewAdmissionQueue.OfferResult.REPLACED, queue.size());
            return;
        }
        if (needsTypedResolver) {
            PlanningProgressServer.running(player.getUUID(), previewGeneration,
                    PlanningProgressSnapshot.Phase.SPECIAL_RECIPE);
        }

        // Arcane Iterator plans should start from the highest matching enchanted
        // book the player already owns, rather than always rebuilding from a plain
        // book. Use the NBT-aware availability snapshot so a different enchantment
        // (or a different level) can never satisfy this shortcut.
        if (levelBooks != null) {
            for (int lvl = levelBooks.length - 1; lvl >= 1; lvl--) {
                ItemStack wanted = levelBooks[lvl - 1];
                long matchingCount = available.entrySet().stream()
                        .filter(e -> MaterialMatcher.sameRuntimeFragment(e.getKey().toStack(), wanted))
                        .mapToLong(Map.Entry::getValue)
                        .reduce(0L, SaturatingLongMath::add);
                if (matchingCount >= repeatCount) {
                    iteratorStartLevel = lvl;
                    break;
                }
            }
            RSIntegrationMod.debug("[RSI-tryBuildPlan] ArcaneIterator start level: {}/{}",
                    iteratorStartLevel, levelBooks.length);
        }

        // Build plan via CraftingResolver
        List<String> missing = new ArrayList<>();

        ModRecipeHandler recursiveHandler = ModRecipeHandlers.claimFor(recipe).winner();
        List<ScaledIngredientSpec> recursiveRecipeSpecs = recursiveHandler == null
                ? recipeSpecs
                : recursiveHandler.getRecursiveScaledIngredients(recipe, recipeSpecs);
        DirectMaterialAllocator.Result directRecursiveAllocation = effectiveOverrides.isEmpty()
                ? DirectMaterialAllocator.allocateScaled(recursiveRecipeSpecs, available)
                : new DirectMaterialAllocator.Result(false, List.of(), -1, 0);
        boolean directRecursiveTerminalPlan = !effectiveOverrides.isEmpty()
                ? false
                : CraftIngredientPlanMath.useDirectTerminalPlan(
                        directRecursiveAllocation, recursiveRecipeSpecs, planTargetOutput);

        List<ResolutionStep> resolutionSteps = null;
        CraftPlanGraph planGraph = null;
        CraftPlanGraph cacheInputGraph = null;
        boolean terminalEmbedded = false;
        List<ResourceLocation> stepIds;
        boolean usedPurePlan = false;

        boolean selectedTypedResolver = false;
        boolean selectedDirectTerminalResolver = false;
        if (directTerminalPlan) {
            resolutionSteps = List.of();
            cacheInputGraph = CraftIngredientPlanMath.directTerminalInputGraph(
                    recipeSpecs, directPreviewAllocation);
            selectedDirectTerminalResolver = true;
        } else if (directRecursiveTerminalPlan) {
            resolutionSteps = List.of();
            cacheInputGraph = CraftIngredientPlanMath.directTerminalInputGraph(
                    recursiveRecipeSpecs, directRecursiveAllocation);
            selectedDirectTerminalResolver = true;
        } else if (CraftPlanDecisionRules.canUsePrecomputedPlan(precomputedPlan)
                && selectedPureResolver) {
            resolutionSteps = PurePlanAdapter.toResolutionSteps(precomputedPlan,
                    planningSnapshot.recipeGraph());
            usedPurePlan = true;
        } else if (selectedPureResolver) {
            if (timedOutMissingRoute) {
                if (precomputedPlan.missing().isEmpty()) {
                    missing.add(CraftPacketUtils.missingMaterialKey(
                            demandTree.unresolved().itemId()));
                } else {
                    for (var unresolved : precomputedPlan.missing()) {
                        recordMissingMaterial(missing, unresolved);
                    }
                }
            } else {
                for (var unresolved : precomputedPlan.missing()) {
                    recordMissingMaterial(missing, unresolved);
                }
            }
            resolutionSteps = PurePlanAdapter.toResolutionSteps(precomputedPlan,
                    planningSnapshot.recipeGraph());
            usedPurePlan = true;
        } else {
            selectedTypedResolver = true;
            if (completedTyped == null) {
                PlanningSnapshot typedSnapshot = planningSnapshot;
                var level = player.serverLevel();
                var bindingFingerprint = PlanningStateValidator.bindingFingerprint(player, planDimKey, pos);
                var activeRoot = new CraftingResolver.ActiveRootRecipe(recipeId, targetOutput);
                List<TypedRootKey> rootKeys = typedRootKeys(recursiveRecipeSpecs);
                var job = CraftingResolver.startTypedPreview(
                        recursiveRecipeSpecs, available, level, player, planningEndpoint,
                        missing, forcedOverrides, CraftExecutionPolicy.usesPhysicalMachineInputSlots(recipe),
                        RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get(), activeRoot, materialLocks,
                        safePreferences);
                scheduleTypedPreview(player, recipeId, previewGeneration, typedSnapshot, sink, job,
                        () -> player.serverLevel() == level && Objects.equals(bindingFingerprint,
                                PlanningStateValidator.bindingFingerprint(player, planDimKey, pos)),
                        preview -> tryBuildPlanWithSources(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                baseItem, clickedOutput, requestId, previewGeneration, precomputedPlan, typedSnapshot,
                                true, true, sink, planStorageReference, materialLocks, routedPlan, safePreferences,
                                preview), missing, activeRoot.output(), rootKeys);
                return;
            }
            if (!ItemStack.matches(completedTyped.output(), targetOutput)
                    || !completedTyped.roots().equals(typedRootKeys(recursiveRecipeSpecs))) {
                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.STALE_PLAN));
                return;
            }
            planGraph = completedTyped.graph();
            missing.addAll(completedTyped.missing());
            resolutionSteps = ExecutionEquivalence.projectFlatSteps(planGraph);
        }
        if (usedPurePlan && precomputedPlan.feasible()) {
            if (completedTyped != null && (!ItemStack.matches(completedTyped.output(), planTargetOutput)
                    || !completedTyped.roots().equals(typedRootKeys(recursiveRecipeSpecs)))) {
                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.STALE_PLAN));
                return;
            }
            try {
                terminalEmbedded = PurePlanGraphAdapter.targetsOutput(precomputedPlan,
                        planningSnapshot.recipeGraph().recipesById().get(recipeId));
                planGraph = completedTyped != null ? completedTyped.graph() : terminalEmbedded
                        ? PurePlanGraphAdapter.toOutputGraph(precomputedPlan, available)
                        : PurePlanGraphAdapter.toInputGraph(precomputedPlan, available, recursiveRecipeSpecs);
                cacheInputGraph = planGraph;
            } catch (IllegalArgumentException | ArithmeticException mismatch) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-plan] Pure material flow cannot represent terminal recipe={}",
                        recipeId, mismatch);
                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE,
                                recipe.getClass().getSimpleName()));
                return;
            }
            if (completedTyped == null && previewGeneration > 0L
                    && planGraph.nodes().size() > IMMEDIATE_BINDING_SCAN_RECIPES) {
                var graph = planGraph;
                var level = player.serverLevel();
                var currentSnapshot = planningSnapshot;
                var work = new ParallelPlanEstimator.Request(player, aggregateAvailableItems(available)).start(graph);
                var roots = typedRootKeys(recursiveRecipeSpecs);
                schedulePreviewWork(player, recipeId, previewGeneration, currentSnapshot,
                        () -> player.serverLevel() == level && currentSnapshot.bindingFingerprint().equals(
                                PlanningStateValidator.bindingFingerprint(player, planDimKey, pos)),
                        work::advance,
                        () -> tryBuildPlanWithSources(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                baseItem, clickedOutput, requestId, previewGeneration, precomputedPlan, currentSnapshot,
                                true, reuseValidatedSnapshot, sink, planStorageReference, materialLocks, routedPlan,
                                safePreferences, new CompletedTypedPreview(graph, List.of(), planTargetOutput,
                                        roots, work.result(), work.displayResult())), sink);
                return;
            }
            resolutionSteps = ExecutionEquivalence.projectFlatSteps(planGraph);
        }
        boolean usedTypedResolver = selectedTypedResolver
                && resolutionSteps != null && !resolutionSteps.isEmpty();

        // ── OR debug: log resolution results ──
        RSIntegrationMod.debug("[RSI-OR] tryBuildPlan recipe={} typedResolver={} steps={} alts={}",
                recipeId, usedTypedResolver,
                resolutionSteps != null ? resolutionSteps.size() : -1,
                resolutionSteps != null ? resolutionSteps.stream()
                        .filter(rs -> !rs.alternativeIds().isEmpty()).count() : -1);
        if (resolutionSteps != null) {
            for (ResolutionStep rs : resolutionSteps) {
                if (!rs.alternativeIds().isEmpty()) {
                    RSIntegrationMod.debug("[RSI-OR]   step {} has {} alternatives: {}",
                            rs.recipeId(), rs.alternativeIds().size(), rs.alternativeIds());
                }
            }
        }
        if (hasSelectedCraftingPlanner(
                selectedPureResolver, selectedDirectTerminalResolver, selectedTypedResolver)) {
            stepIds = resolutionSteps == null ? List.of() : resolutionSteps.stream()
                    .map(ResolutionStep::recipeId).collect(Collectors.toList());
        } else {
            throw new IllegalStateException("No crafting planner selected for " + recipeId);
        }

        // Diagnostic: log stepId distribution before dedup
        Map<ResourceLocation, Integer> diagStepCounts = new LinkedHashMap<>();
        for (ResourceLocation id : stepIds) diagStepCounts.merge(id, 1, Integer::sum);
        RSIntegrationMod.debug("[RSI-plan] stepIds: total={} unique={} | {}",
                stepIds.size(), diagStepCounts.size(), diagStepCounts);

        // Build modType lookup from typed resolution results and recipe dimensions
        // Pure planning intentionally projects only ordinary crafting recipes. Restore
        // same-output machine recipes from the complete server index so the plan tree can
        // offer a bound machine path even when crafting was selected as the primary path.
        Map<Item, List<RecipeIndex.Entry>> recipeIndex = RecipeIndex.get(player.serverLevel());
        if (resolutionSteps != null) {
            resolutionSteps = CraftDisplayMaterials.attachIndexedAlternatives(
                    resolutionSteps, recipeIndex, player.serverLevel().registryAccess());
        }
        Map<ResourceLocation, ModType> modTypeByRecipe = new HashMap<>();
        Map<ResourceLocation, Integer> recipeWidths = new HashMap<>();
        Map<ResourceLocation, Integer> recipeHeights = new HashMap<>();
        Map<ResourceLocation, ResolutionStep> stepByRecipe = new HashMap<>();
        if (resolutionSteps != null) {
            for (ResolutionStep rs : resolutionSteps) {
                if (rs.modType() != ModType.GENERIC) {
                    modTypeByRecipe.putIfAbsent(rs.recipeId(), rs.modType());
                }
                stepByRecipe.putIfAbsent(rs.recipeId(), rs);
            }
        }

        // Compute itemAvailable early — needed for ingredient matching in both
        // PlanStep display and material calculation.
        Map<Item, Long> itemAvailable = aggregateAvailableItems(available);
        // RecipeIndex for OR alternative material checks — allows alternatives
        // whose ingredients are craftable (not just directly available).
        // Display copy: decremented on each match so the same tag ingredient
        // showing up N times doesn't pick the same item N times (e.g. 1 cherry
        // + 1 acacia shown as 2 acacia when both are logs).
        Map<Item, Long> displayAvailable = new HashMap<>(itemAvailable);

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            long totalItems = itemAvailable.values().stream()
                    .mapToLong(Long::longValue)
                    .reduce(0L, SaturatingLongMath::add);
            RSIntegrationMod.debug("[RSI-Generic] itemAvailable: {} types, {} total items",
                    itemAvailable.size(), totalItems);
        }

        // Aggregate every run of a recipe into one step, keeping first-seen order.
        // Quantity reaches us two ways: the target's ingredients are pre-expanded
        // ×repeatCount (many entries, executions=1 each) and deeper intermediates use
        // inner batching (one entry, executions=N). Summing executions per recipe
        // captures both; counting bare occurrences would drop inner-batched runs and
        // under-scale that sub-recipe — both its tree children (amount = inputCount ×
        // parent.batches) and its material draw. Distinct recipes keep first-seen
        // order; hierarchy comes from the depth pass below (item graph, not list
        // position), and each recipe maps to one output item → one depth, so folding
        // duplicates never crosses depth boundaries.
        List<PlanStep> steps = new ArrayList<>();

        List<CraftPreviewSteps.PreviewStep> mergedSteps = CraftPreviewSteps.merge(resolutionSteps,
                id -> CraftRecipeResolver.resolve(player.serverLevel(), id), player.serverLevel().registryAccess());
        for (CraftPreviewSteps.PreviewStep previewStep : mergedSteps) {
            ResolutionStep mergedRs = previewStep.step();
            ResourceLocation stepId = mergedRs.recipeId();
            int batches = previewStep.batches();
            if (mergedRs != null && mergedRs.syntheticInput() != null && mergedRs.syntheticOutput() != null) {
                steps.add(new PlanStep(stepId, mergedRs.syntheticOutput().copy(), batches,
                        List.of(mergedRs.syntheticInput().copy()), Collections.emptyList(), mergedRs.modType(),
                        0, false, 0, 0, Collections.emptyList()));
                continue;
            }
            Recipe<?> stepRecipe = CraftRecipeResolver.resolve(player.serverLevel(), stepId);
            if (stepRecipe == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Plan step recipe not found: {}",
                        stepId);
                continue;
            }
            ItemStack output = ModRecipeHandlers.tryGetUncachedResultItem(
                    stepRecipe, player.serverLevel().registryAccess());
            output = SlashBladeRecipeHandler.planningOutput(stepRecipe, output);
            if ((stepRecipe instanceof SmithingTransformRecipe
                    || SlashBladeRecipeHandler.isShapedBladeRecipe(stepRecipe)) && mergedRs != null
                    && mergedRs.syntheticOutput() != null) {
                output = mergedRs.syntheticOutput().copy();
            }
            ModType stepModType = mergedRs != null ? mergedRs.modType()
                    : modTypeByRecipe.get(stepId);
            output = CraftMachineBindingSupport.routeRecipeOutput(stepModType, output);
            if (output.isEmpty()) {
                RSIntegrationMod.debug("[RSI-Generic] Plan step output empty: {} ({})",
                        stepId, stepRecipe.getClass().getSimpleName());
                continue;
            }
            int recipeW = 0, recipeH = 0;

            List<ItemStack> inputs = new ArrayList<>();
            List<DemandRole> inputRoles = new ArrayList<>();
            if (stepRecipe instanceof CraftingRecipe scr) {
                List<Ingredient> craftingIngredients = scr.getIngredients();
                if (SlashBladeRecipeHandler.isShapedBladeRecipe(scr) && mergedRs != null) {
                    craftingIngredients = SlashBladePlanning.requireOutputIngredients(craftingIngredients,
                            scr.getResultItem(player.serverLevel().registryAccess()), mergedRs.syntheticOutput());
                }
                List<DemandRole> craftingRoles = CraftIngredientPlanMath.alignInputRoles(
                        craftingIngredients, CraftIngredientPlanMath.extractPlanIngredientSpecs(scr));
                if (scr instanceof ShapedRecipe shaped) {
                    recipeW = shaped.getWidth();
                    recipeH = shaped.getHeight();
                    // Preserve grid positions — include empty slots as ItemStack.EMPTY
                    for (int inputIndex = 0; inputIndex < craftingIngredients.size(); inputIndex++) {
                        Ingredient ing = craftingIngredients.get(inputIndex);
                        if (ing.isEmpty()) {
                            inputs.add(ItemStack.EMPTY);
                        } else {
                            ItemStack matched = CraftDisplayMaterials.matchAndConsume(ing, displayAvailable);
                            inputs.add(matched != null ? matched : CraftDisplayMaterials.firstValidDisplayItem(ing));
                        }
                        inputRoles.add(craftingRoles.get(inputIndex));
                    }
                } else {
                    if (!craftingIngredients.isEmpty()) {
                        int n = 0;
                        for (Ingredient ing : craftingIngredients) {
                            if (!ing.isEmpty()) n++;
                        }
                        if (n <= 3) { recipeW = n; recipeH = 1; }
                        else if (n <= 4) { recipeW = 2; recipeH = 2; }
                        else { recipeW = 3; recipeH = 3; }
                    }
                    for (int inputIndex = 0; inputIndex < craftingIngredients.size(); inputIndex++) {
                        Ingredient ing = craftingIngredients.get(inputIndex);
                        if (ing.isEmpty()) continue;
                        ItemStack matched = CraftDisplayMaterials.matchAndConsume(ing, displayAvailable);
                        inputs.add(matched != null ? matched : CraftDisplayMaterials.firstValidDisplayItem(ing));
                        inputRoles.add(craftingRoles.get(inputIndex));
                    }
                }
            } else {
                // Multi-block recipe: use extractIngredientSpecs to preserve
                // per-ingredient counts (extractIngredients drops counts and
                // returns empty for wrappers like FaRitualWrapper).
                List<IngredientSpec> modSpecs = CraftPacketUtils.extractIngredientSpecs(stepRecipe);
                modSpecs = CraftPacketUtils.applyPlanningIngredientContext(stepRecipe, player, modSpecs);
                if (stepRecipe instanceof SmithingTransformRecipe smithing && mergedRs != null) {
                    modSpecs = SmithingRecipeHandler.requireDemandedOutputTag(
                            smithing, modSpecs, mergedRs.syntheticOutput());
                }
                modSpecs = MinersDelightCopperPotSupport.adaptIngredientSpecs(
                        stepModType, modSpecs, stepRecipe,
                        player.serverLevel().registryAccess());
                modSpecs = MaterialLocks.narrowSpecs(stepId, modSpecs, materialLocks);
                if (modSpecs != null) {
                    ModRecipeHandler stepHandler = ModRecipeHandlers.claimFor(stepRecipe).winner();
                    int stepInputIndex = 0;
                    for (IngredientSpec spec : modSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.role() == DemandRole.CATALYST && stepHandler != null
                                ? stepHandler.requiredIngredientCount(
                                        stepRecipe, spec, stepInputIndex, batches)
                                : spec.count();
                        stepInputIndex++;
                        ItemStack matched = CraftDisplayMaterials.matchAndConsume(spec.ingredient(), displayAvailable);
                        ItemStack display = matched != null ? matched.copy() : CraftDisplayMaterials.firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        CraftIngredientPlanMath.consumeIngredientCountInPlace(
                                spec.ingredient(), Math.max(0, cnt - 1), displayAvailable);
                        inputs.add(display);
                        inputRoles.add(spec.role());
                    }
                }
            }

            // Extract alternatives from the resolver's own candidate analysis.
            // Missing materials do not hide a route: choosing it triggers a new
            // recursive plan that may craft those inputs.
            List<ResourceLocation> alternatives = new ArrayList<>();
            List<String> alternativeModTypes = new ArrayList<>();
            ResolutionStep rs = stepByRecipe.get(stepId);
            RSIntegrationMod.debug("[RSI-OR] buildStep {}: stepByRecipe has rs={} alternatives={}",
                    stepId, rs != null,
                    rs != null ? rs.alternativeIds().size() : -1);
            if (rs != null && !rs.alternativeIds().isEmpty()) {
                for (int i = 0; i < rs.alternativeIds().size(); i++) {
                    ResourceLocation altId = rs.alternativeIds().get(i);
                    String altMod = i < rs.alternativeModTypes().size()
                            ? rs.alternativeModTypes().get(i)
                            : rs.modType().id();
                    Recipe<?> altRecipe = CraftRecipeResolver.resolve(player.serverLevel(), altId);
                    if (altRecipe == null) {
                        RSIntegrationMod.debug("[RSI-OR]   alt {} NOT FOUND in recipe manager", altId);
                        continue;
                    }
                    // Machine gating must match the resolver's own candidate check
                    // (CandidateEngine.isMachineAvailable → hasBindingForRecipe).
                    // hasBindingForRecipe compares the bound machine types against the
                    // type recorded on the step, so the type must already be the concrete
                    // one (vanilla_blast_furnace / vanilla_stonecutter), not the family
                    // fallback vanilla_furnace that the vanilla handler declares.
                    boolean hasMachine = AltarBindingRegistry.hasBindingForRecipe(
                            player, altRecipe, ModType.byId(altMod));
                    boolean runtimeAvailable = CraftMachineBindingSupport.isRecipeAvailableForPlanning(player, altRecipe);
                    RSIntegrationMod.debug("[RSI-OR]   alt {}: hasMachine={} runtimeAvailable={}",
                            altId, hasMachine, runtimeAvailable);
                    if (runtimeAvailable && (CraftExecutionPolicy.isSmithingRecipe(altRecipe) || hasMachine)) {
                        alternatives.add(altId);
                        alternativeModTypes.add(altMod);
                    }
                }
            }
            if (alternatives.isEmpty()) { alternatives = Collections.emptyList(); alternativeModTypes = Collections.emptyList(); }

            ModType mt = stepModType;
            recipeWidths.put(stepId, recipeW);
            recipeHeights.put(stepId, recipeH);
            steps.add(new PlanStep(stepId, output, batches, inputs, alternatives, mt,
                    0, !alternatives.isEmpty(), recipeW, recipeH, alternativeModTypes,
                    inputRoles));
        }

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            RSIntegrationMod.debug("[RSI-Generic] Plan intermediate steps:");
            for (PlanStep s : steps) {
                StringBuilder sb = new StringBuilder("  ").append(s.recipeId())
                        .append(" -> ")
                        .append(BuiltInRegistries.ITEM.getKey(s.output().getItem()))
                        .append(" x").append(s.totalOutputCount())
                        .append(" [");
                for (ItemStack in : s.inputs()) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
                }
                sb.append("]");
                RSIntegrationMod.LOGGER.debug(sb.toString());
            }
        }

        // ── Compute tree depths ────────────────────────────────────
        // BFS depth assignment starting from target recipe ingredients.
        // Also seed raw materials (leaf items not produced by any step) as depth 0
        // so steps that consume them can propagate depth correctly.
        Map<Item, Integer> depthByItem = new HashMap<>();
        for (ScaledIngredientSpec spec : recipeSpecs) {
            if (spec.isEmpty()) continue;
            for (ItemStack stack : spec.ingredient().getItems()) {
                if (!stack.isEmpty()) depthByItem.putIfAbsent(stack.getItem(), 0);
            }
        }
        Set<Item> outputs = new HashSet<>();
        for (PlanStep s : steps) outputs.add(s.output().getItem());
        for (PlanStep s : steps) {
            for (ItemStack in : s.inputs()) {
                Item it = in.getItem();
                if (!outputs.contains(it)) depthByItem.putIfAbsent(it, 0);
            }
        }
        // Propagate depths: each step's output gets depth = max(its ingredient depths) + 1
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                if (step.depth() > 0) continue; // already assigned
                int maxIngredientDepth = -1;
                for (ItemStack in : step.inputs()) {
                    Integer d = depthByItem.get(in.getItem());
                    if (d != null && d > maxIngredientDepth) maxIngredientDepth = d;
                }
                if (maxIngredientDepth >= 0) {
                    int newDepth = maxIngredientDepth + 1;
                    steps.set(i, new PlanStep(step.recipeId(), step.output(), step.batches(),
                            step.inputs(), step.alternatives(), step.modType(),
                            newDepth, step.hasOrSiblings(),
                            step.recipeWidth(), step.recipeHeight(),
                            step.alternativeModTypes(), step.inputRoles()));
                    depthByItem.put(step.output().getItem(), newDepth);
                    changed = true;
                }
            }
        }

        // ── Add the target recipe itself as the last step so its grid is visible ──
        // Components, not Strings: a dedicated server cannot resolve rsi.* keys.
        List<Component> modWarnings = new ArrayList<>();
        if (timedOutMissingRoute) {
            modWarnings.add(Component.translatable("rsi.plan.failure.complexity_limit"));
        }
        boolean blockingPrerequisiteFailure = false;
        // Items the plan's intermediate steps actually produce. When the target's
        // ingredient is a tag with no member in stock (e.g. a wood-tag gun slot),
        // the display representative should be whichever member the plan crafts
        // (oak_log via the timber chain) so the target card matches the tree below,
        // instead of the arbitrary first tag entry (dark_oak_log).
        Set<Item> plannedOutputs = new HashSet<>();
        for (PlanStep s : steps) {
            if (!s.output().isEmpty()) plannedOutputs.add(s.output().getItem());
        }
        {
            List<ItemStack> targetInputs = new ArrayList<>();
            List<DemandRole> targetInputRoles = new ArrayList<>();
            int targetW = 0, targetH = 0;
            if (faRecipe || arsDynamic) {
                // Contextual recipes use the already validated concrete input
                // list so the target card shows the same NBT-bearing item that
                // execution will extract.
                for (int inputIndex = 0; inputIndex < displayIngredients.size(); inputIndex++) {
                    Ingredient ing = displayIngredients.get(inputIndex);
                    if (ing.isEmpty()) continue;
                    ItemStack matched = CraftDisplayMaterials.matchAndConsume(ing, displayAvailable, plannedOutputs);
                    ItemStack display = matched != null ? matched.copy() : CraftDisplayMaterials.firstValidDisplayItem(ing);
                    targetInputs.add(display);
                    targetInputRoles.add(displayInputRoles.get(inputIndex));
                }
            } else if (recipe instanceof ShapedRecipe shaped) {
                targetW = shaped.getWidth();
                targetH = shaped.getHeight();
                for (int inputIndex = 0; inputIndex < displayIngredients.size(); inputIndex++) {
                    Ingredient ing = displayIngredients.get(inputIndex);
                    if (ing.isEmpty()) {
                        targetInputs.add(ItemStack.EMPTY);
                    } else {
                        ItemStack matched = CraftDisplayMaterials.matchAndConsume(ing, displayAvailable, plannedOutputs);
                        targetInputs.add(matched != null ? matched
                                : CraftDisplayMaterials.firstValidDisplayItem(ing));
                    }
                    targetInputRoles.add(displayInputRoles.get(inputIndex));
                }
            } else if (recipe instanceof CraftingRecipe) {
                int n = 0;
                for (Ingredient ing : displayIngredients) { if (!ing.isEmpty()) n++; }
                if (n <= 3) { targetW = n; targetH = 1; }
                else if (n <= 4) { targetW = 2; targetH = 2; }
                else { targetW = 3; targetH = 3; }
                for (int inputIndex = 0; inputIndex < displayIngredients.size(); inputIndex++) {
                    Ingredient ing = displayIngredients.get(inputIndex);
                    if (ing.isEmpty()) continue;
                    ItemStack matched = CraftDisplayMaterials.matchAndConsume(ing, displayAvailable, plannedOutputs);
                    targetInputs.add(matched != null ? matched
                            : CraftDisplayMaterials.firstValidDisplayItem(ing));
                    targetInputRoles.add(displayInputRoles.get(inputIndex));
                }
            } else {
                // Mod recipe: linear layout — re-read specs for per-ingredient counts
                // (displayIngredients is no longer unrolled per-unit).
                List<IngredientSpec> targetSpecs = CraftPacketUtils.extractIngredientSpecs(recipe);
                targetSpecs = CraftPacketUtils.applyPlanningIngredientContext(recipe, player, targetSpecs);
                targetSpecs = MaterialLocks.narrowSpecs(recipeId, targetSpecs, materialLocks);
                if (targetSpecs != null
                        && recipe instanceof SmithingTransformRecipe smithingRecipe
                        && !selectedSmithingBase.isEmpty()) {
                    targetSpecs = SmithingRecipeHandler.requireExactBase(
                            smithingRecipe, targetSpecs, selectedSmithingBase);
                }
                if (targetSpecs != null) {
                    ModRecipeHandler targetHandler = ModRecipeHandlers.claimFor(recipe).winner();
                    int targetInputIndex = 0;
                    for (IngredientSpec spec : targetSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.role() == DemandRole.CATALYST && targetHandler != null
                                ? targetHandler.requiredIngredientCount(
                                        recipe, spec, targetInputIndex, repeatCount)
                                : spec.count();
                        targetInputIndex++;
                        ItemStack matched = CraftDisplayMaterials.matchAndConsume(spec.ingredient(), displayAvailable, plannedOutputs);
                        ItemStack display = matched != null ? matched.copy() : CraftDisplayMaterials.firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        CraftIngredientPlanMath.consumeIngredientCountInPlace(spec.ingredient(), Math.max(0, cnt - 1),
                                displayAvailable, plannedOutputs);
                        targetInputs.add(display);
                        targetInputRoles.add(spec.role());
                    }
                }
            }
            int targetDepth = 0;
            for (PlanStep s : steps) targetDepth = Math.max(targetDepth, s.depth() + 1);

            // Collect OR alternatives for the target recipe itself from RecipeIndex.
            // Must check NBT so tacz:attachment variants (bracelet_zenith vs
            // muzzle_brake_pioneer) aren't cross-contaminated — they share the
            // same base Item but have different NBT and are different products.
            List<ResourceLocation> targetAlts = new ArrayList<>();
            List<String> targetAltModTypes = new ArrayList<>();
            List<RecipeIndex.Entry> targetEntries = recipeIndex.get(planTargetOutput.getItem());
            boolean targetHasTag = planTargetOutput.hasTag();
            if (targetEntries != null) {
                for (RecipeIndex.Entry e : targetEntries) {
                    if (e.recipe().getId().equals(recipeId)) continue;
                    // 花冠根节点可切换花朵；中间步骤仍须保持产物 NBT 一致。
                    if (targetHasTag) {
                        ItemStack altOut;
                        altOut = ModRecipeHandlers.tryGetResultItem(
                                e.recipe(), player.serverLevel().registryAccess());
                        boolean crownVariant = recipe instanceof CraftingRecipe
                                && e.recipe() instanceof CraftingRecipe
                                && LongTravailCrownRecipes.isVariantAlternative(
                                        recipeId, e.recipe().getId(), planTargetOutput, altOut);
                        if (!CraftDisplayMaterials.sameRecipeOutput(altOut, planTargetOutput)
                                && !crownVariant) continue;
                    }
                    // Gate by machine binding too — same rule the resolver and the
                    // intermediate-step alternatives use (hasBindingForRecipe: vanilla
                    // machines are always available, mod machines need a binding). Without
                    // this the target offered recipes for unbound machines (cooking pot,
                    // forge ritual, spirit crucible…) that the player can't actually run.
                    // Selecting this branch performs a fresh recursive plan, so
                    // current inventory must not decide whether the button exists.
                    if (CraftExecutionPolicy.isSmithingRecipe(e.recipe())
                            || AltarBindingRegistry.hasBindingForRecipe(
                                    player, e.recipe(), e.modType())) {
                        if (!CraftMachineBindingSupport.isRecipeAvailableForPlanning(player, e.recipe())) continue;
                        targetAlts.add(e.recipe().getId());
                        targetAltModTypes.add(e.modType().id());
                    }
                }
            }
            if (targetAlts.isEmpty()) { targetAlts = Collections.emptyList(); targetAltModTypes = Collections.emptyList(); }
            RSIntegrationMod.debug("[RSI-OR] target step {}: {} alternatives from index",
                    recipeId, targetAlts.size());

            // Collect plan-time mod warnings (Goety research/structure, FA essences).
            // These render in the "unavailable" area, not inside step cards.
            if (recipeModType != null) {
                PlanWarnings.Result warningResult = PlanWarnings.collectResult(
                        recipeModType.id(), player, recipe, dim, pos,
                        planningEndpoint, steps);
                modWarnings.addAll(warningResult.warnings());
                blockingPrerequisiteFailure |= warningResult.blocksExecution();
            }

            // Arcane Iterator per-level chain: rewrite the target step's center
            // input (index 0, the plain book prepended by filterWRCrystal) to the
            // level-(N-1) book, and snapshot the side materials so the synthesized
            // lower-level steps below reuse the identical side set.
            List<ItemStack> iteratorSideDisplays = null;
            List<DemandRole> iteratorSideRoles = null;
            if (levelBooks != null && !targetInputs.isEmpty()) {
                iteratorSideDisplays = new ArrayList<>();
                iteratorSideRoles = new ArrayList<>();
                for (int i = 1; i < targetInputs.size(); i++) {
                    iteratorSideDisplays.add(targetInputs.get(i).copy());
                    iteratorSideRoles.add(targetInputRoles.get(i));
                }
                targetInputs.set(0, levelBooks[levelBooks.length - 2].copy());
            }

            if (!terminalEmbedded) {
                steps.add(new PlanStep(recipeId, planTargetOutput, repeatCount, targetInputs,
                        targetAlts, recipeModType, targetDepth, !targetAlts.isEmpty(),
                        targetW, targetH, targetAltModTypes, targetInputRoles));
            }

            // Emit only the still-required levels. If a matching level-S book is
            // already available, it is a leaf input and the chain begins at S+1;
            // otherwise S=0 and level I starts from a plain book.
            if (levelBooks != null && iteratorSideDisplays != null) {
                for (int k = levelBooks.length - 1; k > iteratorStartLevel; k--) {
                    List<ItemStack> lvlInputs = new ArrayList<>();
                    List<DemandRole> lvlInputRoles = new ArrayList<>();
                    lvlInputs.add(k >= 2 ? levelBooks[k - 2].copy() : new ItemStack(Items.BOOK));
                    lvlInputRoles.add(DemandRole.CONSUMED);
                    for (ItemStack side : iteratorSideDisplays) lvlInputs.add(side.copy());
                    lvlInputRoles.addAll(iteratorSideRoles);
                    steps.add(new PlanStep(recipeId, levelBooks[k - 1].copy(), repeatCount,
                            lvlInputs, Collections.emptyList(), recipeModType,
                            targetDepth + (levelBooks.length - k), false,
                            0, 0, Collections.emptyList(), lvlInputRoles));
                }
            }

            if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("[RSI-Generic] Target step: ")
                        .append(recipeId).append(" -> ")
                        .append(BuiltInRegistries.ITEM.getKey(planTargetOutput.getItem()))
                        .append(" [");
                for (ItemStack in : targetInputs) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
                }
                sb.append("] grid=").append(targetW).append("x").append(targetH);
                RSIntegrationMod.LOGGER.debug(sb.toString());
            }
        }

        steps = CraftPreviewSteps.linkSlashBladeInputs(steps,
                id -> CraftRecipeResolver.resolve(player.serverLevel(), id), player.serverLevel().registryAccess());

        // Compute material availability for ALL items in the plan.
        // neededCounts: displayItem → how many needed (negative if produced > consumed)
        // itemSource: displayItem → original Ingredient (may be a tag, for aggregate counting)
        // Use a FRESH copy of itemAvailable — displayAvailable was already
        // mutated by the step/target display pass above, and reusing it here
        // causes matchBestAvailable fallback to return arbitrary items from
        // the ingredient array, producing bizarre material requirements.
        Map<Item, Long> matAvailable = new HashMap<>(itemAvailable);
        Map<Item, Long> neededCounts = new LinkedHashMap<>();
        Map<Item, Ingredient> itemSource = new HashMap<>();
        for (ScaledIngredientSpec spec : recipeSpecs) {
            if (spec.isEmpty()) continue;
            Map<Item, Long> consumed = CraftIngredientPlanMath.consumeIngredientCountInPlace(
                    spec.ingredient(), spec.count(), matAvailable);
            for (Map.Entry<Item, Long> entry : consumed.entrySet()) {
                neededCounts.merge(entry.getKey(), entry.getValue().longValue(),
                        SaturatingLongMath::signedAdd);
                itemSource.putIfAbsent(entry.getKey(), spec.ingredient());
            }
        }
        for (PlanStep step : steps) {
            // Target recipe: only subtract its output (inputs already counted
            // in the recipe-spec loop above, don't double-count)
            if (step.recipeId().equals(recipeId)) {
                // A self-amplifying terminal needs one seed before its first
                // execution. Its later outputs may feed later executions, but
                // the final output cannot erase that startup requirement.
                if (!CraftExecutionPolicy.isSelfAmplifyingRecipe(recipe, player.serverLevel().registryAccess())) {
                    neededCounts.merge(step.output().getItem(), -step.totalOutputCount(),
                            SaturatingLongMath::signedAdd);
                }
                continue;
            }
            Recipe<?> stepRecipe = CraftRecipeResolver.resolve(player.serverLevel(), step.recipeId());
            if (stepRecipe == null) continue;
            List<IngredientSpec> specs = stepRecipe instanceof CraftingRecipe craftingRecipe
                    ? CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe)
                    : CraftPacketUtils.extractIngredientSpecs(stepRecipe);
            specs = CraftPacketUtils.applyPlanningIngredientContext(stepRecipe, player, specs);
            if (specs != null) {
                ModRecipeHandler stepHandler = ModRecipeHandlers.claimFor(stepRecipe).winner();
                int stepInputIndex = 0;
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    ItemStack matched = CraftDisplayMaterials.matchAndConsume(spec.ingredient(), matAvailable);
                    if (matched != null) {
                        long required = stepHandler != null
                                ? stepHandler.requiredIngredientCountLong(
                                        stepRecipe, spec, stepInputIndex, step.batches())
                                : CraftPacketUtils.requiredCount(spec, step.batches());
                        neededCounts.merge(matched.getItem(),
                                required, SaturatingLongMath::signedAdd);
                        itemSource.putIfAbsent(matched.getItem(), spec.ingredient());
                    }
                    stepInputIndex++;
                }
            }
        }
        // Subtract what intermediate steps produce (skip target — already done above).
        for (PlanStep step : steps) {
            if (step.recipeId().equals(recipeId)) continue;
            neededCounts.merge(step.output().getItem(), -step.totalOutputCount(),
                    SaturatingLongMath::signedAdd);
        }

        // FA smithing: the target output shares the baseItem's Item type, so the
        // target-output subtraction above canceled out the baseItem.  Add it back
        // so the material panel shows the base item as a required material.
        if (faRecipe && baseItem != null && !baseItem.isEmpty()) {
            neededCounts.merge(baseItem.getItem(), (long) repeatCount,
                    SaturatingLongMath::signedAdd);
        }

        // Add fuel requirement for machine recipes that consume fuel items.
        // CrockPot: any burnable item (coal, charcoal, etc.)
        // Aether: machine-specific fuel selected from the recipe family.
        long requiredFuelBurnUnits =
                CraftPacketUtils.estimatedFuelBurnUnits(recipe, repeatCount);
        CrockPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, requiredFuelBurnUnits);
        AetherFurnaceBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null, recipe,
                itemAvailable, itemSource, neededCounts, repeatCount);
        // EnchantalCooler: lapis lazuli or fuel tag items
        EnchantalCoolerBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);
        CookingPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);
        MokaPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);

        CraftPlanGraph displayGraph = planGraph != null ? planGraph : cacheInputGraph;
        boolean reuseTypedEstimates = completedTyped != null && displayGraph == completedTyped.graph();
        var parallelEstimates = reuseTypedEstimates ? null : new ParallelPlanEstimator.Request(player, itemAvailable);
        PlanGraphView planGraphView = displayGraph != null
                ? PlanGraphView.from(displayGraph, reuseTypedEstimates
                        ? completedTyped.parallelPlans() : parallelEstimates.estimate(displayGraph))
                        .withRecipeAlternatives(steps)
                : null;
        if (planGraphView != null) {
            planGraphView = CraftDisplayMaterials.attachMaterialRecipeOptions(
                    planGraphView, recipeIndex, player.serverLevel().registryAccess(),
                    (candidate, type) -> CraftMachineBindingSupport.isRecipeAvailableForPlanning(
                            player, candidate)
                            && (CraftExecutionPolicy.isSmithingRecipe(candidate)
                            || AltarBindingRegistry.hasBindingForRecipe(player, candidate, type)));
        }
        Map<ResourceLocation, PlanResponse.StepIssue> stepIssues = new LinkedHashMap<>();
        if (resolutionSteps != null) {
            for (ResolutionStep step : resolutionSteps) {
                if (step.productionTarget() == null) continue;
                Recipe<?> plannedRecipe = CraftRecipeResolver.resolve(player.serverLevel(), step.recipeId());
                ModRecipeHandler handler = plannedRecipe == null ? null
                        : ModRecipeHandlers.claimFor(plannedRecipe).winner();
                if (handler == null) continue;
                boolean targeted = step.productionTarget().resource() != null
                        ? handler.requiresTargetedProduction(
                                plannedRecipe,
                                step.productionTarget().resource())
                        : step.productionTarget().material() != null
                        && handler.requiresTargetedProduction(
                                plannedRecipe,
                                step.productionTarget().material().toStack(1));
                if (!targeted) continue;
                Component warning = Component.translatable("rsi.plan.probability.estimate",
                        step.productionTarget().displayStack().getHoverName(),
                        step.productionTarget().quantity());
                stepIssues.put(step.recipeId(), new PlanResponse.StepIssue(List.of(warning), false));
                modWarnings.add(warning);
            }
        }
        Set<ResourceLocation> intermediateGoetyRecipes = new LinkedHashSet<>();
        for (PlanStep step : steps) {
            String modTypeId = step.modType() != null ? step.modType().id() : "";
            if (!step.recipeId().equals(recipeId)
                    && (ModIds.GOETY.equals(modTypeId) || "goety_brazier".equals(modTypeId))) {
                intermediateGoetyRecipes.add(step.recipeId());
            }
        }
        if (planGraphView != null) {
            for (PlanGraphView.NodeView node : planGraphView.nodes()) {
                if (!node.recipeId().equals(recipeId)
                        && (ModIds.GOETY.equals(node.modTypeId())
                        || "goety_brazier".equals(node.modTypeId()))) {
                    intermediateGoetyRecipes.add(node.recipeId());
                }
            }
        }
        for (ResourceLocation intermediateRecipeId : intermediateGoetyRecipes) {
            Recipe<?> intermediateRecipe = CraftRecipeResolver.resolve(player.serverLevel(), intermediateRecipeId);
            if (intermediateRecipe == null) continue;
            GoetyBatchDelegate.PlanPrerequisiteCheck check =
                    GoetyBatchDelegate.checkPlanPrerequisites(
                            player, intermediateRecipe, null, null);
            if (!check.warnings().isEmpty() || check.blocked()) {
                stepIssues.put(intermediateRecipeId,
                        new PlanResponse.StepIssue(check.warnings(), check.blocked()));
                for (Component warning : check.warnings()) {
                    if (!modWarnings.contains(warning)) modWarnings.add(warning);
                }
            }
            blockingPrerequisiteFailure |= check.blocked();
        }
        PlanMaterialBill.Result materialBill = PlanMaterialBill.summarize(
                neededCounts, itemSource, itemAvailable, available, planTargetOutput,
                steps, repeatCount, planGraphView, !missing.isEmpty());
        Map<IngredientKey, PlanResponse.Availability> materials = directTerminalPlan
                ? new LinkedHashMap<>(directMaterialView(directPreviewAllocation, available))
                : new LinkedHashMap<>(materialBill.materials());
        Map<IngredientKey, Long> leftovers = materialBill.leftovers();
        boolean feasible = directTerminalPlan || materialBill.feasible();
        CraftMachineBindingSupport.PlanAvailability machineAvailability =
                CraftMachineBindingSupport.inspectPlanAvailability(
                        player, recipeId, recipeModType, steps, dim, pos);
        boolean missingExecutionBinding =
                machineAvailability.missingExecutionBinding();
        // A plan may still be displayed without a bound physical machine, but
        // it must never be advertised as executable in that state.
        if (missingExecutionBinding) feasible = false;

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            long shortageCount = materials.values().stream().filter(a -> a.missingCount() > 0).count();
            RSIntegrationMod.debug("[RSI-Generic] Plan for {}: {} steps, feasible={}, resolver={}, missing={}, shortages={}",
                    recipeId, steps.size(), feasible, usedTypedResolver ? "typed" : "pure",
                    missing.size(), shortageCount);
        }
        // Dedup missing items — the resolver may add the same ingredient
        // name for every step that references it, producing an unreadable wall.
        List<String> dedupedMissing = missing.stream().distinct().toList();

        String targetName = ItemStackUtils.registryId(planTargetOutput);

        // ── Embers Alchemy: lookup cached codes from prior inference ──
        EmbersPlanInfo embersInfo = EmbersPlanInfo.build(
                player, recipe, planningEndpoint, recipeId,
                recipeModType != null ? recipeModType.id() : null,
                dim, pos);

        // Inject aspectus catalysts into the material map at count 1 (not
        // multiplied by repeatCount). Catalysts are placed once per craft and
        // recycled; they are not consumed. Without this, the plan would show
        // zero aspectus requirement, making feasibility checks inaccurate.
        if (embersInfo.code() != null
                && ModIds.ID_EMBERS_ALCHEMY.equals(recipeModType != null ? recipeModType.id() : null)) {
            try {
                var af = Reflect.findField(recipe.getClass(), "aspects");
                if (af.isPresent()) {
                    af.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var aspects = (List<Ingredient>) af.get().get(recipe);
                    if (aspects != null) {
                        for (int ci : embersInfo.code()) {
                            if (ci < 0 || ci >= aspects.size()) continue;
                            Ingredient aspectIng = aspects.get(ci);
                            for (ItemStack opt : aspectIng.getItems()) {
                                if (opt.isEmpty()) continue;
                                IngredientKey key = IngredientKey.of(opt);
                                materials.merge(key,
                                        new PlanResponse.Availability(1,
                                                itemAvailable.getOrDefault(key.item(), 0L)),
                                        (old, neu) -> new PlanResponse.Availability(
                                                old.needed(), Math.max(old.available(), neu.available()),
                                                Math.max(old.missingCount(), neu.missingCount())));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Catalyst injection into plan materials failed", e);
            }
            // Reassess feasibility after injecting catalyst items — the player
            // may be missing aspectus items that are required for execution.
            feasible = feasible && materials.values().stream().allMatch(a -> a.missingCount() == 0);
        }

        boolean executionMachineSupportsGui =
                machineAvailability.executionMachineSupportsGui();
        Set<String> boundMachineTypes =
                machineAvailability.boundMachineTypes();
        boolean allExecutionMachinesLeased =
                machineAvailability.allExecutionMachinesLeased();
        if (allExecutionMachinesLeased) feasible = false;

        // 已转入 typed 的纯图失败只作路由证据，不能覆盖最终诊断或进入成功计划缓存。
        PureRecipePlanner.Result responsePurePlan = usedPurePlan ? precomputedPlan : null;
        boolean nbtMismatch = false;
        if (!feasible) {
            nbtMismatch = CraftPlanDecisionRules.hasNbtMismatch(materials, itemAvailable);
            boolean bindingBlocked = responsePurePlan != null
                    && CraftPlanDecisionRules.allMissingRequireBlockedOutput(
                            responsePurePlan.missing(),
                    planningSnapshot.bindingBlockedOutputIds());
            if (allExecutionMachinesLeased) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.machines_leased"));
            } else if (bindingBlocked) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.no_bound_machine"));
            } else if (CraftPlanDecisionRules.hasCyclicPureFailure(responsePurePlan)) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.cyclic_dependency"));
            } else if (nbtMismatch) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.nbt_mismatch"));
            } else if (!dedupedMissing.isEmpty() || materials.values().stream()
                    .anyMatch(a -> a.missingCount() > 0)) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.missing_materials"));
            } else if (missingExecutionBinding || (recipeModType != null
                    && !boundMachineTypes.contains(recipeModType.id()))) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.no_bound_machine"));
            }
        }

        CraftPlanPrerequisiteInspector.Result prerequisiteResult =
                CraftPlanPrerequisiteInspector.inspect(
                        player, recipe, recipeId, repeatCount, planningEndpoint,
                        planGraph, steps, available, recipeModType, feasible,
                        blockingPrerequisiteFailure);
        modWarnings.addAll(prerequisiteResult.warnings());
        feasible = prerequisiteResult.feasible();
        blockingPrerequisiteFailure =
                prerequisiteResult.blockingPrerequisiteFailure();
        List<MachineCandidateView> machineCandidates =
                prerequisiteResult.machineCandidates();
        boolean noReadyMachine = prerequisiteResult.noReadyMachine();

        boolean materialShortage = !dedupedMissing.isEmpty()
                || materials.values().stream().anyMatch(a -> a.missingCount() > 0);
        List<IntegrationDiagnostic> diagnostics = new ArrayList<>();
        boolean cyclicPureFailure =
                CraftPlanDecisionRules.hasCyclicPureFailure(responsePurePlan);
        CraftPlanOutcomeResolver.Resolution outcomeResolution =
                CraftPlanOutcomeResolver.resolve(
                        feasible, timedOutMissingRoute, allExecutionMachinesLeased,
                        missingExecutionBinding,
                        recipeModType != null
                                && !boundMachineTypes.contains(recipeModType.id()),
                        noReadyMachine, cyclicPureFailure, nbtMismatch,
                        materialShortage, blockingPrerequisiteFailure);
        PlanOutcome outcome = outcomeResolution.outcome();
        if (outcomeResolution.diagnostic() != null) {
            diagnostics.add(IntegrationDiagnostic.error(
                    outcomeResolution.diagnostic()));
        }
        if (outcome == PlanOutcome.CYCLE) {
            if (PREVIEW_FAILURE_LOGS.allow("cycle:" + recipeId)) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-plan] cycle failure recipe={} target={} choice={} excluded={} forced={} steps={} missing={}",
                        recipeId, ItemStackUtils.registryId(rootTarget),
                        safePreferences.choiceFor(rootTarget),
                        safePreferences.excludedRecipes(), forcedOverrides,
                        steps.stream().map(step -> step.recipeId().toString()).toList(),
                        CraftPlanDecisionRules.summarizePureDemands(precomputedPlan == null
                                ? List.of() : precomputedPlan.missing()));
            }
        }

        // 单层配方没有 CraftPlanGraph，但仍要让递归树显示会并行到几台机器、
        // 每台分多少、需要多少燃料/催化剂/耐久工具。
        List<PlanStep> displaySteps = steps.stream()
                .map(step -> step.withParallelPlan(reuseTypedEstimates
                        ? ParallelPlanEstimator.forDisplay(completedTyped.displayPlans(), step)
                        : parallelEstimates.estimateStep(step)))
                .toList();

        PlanResponseDraft responseDraft = new PlanResponseDraft(
                feasible,
                targetName,
                planTargetOutput,
                displaySteps,
                materials,
                dedupedMissing,
                recipeId.toString(),
                recipeModType != null ? recipeModType.id() : null,
                dim != null ? dim.toString() : null,
                pos != null ? pos.getX() : 0,
                pos != null ? pos.getY() : 0,
                pos != null ? pos.getZ() : 0,
                modWarnings,
                repeatCount,
                embersInfo.code(),
                embersInfo.aspectNames(),
                embersInfo.inputNames(),
                embersInfo.code() != null ? 0 : embersInfo.seed(),
                embersInfo.canInfer(),
                embersInfo.codeFromCache(),
                executionMachineSupportsGui,
                !selectedSmithingBase.isEmpty() ? selectedSmithingBase : baseItem,
                boundMachineTypes,
                leftovers,
                clickedOutput,
                planGraphView,
                blockingPrerequisiteFailure,
                machineCandidates,
                stepIssues,
                planStorageReference,
                storageNetworks,
                outcome,
                diagnostics
        );

        int responseStepCount = displaySteps.size();
        int responseGraphNodes = planGraphView != null ? planGraphView.nodes().size() : 0;
        boolean responseFeasible = feasible;
        CraftPlanGraph resolvedGraphForCache =
                cacheInputGraph != null ? cacheInputGraph : planGraph;
        CraftPlanResponseFinalizer.submit(
                player, recipeId, previewGeneration, planningSnapshot, responseDraft,
                planDimKey, planLookupPos, planStorageReference, cacheKey,
                responsePurePlan, resolvedGraphForCache, terminalEmbedded, responseStepCount,
                responseGraphNodes, responseFeasible, planStartNanos, sink);
    }

    private static Map<Item, Long> aggregateAvailableItems(Map<StackKey, Long> available) {
        Map<Item, Long> itemAvailable = new HashMap<>();
        for (var entry : available.entrySet()) {
            itemAvailable.merge(entry.getKey().item(), entry.getValue(), SaturatingLongMath::add);
        }
        return itemAvailable;
    }

    static boolean requiresFreshPreview(@Nullable ModType terminalType, @Nullable PlanResponse plan) {
        if (terminalType != null && (ModIds.ID_EMBERS_STAMPER.equals(terminalType.id())
                || "crockpot".equals(terminalType.id())
                || ModIds.ID_SUMMONING_RITUALS.equals(terminalType.id()))) return true;
        return plan != null && plan.steps().stream().anyMatch(step ->
                step.modType() != null && (ModIds.ID_EMBERS_STAMPER.equals(step.modType().id())
                        || "crockpot".equals(step.modType().id())));
    }

    /** Projects each physical stack once so broad and exact-NBT demands cannot reuse it. */
    private static Map<ImmutableRecipeGraph.MaterialRef, Long> routingAvailability(
            Map<StackKey, Long> available) {
        return ImmutableRecipeGraphProjector.projectAvailability(available);
    }

}
