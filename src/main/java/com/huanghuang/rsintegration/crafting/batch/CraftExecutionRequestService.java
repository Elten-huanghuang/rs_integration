package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.MaterialSourceSelection;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsDynamicApparatusRecipe;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotCategoryPlanning;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotInventory;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyRecipeHandler;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.machine.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** 解析执行请求，校验确认计划并向统一提交服务交接。 */
final class CraftExecutionRequestService {
    private static final LogSampler FAILURE_LOG_SAMPLER = new LogSampler(2_000);
    @Nullable
    private static volatile Map<String, Set<Item>> itemTypesByDescriptionIdCache;

    private CraftExecutionRequestService() {}

    private static Map<StackKey, Long> listAvailable(ServerPlayer player,
                                                          @Nullable CraftStorageEndpoint endpoint) {
        return MaterialSources.listAllAvailableFresh(player, endpoint);
    }

    private static DirectReservationCheck probeDirectTerminalReservation(
            ServerPlayer player, ResourceLocation recipeId,
            List<ScaledIngredientSpec> specs,
            Map<StackKey, Long> available, @Nullable CraftStorageEndpoint endpoint) {
        if (specs.stream().noneMatch(spec -> spec != null && !spec.isEmpty())) {
            return new DirectReservationCheck(false, "no-fixed-inputs", "unknown");
        }
        DirectMaterialAllocator.Result allocation =
                DirectMaterialAllocator.allocateScaled(specs, available);
        if (!allocation.feasible()) {
            int index = allocation.missingIngredientIndex();
            ScaledIngredientSpec missing =
                    index >= 0 && index < specs.size() ? specs.get(index) : null;
            String material = missing == null ? "unknown"
                    : CraftPacketUtils.describeIngredient(missing.ingredient()).getString();
            String nbtMode = missing == null ? "unknown"
                    : ImmutableRecipeGraphProjector.nbtMatchMode(missing.ingredient()).name();
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-DirectReserve] unavailable recipe={} ingredientIndex={} material={} missing={} nbtMode={}",
                    recipeId, index, material, allocation.missingCount(), nbtMode);
            return new DirectReservationCheck(false, material, nbtMode);
        }

        try (ExtractionLedger probe = new ExtractionLedger()) {
            if (endpoint != null) probe.setStorageEndpoint(endpoint);
            for (DirectMaterialAllocator.Allocation fragment : allocation.allocations()) {
                ItemStack template = fragment.material().toStack();
                long remaining = fragment.count();
                while (remaining > 0L) {
                    int chunk = SaturatingLongMath.clampToInt(remaining);
                    ItemStack reserved = endpoint != null
                            ? probe.reserveExactAcrossNetworkAndInventory(
                                    template, chunk, endpoint, player)
                            : probe.reserveExactAcrossNetworkAndInventory(
                                    template, chunk, (CraftStorageEndpoint) null, player);
                    if (reserved.isEmpty() || reserved.getCount() != chunk) {
                        probe.rollback(player);
                        String material = String.valueOf(BuiltInRegistries.ITEM.getKey(template.getItem()))
                                + (template.hasTag() ? template.getTag() : "");
                        RSIntegrationMod.LOGGER.debug(
                                "[RSI-DirectReserve] live reservation rejected recipe={} material={} count={} directAllocation=true",
                                recipeId, material, remaining);
                        return new DirectReservationCheck(false, material, "EXACT_ALLOCATION");
                    }
                    remaining -= chunk;
                }
            }
            probe.rollback(player);
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-DirectReserve] ready recipe={} specs={} fragments={} plannerBypassed=true",
                recipeId, specs.size(), allocation.allocations().size());
        return new DirectReservationCheck(true, "", "");
    }

    private record DirectReservationCheck(boolean available, String missingMaterial,
                                          String nbtMode) {}

    /** Budget for the typed live-object resolver. */
    private static int typedResolverBudgetMs() {
        try {
            return typedResolverBudgetMs(
                    RSIntegrationConfig.CRAFTING_RESOLVE_TIMEOUT_MS.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS;
        }
    }

    static int typedResolverBudgetMs(int resolveTimeoutMs) {
        return Math.max(1, resolveTimeoutMs);
    }

    @Nullable
    private static List<IngredientSpec> crockPotInputs(Recipe<?> recipe, @Nullable ItemStack target,
            @Nullable CraftStorageEndpoint endpoint, ServerPlayer player,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dimension, player);
        var specs = CrockPotCategoryPlanning.ingredients(CrockPotCategoryPlanning.capture(
                recipe, target, endpoint, player, level, CrockPotInventory.planningSlots(level, position)));
        if (specs == null) player.sendSystemMessage(Component.translatable("rsi.crockpot.error.food_values"));
        return specs;
    }

    private static boolean tryExecuteCachedCraftingPlan(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dim,
            @Nullable BlockPos pos, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            PlanCache.Entry cached, Map<String, ItemStack> materialLocks, long confirmedPreviewRequestId) {
        ModType terminalType = CraftPlanningApplicationService.modTypeForCachedExecution(recipe);
        if (cached.resolvedGraph() == null) {
            // 已展示的成功计划不能在确认后改走另一条未展示的递归路线。
            player.sendSystemMessage(Component.translatable(
                    "rsi.plan.failure.planning_cancelled"));
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Generic] Refusing preview without material flow recipe={}", recipeId);
            return true;
        }
        // 回传物品只用于确认身份；显示同步字段不能覆盖服务端计划的产物 NBT 或能力。
        targetOutput = CraftConfirmationTarget.executionTarget(cached.plan()).copy();
        ItemStack recipeOutput = resolveCachedRecipeOutput(
                recipe, player.serverLevel().registryAccess(), targetOutput);
        if (recipeOutput.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planning_cancelled"));
            return true;
        }
        ResolutionStep terminalStep = terminalType == ModType.GENERIC
                && recipe instanceof CraftingRecipe
                ? CraftTerminalSteps.genericTerminalStep(recipeId, repeatCount)
                : CraftTerminalSteps.backgroundPhysicalTerminalStep(
                        recipeId, terminalType, inferMode, repeatCount);
        recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
        try {
            CraftPlanGraph completeGraph = cached.terminalEmbedded()
                    ? cached.resolvedGraph()
                    : CraftTerminalSteps.composeEquivalentTerminalGraph(
                            cached.resolvedGraph(), terminalStep, recipeOutput,
                            player.serverLevel());
            RSIntegrationMod.debug(
                    "[RSI-Generic] Executing revalidated preview input graph for {} nodes={}",
                    recipeId, completeGraph.nodes().size());
            CraftExecutionService.launchGraph(player, storageEndpoint, completeGraph,
                    recipeId, null, targetOutput, outputDestination,
                    machineSelectionMode, dim, pos, forcedRecipes, materialLocks,
                    confirmedPreviewRequestId, chain -> {});
            return true;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Generic] Cached typed graph cannot be composed for {}",
                    recipeId, exception);
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.unsupported_machine",
                    recipe.getClass().getSimpleName()));
            return true;
        }
    }

    static ItemStack resolveCachedRecipeOutput(
            Recipe<?> recipe, RegistryAccess access, @Nullable ItemStack targetOutput) {
        ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(recipe, access);
        if (GoetyDynamicRitualRecipe.isSupported(recipe)) {
            recipeOutput = GoetyDynamicRitualRecipe.validatedOutput(recipe, targetOutput);
        }
        return recipeOutput;
    }

    private static boolean tryPrepareIntermediateMaterials(
            ServerPlayer player, ResourceLocation recipeId, Recipe<?> recipe,
            @Nullable ModType modType, List<ScaledIngredientSpec> specs,
            ItemStack recipeOutput,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            CraftStorageEndpoint storageEndpoint,
            MachineSelectionMode machineSelectionMode,
            @Nullable ResourceLocation dim, @Nullable BlockPos pos,
            Map<String, ItemStack> materialLocks) {
        Map<StackKey, Long> available = listAvailable(player, storageEndpoint);
        List<String> missing = new ArrayList<>();
        CraftPlanGraph graph;
        try {
            graph = CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)
                    ? CraftingResolver.resolveMachineGraphForScaledSpecsWithTypes(
                            specs, available, player.serverLevel(), player, storageEndpoint, missing,
                            forcedOverrides, true, typedResolverBudgetMs(),
                            new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks)
                    : CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                            specs, available, player.serverLevel(), player, storageEndpoint, missing,
                            forcedOverrides, true, typedResolverBudgetMs(),
                            new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks);
        } catch (CraftingPlanningTimeoutException timeout) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.preparation.failed", recipeId.toString()));
            return false;
        }

        Component unresolvedIntermediate = CraftPreparationPlans.summarizeUnresolvedInputs(graph);
        Component stillMissing = CraftPreparationPlans.summarizePreparationMissing(graph);
        if (stillMissing == null && !missing.isEmpty()) {
            stillMissing = CraftPacketUtils.formatMissingSummary(missing);
        }
        CraftPlanGraph executableGraph = CraftPreparationPlans.pruneBlocked(graph);
        if (!CraftPreparationPlans.isExecutable(executableGraph)) {
            if (!graph.unresolvedDemands().isEmpty()) {
                if (unresolvedIntermediate == null && !missing.isEmpty()) {
                    unresolvedIntermediate = CraftPacketUtils.formatMissingSummary(missing);
                }
                if (unresolvedIntermediate == null) {
                    unresolvedIntermediate = Component.translatable("rsi.plan.unknown_item");
                }
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.preparation.blocked", unresolvedIntermediate));
                return false;
            }
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.preparation.none", recipeId.toString(),
                    stillMissing == null ? Component.literal("-") : stillMissing));
            return false;
        }

        if (executableGraph.nodes().size() != graph.nodes().size()) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Preparation] partial graph recipe={} keptNodes={} skippedNodes={} unresolvedInputs={}",
                    recipeId, executableGraph.nodes().size(),
                    graph.nodes().size() - executableGraph.nodes().size(),
                    graph.unresolvedDemands().size());
        }
        Component products = CraftPreparationPlans.summarizePreparedOutputs(executableGraph);
        if (products == null) products = Component.translatable("rsi.plan.unknown_item");
        if (stillMissing == null) stillMissing = Component.literal("-");
        CraftExecutionService.launchPreparation(player, executableGraph, recipeId, storageEndpoint,
                dim, pos, machineSelectionMode, products, stillMissing, materialLocks, forcedOverrides);
        return true;
    }

    private static void launchGraphAsyncChain(ServerPlayer player, CraftPlanGraph graph,
                                               @Nullable CraftStorageEndpoint storageEndpoint,
                                               ResourceLocation recipeId,
                                               Map<String, String> forcedRecipes,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos,
                                               @Nullable ItemStack targetOutput,
                                               OutputDestination outputDestination,
                                               MachineSelectionMode machineSelectionMode, Map<String, ItemStack> materialLocks) {
        CraftExecutionService.launchGraph(
                player, storageEndpoint, graph, recipeId, null, targetOutput,
                outputDestination, machineSelectionMode, dim, pos, forcedRecipes,
                materialLocks, 0L, chain -> {});
    }

    static void tryResolveTypedGraphOnly(
                                   ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode,
                                   @Nullable StorageReference storageReference,
                                   Map<String, ItemStack> materialLocks) {
        tryResolve(player, recipeId, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageReference, materialLocks, true, false, PlanningPreferences.empty(), 0L);
    }

    static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode,
                                   @Nullable StorageReference storageReference,
                                   Map<String, ItemStack> materialLocks,
                                   boolean typedGraphOnly,
                                   boolean partialPreparation,
                                   PlanningPreferences preferences, long confirmedPreviewRequestId) {
        PlanningPreferences selected = preferences == null ? PlanningPreferences.empty() : preferences;
        try (MaterialSourceSelection.Scope ignored = selected.materialSources().enter()) {
            tryResolveWithSources(player, recipeId, forcedRecipes, dim, pos, repeatCount, inferMode,
                    baseItem, targetOutput, outputDestination, machineSelectionMode, storageReference,
                    materialLocks, typedGraphOnly, partialPreparation, selected, confirmedPreviewRequestId);
        }
    }

    private static void tryResolveWithSources(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim, @Nullable BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode,
                                   @Nullable StorageReference storageReference,
                                   Map<String, ItemStack> materialLocks, boolean typedGraphOnly,
                                   boolean partialPreparation, PlanningPreferences preferences,
                                   long confirmedPreviewRequestId) {
        PlanningPreferences safePreferences = preferences == null
                ? PlanningPreferences.empty() : preferences;
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

        Recipe<?> recipe = CraftRecipeResolver.resolve(player.serverLevel(), recipeId);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }
        if (safePreferences.excludes(recipeId)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.plan.failure.recipe_excluded"));
            return;
        }
        if (HistoryStagesCompat.isRecipeLocked(recipe, player)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_stage_missing"));
            return;
        }
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !CraftPacketUtils.isCraftingRecipeAvailable(craftingRecipe, player)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_stage_missing"));
            return;
        }
        ModType modType = CraftMachineBindingSupport.resolveExecutionModType(player, recipe, dim, pos);

        // FA ApplyModifierRecipe: no fixed base item, so auto-crafting is impossible.
        // Redirect to opening the smithing table GUI with template & addition pre-filled.
        if (OpenBoundMachineGuiPacket.isFaApplyModifier(recipe)) {
            OpenBoundMachineGuiPacket.openSmithingForFaModifier(player, recipeId, baseItem);
            return;
        }

        boolean arsDynamic = ArsDynamicApparatusRecipe.isSupported(recipe);
        boolean goetyDynamic = GoetyDynamicRitualRecipe.isSupported(recipe);
        List<IngredientSpec> specs;
        if (arsDynamic) {
            ItemStack validated = ArsDynamicApparatusRecipe.validatedOutput(recipe, targetOutput);
            specs = ArsDynamicApparatusRecipe.buildMaterials(recipe, targetOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
                return;
            }
        } else if (goetyDynamic) {
            ItemStack validated = GoetyDynamicRitualRecipe.validatedOutput(recipe, targetOutput);
            specs = GoetyDynamicRitualRecipe.buildMaterials(recipe, targetOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
                return;
            }
        } else {
            specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            specs = CraftPacketUtils.applyPlanningIngredientContext(recipe, player, specs);
        }
        if (modType != null && ModIds.ID_MD_COPPER_POT.equals(modType.id())) {
            specs = MinersDelightCopperPotSupport.adaptIngredientSpecs(
                    specs, recipe, player.serverLevel().registryAccess());
        }
        if (specs == null || specs.isEmpty()) {
            // 纯类别配方可没有固定原料，选定库存端点和机器后才能求解。
            if (!CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.no_ingredients"));
                return;
            }
            specs = new ArrayList<>();
        }

        if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
            specs = SmithingRecipeHandler.requireExactBase(smithingRecipe, specs, baseItem);
        }

        // Group non-empty ingredients by item type with total count
        Map<String, IngredientNeed> grouped = new LinkedHashMap<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            String key = spec.ingredient().toJson().toString();
            grouped.computeIfAbsent(key, k -> new IngredientNeed(spec.ingredient(), 0)).count += spec.count();
        }

        if (!partialPreparation && repeatCount > 1
                && GoetyRecipeHandler
                .requiresManualConfirmation(recipe)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.goety.error.manual_single_only"));
            return;
        }
        if (!partialPreparation
                && CraftExecutionPolicy.requiresBoundMachine(recipe, modType)
                && !CraftMachineBindingSupport.hasBindingForExecutionType(player, recipe, modType)) {
            CraftMachineBindingSupport.logBindingRejection("execute-entry", player, recipe, modType, dim, pos);
            // A wrapped smithing recipe can still reach this guard if a
            // third-party classifier misses it. Keep the binding failure
            // user-facing instead of dereferencing a nullable type while
            // constructing the error message.
            String bindingTypeId = modType != null
                    ? modType.id()
                    : CraftExecutionPolicy.isSmithingRecipe(recipe) ? "smithing" : "unknown";
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.no_bound_machine", bindingTypeId));
            return;
        }
        if (!partialPreparation && modType != null && modType.isVirtual()) {
            ModRecipeHandler virtualHandler = ModRecipeHandlers.claimFor(recipe).winner();
            if (virtualHandler == null || virtualHandler.modType(recipe) != modType
                    || !virtualHandler.isAvailableForPlanning(recipe, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.execution_context", recipeId));
                return;
            }
        }
        // Input storage and output destination are independent. Even when the
        // result is sent to the player's inventory, ingredients may still be
        // reserved from the resolved RS/backend network.
        // RS is an optional storage backend. Standalone inventory crafting must
        // never link or invoke its NetworkItem classes when RS is absent.
        CraftStorageEndpoint storageEndpoint;
        StorageReference selectedStorageReference = storageReference;
        if (storageReference != null) {
            var resolved = CraftStorageEndpoints.resolve(storageReference, player);
            if (resolved.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Storage] selected reference unavailable; refusing backend fallback backend={} id={} player={}",
                        storageReference.backendId(), storageReference.networkId(),
                        player.getGameProfile().getName());
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.network_unavailable"));
                return;
            }
            storageEndpoint = resolved.orElseThrow();
            selectedStorageReference = storageEndpoint.session().reference();
        } else {
            // 未指定引用时由统一选择服务决定绑定、默认和发现网络。
            storageEndpoint = CraftPlanStorageService.resolveEndpoint(
                    player, dim, pos, modType);
            if (storageEndpoint != null) {
                selectedStorageReference = storageEndpoint.session().reference();
            }
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ExecAvail] recipe={} preparation={} endpoint={} reference={} destination={}",
                recipeId, partialPreparation,
                storageEndpoint == null ? "none" : storageEndpoint.session().reference().backendId(),
                selectedStorageReference == null ? "default" : selectedStorageReference,
                outputDestination);
        // The preview snapshot is fingerprinted with the canonical selected
        // backend reference.  Execution may arrive with a legacy/null packet
        // reference, so validate against the endpoint actually selected above
        // or the cache will be rejected on every confirmation.
        final StorageReference executionStorageReference = selectedStorageReference;

        // Auto-select a bound machine for mod recipes when dim/pos are not
        // explicitly provided (e.g. triggered from RS terminal instead of
        // a specific machine's JEI page).  Without this, mod recipes fall
        // bypass the machine entirely and may fail or give results for free.
        ResourceLocation effectiveDim = dim;
        BlockPos effectivePos = pos;
        if ((effectiveDim == null || effectivePos == null) && modType != null && modType.isVirtual()) {
            effectiveDim = player.level().dimension().location();
            effectivePos = player.blockPosition();
        } else if ((effectiveDim == null || effectivePos == null)
                && CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)) {
            String reqKeyword = CraftRecipeResolver.machineKeyword(recipe);
            List<AltarBindingRegistry.BoundMachine> boundMachines = new ArrayList<>(
                    AltarBindingRegistry.getBoundMachinesForRecipe(player, modType, recipeId));
            if (ModIds.GOETY.equals(modType.id())) {
                MachineCandidateView ready = GoetyBatchDelegate.firstReadyPlanMachine(
                        player, recipe, storageEndpoint);
                if (ready != null) {
                    boundMachines.sort(Comparator.comparingInt(machine ->
                            machine.dim().toString().equals(ready.dimension())
                                    && machine.pos().getX() == ready.x()
                                    && machine.pos().getY() == ready.y()
                                    && machine.pos().getZ() == ready.z() ? 0 : 1));
                }
            } else if (ModIds.ID_SUMMONING_RITUALS.equals(modType.id())) {
                List<MachineCandidateView> candidates =
                        SummoningRitualAltarBatchDelegate.getPlanMachineCandidates(player, recipe);
                MachineCandidateView ready = candidates.stream()
                        .filter(candidate -> candidate.state() == MachineCandidateView.State.READY)
                        .findFirst().orElse(null);
                if (ready != null) {
                    boundMachines.sort(Comparator.comparingInt(machine ->
                            machine.dim().toString().equals(ready.dimension())
                                    && machine.pos().getX() == ready.x()
                                    && machine.pos().getY() == ready.y()
                                    && machine.pos().getZ() == ready.z() ? 0 : 1));
                }
            }
            for (var m : boundMachines) {
                if (reqKeyword != null && m.blockKey() != null && !m.blockKey().contains(reqKeyword))
                    continue;
                effectiveDim = m.dim();
                effectivePos = m.pos();
                if (storageEndpoint == null && storageReference == null) {
                    storageEndpoint = CraftPlanStorageService.resolveEndpoint(
                            player, effectiveDim, effectivePos, modType);
                }
                break;
            }
        }

        // Explicit preparation mode is deliberately handled before every
        // terminal/direct-extraction path. It resolves only the terminal's
        // input graph, so the requested recipe can never be executed here.
        if (partialPreparation) {
            if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.auto_craft_disabled"));
                return;
            }
            if (storageEndpoint == null) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.network_unavailable"));
                return;
            }
            ItemStack preparationOutput = CraftMachineBindingSupport.routeRecipeOutput(modType,
                    ModRecipeHandlers.tryGetResultItem(
                            recipe, player.serverLevel().registryAccess()));
            if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                specs = crockPotInputs(recipe, targetOutput, storageEndpoint, player, effectiveDim, effectivePos);
                if (specs == null) return;
            }
            List<ScaledIngredientSpec> preparationSpecs =
                    CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, specs, preparationOutput, repeatCount);
            if (preparationSpecs.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.preparation.none", recipeId.toString(), "-"));
                return;
            }
            if (!tryPrepareIntermediateMaterials(player, recipeId, recipe, modType,
                    preparationSpecs, preparationOutput, forcedOverrides,
                    storageEndpoint, machineSelectionMode, dim, pos, materialLocks)) {
                return;
            }
            return;
        }

        // 预览指纹来自请求位置；自动选择的执行机器不能替换预览的校验上下文。
        var cachedPreview = CraftPlanningApplicationService.findValidatedExecutionPlan(
                player, recipeId, forcedRecipes, repeatCount, targetOutput, modType,
                dim, pos, executionStorageReference, materialLocks, confirmedPreviewRequestId);
        if (cachedPreview.rejected()) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.confirmation_invalid"));
            return;
        }
        if (cachedPreview.entry() != null
                && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                outputDestination, machineSelectionMode, storageEndpoint,
                cachedPreview.entry(), materialLocks, confirmedPreviewRequestId)) {
            return;
        }

        // Smithing is authorized by its bound table above, then dispatched by its
        // own terminal block below (it is deliberately excluded here). Physical
        // machines and logical virtual recipes both need their typed terminal
        // delegate: the latter have no block, but may still depend on recursively
        // crafted inputs (for example Malum void favor consuming a craftable relic).
        // The auto-crafting flag controls intermediate resolution, not whether
        // the terminal must still be represented by a typed graph node.
        if (CraftExecutionPolicy.requiresTypedTerminalExecution(recipe, modType)
                && effectiveDim != null && effectivePos != null
                && modType != ModType.byId("smithing")) {
            if (modType != null) {
                List<IngredientSpec> executionSpecs = specs;
                if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                    executionSpecs = crockPotInputs(recipe, targetOutput, storageEndpoint, player, effectiveDim, effectivePos);
                    if (executionSpecs == null) return;
                }
                // The terminal executes repeatCount times, so its input DAG must
                // cover the complete batch so every terminal run has a planned
                // material allocation.
                ItemStack recipeOutput = arsDynamic
                        ? ArsDynamicApparatusRecipe.validatedOutput(recipe, targetOutput)
                        : goetyDynamic
                                ? GoetyDynamicRitualRecipe.validatedOutput(recipe, targetOutput)
                                : ModRecipeHandlers.tryGetResultItem(
                                        recipe, player.serverLevel().registryAccess());
                recipeOutput = CraftMachineBindingSupport.routeRecipeOutput(modType, recipeOutput);
                // Physical machine recipes used to ignore the preview DAG and
                // always re-enter the bounded server-thread resolver. Reuse
                // the authoritative graph when it is still valid; the final
                // machine node remains typed, so Goety/Ars/etc. still execute
                // through their normal delegates.
                List<ScaledIngredientSpec> graphSpecs =
                        CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                        recipe, executionSpecs, recipeOutput, repeatCount);
                ModRecipeHandler graphHandler = ModRecipeHandlers.claimFor(recipe).winner();
                if (graphHandler != null) {
                    graphSpecs = graphHandler.getRecursiveScaledIngredients(recipe, graphSpecs);
                }
                if (ModIds.ID_SUMMONING_RITUALS.equals(modType.id())) {
                    graphSpecs = SummoningRitualAltarBatchDelegate.planningScaledMaterials(
                            player, recipe, effectiveDim, effectivePos, graphSpecs);
                }
                Map<StackKey, Long> avail = listAvailable(player, storageEndpoint);
                logExecutionAvailability(recipeId, avail);
                // Physical recipes are fully represented by immutable values.
                // Queue dependency planning before the bounded typed resolver.
                boolean physicalPureQueued = false;
                if (!graphSpecs.isEmpty() && !typedGraphOnly && safePreferences.isEmpty()) {
                    physicalPureQueued = CraftPureExecutionPlanner.queuePhysical(
                            player, recipe, recipeId, forcedRecipes, dim, pos, repeatCount,
                            inferMode, baseItem, targetOutput, outputDestination,
                            machineSelectionMode, storageEndpoint, executionStorageReference,
                            graphSpecs, recipeOutput, materialLocks);
                    if (physicalPureQueued) return;
                }
                List<String> missing = new ArrayList<>();
                CraftPlanGraph inputGraph;
                try {
                    inputGraph = null;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        missing.clear();
                        inputGraph = CraftExecutionPolicy.usesPhysicalMachineInputSlots(recipe)
                                ? CraftingResolver.resolveMachineGraphForScaledSpecsWithTypes(
                                        graphSpecs, avail, player.serverLevel(), player,
                                        storageEndpoint, missing, forcedOverrides, false,
                                        typedResolverBudgetMs(),
                                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput),
                                        materialLocks, safePreferences)
                                : CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                                        graphSpecs, avail, player.serverLevel(), player,
                                        storageEndpoint, missing, forcedOverrides, false,
                                        typedResolverBudgetMs(),
                                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput),
                                        materialLocks, safePreferences);
                        if (missing.isEmpty()) break;
                        Map<StackKey, Long> refreshed = refreshMissingAvailability(
                                player, storageEndpoint, missing, avail);
                        if (refreshed == null) break;
                        avail = refreshed;
                    }
                } catch (CraftingPlanningTimeoutException timeout) {
                    if (!graphSpecs.isEmpty() && !typedGraphOnly && safePreferences.isEmpty()
                            && CraftPureExecutionPlanner.queuePhysical(player, recipe, recipeId,
                            forcedRecipes, dim, pos, repeatCount, inferMode, baseItem,
                            targetOutput, outputDestination, machineSelectionMode, storageEndpoint,
                            executionStorageReference, graphSpecs, recipeOutput,
                            materialLocks)) {
                        return;
                    }
                    throw timeout;
                }
                if (!missing.isEmpty()) {
                    logAvailabilityAudit(recipeId, storageEndpoint, missing, avail);
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Craft] physical planning failed recipe={} modType={} missing={} "
                                    + "cache={} cachedPure={} pureQueued={} endpoint={} forced={} dim={} pos={}",
                            recipeId, modType.id(), missing,
                            cachedPreview.entry() != null,
                            cachedPreview.entry() != null && cachedPreview.entry().purePlan() != null,
                            physicalPureQueued, storageEndpoint != null,
                            forcedRecipes.size(),
                            effectiveDim, effectivePos);
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                    return;
                }
                ResolutionStep terminalStep = new ResolutionStep(recipeId, modType, recipeId,
                        List.of(), List.of(), inferMode, repeatCount);
                recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
                try {
                    CraftPlanGraph completeGraph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                            inputGraph, terminalStep, recipeOutput, player.serverLevel());
                    launchGraphAsyncChain(player, completeGraph, storageEndpoint,
                            recipeId, forcedRecipes, dim, pos,
                            targetOutput, outputDestination,
                            machineSelectionMode, materialLocks);
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Craft] terminal graph composition failed recipe={} reason={}",
                            recipeId, exception.getMessage());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.unsupported_machine",
                            recipe.getClass().getSimpleName()));
                }
                return;
            }
        }

        // 普通合成也必须先形成完整终端图，不能直接走单步骤执行链。
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)) {
            // 普通工作台配方在后台规划，成功后按同一份材料轨迹执行。
            if (!typedGraphOnly && materialLocks.isEmpty() && safePreferences.isEmpty()
                    && CraftPureExecutionPlanner.queue(
                            player, craftingRecipe, recipeId, forcedRecipes,
                    dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, storageEndpoint,
                    executionStorageReference)) {
                return;
            }
        }

        // Pre-resolve: if intermediate steps are needed, execute them
        // Pure crafting-table recursion can run from the player's inventory
        // without an RS network. Machine-backed candidates still require a
        // network and are filtered by the resolver as usual.
        if (RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()
                && recipe instanceof CraftingRecipe cr
                && !CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)
                && forcedRecipes.isEmpty() && materialLocks.isEmpty()
                && safePreferences.isEmpty()) {
            ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                    cr, player.serverLevel().registryAccess());
            List<ScaledIngredientSpec> graphSpecs =
                    CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, CraftIngredientPlanMath.extractPlanIngredientSpecs(cr), recipeOutput, repeatCount);
            Map<StackKey, Long> available = listAvailable(player, storageEndpoint);
            List<String> graphMissing = new ArrayList<>();
            CraftPlanGraph inputGraph;
            try {
                inputGraph = CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                        graphSpecs, available, player.serverLevel(), player, storageEndpoint,
                        graphMissing, null, false, typedResolverBudgetMs(),
                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput),
                        materialLocks, safePreferences);
            } catch (CraftingPlanningTimeoutException timeout) {
                if (!typedGraphOnly && safePreferences.isEmpty()
                        && CraftPureExecutionPlanner.queue(
                                player, cr, recipeId, forcedRecipes, dim, pos,
                        repeatCount, inferMode, baseItem, targetOutput, outputDestination,
                        machineSelectionMode, storageEndpoint,
                        executionStorageReference)) {
                    return;
                }
                throw timeout;
            }
            List<ResolutionStep> allSteps = ExecutionEquivalence.projectFlatSteps(inputGraph);
            if (CraftTerminalSteps.canComposeEquivalentTerminalGraph(
                    inputGraph, allSteps, graphMissing)) {
                boolean physicalSteps = allSteps.stream().anyMatch(
                        step -> step.modType() != ModType.GENERIC);
                if (physicalSteps && storageEndpoint == null) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.network_unavailable"));
                    return;
                }
                ResolutionStep terminalStep = CraftTerminalSteps.genericTerminalStep(
                        recipeId, repeatCount);
                recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(
                        recipe, recipeOutput, targetOutput);
                try {
                    CraftPlanGraph completeGraph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                            inputGraph, terminalStep, recipeOutput,
                            player.serverLevel());
                    launchGraphAsyncChain(player, completeGraph, storageEndpoint,
                            recipeId, forcedRecipes, dim, pos,
                            targetOutput, outputDestination,
                            machineSelectionMode, materialLocks);
                    return;
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Craft] pre-resolved terminal graph composition failed recipe={} reason={}",
                            recipeId, exception.getMessage());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.unsupported_machine",
                            recipe.getClass().getSimpleName()));
                    return;
                }
            }
        }

        // Unified resolution pass: use the same resolver as tryBuildPlan so the
        // execute path sees the same recipe chain the preview showed. Only for
        // CraftingRecipe (the typed resolver works with shaped/shapeless ingredients).
        if (recipe instanceof CraftingRecipe cr2
                && !CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)
                && RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            Map<StackKey, Long> avail = listAvailable(player, storageEndpoint);
            logExecutionAvailability(recipeId, avail);
            List<String> missingCheck = new ArrayList<>();
            ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                    cr2, player.serverLevel().registryAccess());
            List<ScaledIngredientSpec> scaledSpecs =
                    CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, CraftPacketUtils.extractIngredientSpecs(cr2), recipeOutput, repeatCount);
            CraftPlanGraph inputGraph;
            try {
                inputGraph = CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                        scaledSpecs, avail, player.serverLevel(),
                        player, storageEndpoint, missingCheck, forcedOverrides, false,
                        typedResolverBudgetMs(),
                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput),
                        materialLocks, safePreferences);
            } catch (CraftingPlanningTimeoutException timeout) {
                if (!typedGraphOnly && materialLocks.isEmpty()
                        && safePreferences.isEmpty() && CraftPureExecutionPlanner.queue(
                        player, cr2, recipeId, forcedRecipes, dim, pos,
                        repeatCount, inferMode, baseItem, targetOutput, outputDestination,
                        machineSelectionMode, storageEndpoint,
                        executionStorageReference)) {
                    return;
                }
                throw timeout;
            }
            List<ResolutionStep> planSteps = ExecutionEquivalence.projectFlatSteps(inputGraph);
            if (CraftTerminalSteps.canComposeEquivalentTerminalGraph(
                    inputGraph, planSteps, missingCheck)) {
                boolean physicalSteps = planSteps.stream().anyMatch(
                        step -> step.modType() != ModType.GENERIC);
                ResolutionStep terminalStep = CraftTerminalSteps.genericTerminalStep(recipeId, repeatCount);
                if (physicalSteps && storageEndpoint == null) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.network_unavailable"));
                    return;
                }
                recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(
                        recipe, recipeOutput, targetOutput);
                try {
                    CraftPlanGraph completeGraph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                            inputGraph, terminalStep, recipeOutput,
                            player.serverLevel());
                    launchGraphAsyncChain(player, completeGraph, storageEndpoint,
                            recipeId, forcedRecipes, dim, pos,
                            targetOutput, outputDestination,
                            machineSelectionMode, materialLocks);
                    return;
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Craft] crafting terminal graph composition failed recipe={} reason={}",
                            recipeId, exception.getMessage());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.unsupported_machine",
                            recipe.getClass().getSimpleName()));
                    return;
                }
            }
            // planSteps=0 with missing=[] means everything is directly available; not a failure
            if (!missingCheck.isEmpty()) {
                if (FAILURE_LOG_SAMPLER.allow("resolve:" + recipeId)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Generic] Unified resolver failed for {}: planSteps={} missing={}",
                            recipeId, planSteps != null ? planSteps.size() : "null", missingCheck);
                }
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missingCheck)));
                return;
            }

            RSIntegrationMod.debug("[RSI-Generic] Unified resolver: nothing to resolve for {}", recipeId);
        }

        // Guard: physical machine recipes REQUIRE a bound machine before any
        // terminal graph is built. Executing them without the machine would
        // consume items without actually running the machine crafting.
        if (CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, modType)
                && (effectiveDim == null || effectivePos == null)) {
            CraftMachineBindingSupport.logBindingRejection("execute-context", player, recipe, modType,
                    effectiveDim, effectivePos);
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.no_bound_machine", modType.id()));
            return;
        }

        // 顶层解析失败但绑定回退已成功时，补选端点供 ledger 统一提交。
        if (storageEndpoint == null && storageReference == null) {
            storageEndpoint = CraftPlanStorageService.resolveEndpoint(
                    player, player.serverLevel().dimension().location(),
                    player.blockPosition(), modType);
        }

        // Resolve intermediate crafting steps for smithing recipes.
        // Smithing skips the CraftingRecipe-only paths above, so intermediates
        // (e.g. diamond from diamond block) would not be crafted otherwise.
        // 没有存储端点时也必须继续：绑定锻造台后直接从背包锻造是合法路径，
        // 只有递归出现实体机器步骤时才需要网络（下方按需报 network_unavailable）。
        if (modType == ModType.byId("smithing")
                && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
            ItemStack smithingOutput = ModRecipeHandlers.tryGetResultItem(
                    recipe, player.serverLevel().registryAccess());
            List<ScaledIngredientSpec> smithingSpecs =
                    CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                    recipe, specs, smithingOutput, repeatCount);
            if (!smithingSpecs.isEmpty()) {
                Map<StackKey, Long> avail = listAvailable(player, storageEndpoint);
                DirectReservationCheck directSmithing = probeDirectTerminalReservation(
                        player, recipeId, smithingSpecs, avail, storageEndpoint);
                // 锻造输入同样是不可变的规划数据：材料不齐时先让后台路线排依赖，
                // 避免占用服务端线程的兼容解析预算。
                // 材料已经齐全（directSmithing 可行）时不能在此跳出：终端图仍然必须构造，
                // 否则锻造配方会掉到末尾的“不支持的机器类型”兜底上。
                // 后台物理路线需要存储端点，无端点时直接走前台解析。
                if (!directSmithing.available() && storageEndpoint != null
                        && !typedGraphOnly && safePreferences.isEmpty()
                        && CraftPureExecutionPlanner.queuePhysical(
                        player, recipe, recipeId, forcedRecipes, dim, pos, repeatCount,
                        inferMode, baseItem, targetOutput, outputDestination,
                        machineSelectionMode, storageEndpoint,
                        executionStorageReference, smithingSpecs, smithingOutput,
                        materialLocks)) {
                    return;
                }
                List<String> missing = new ArrayList<>();
                CraftPlanGraph smithingInputGraph;
                try {
                    smithingInputGraph = CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                    smithingSpecs, avail, player.serverLevel(), player, storageEndpoint, missing,
                            forcedOverrides, false, typedResolverBudgetMs(), null,
                            materialLocks, safePreferences);
                } catch (CraftingPlanningTimeoutException timeout) {
                    if (storageEndpoint != null && !typedGraphOnly && safePreferences.isEmpty()
                            && CraftPureExecutionPlanner.queuePhysical(
                            player, recipe, recipeId, forcedRecipes, dim, pos, repeatCount,
                            inferMode, baseItem, targetOutput, outputDestination,
                            machineSelectionMode, storageEndpoint,
                            executionStorageReference, smithingSpecs, smithingOutput,
                            materialLocks)) {
                        return;
                    }
                    throw timeout;
                }
                List<ResolutionStep> interSteps =
                        ExecutionEquivalence.projectFlatSteps(smithingInputGraph);
                // 材料全齐时输入图只有根需求、没有中间节点，零步骤图同样可以追加锻造终端节点。
                if (CraftTerminalSteps.canComposeEquivalentTerminalGraph(
                        smithingInputGraph, interSteps, missing)) {
                    // 递归里出现实体机器步骤时必须有网络端点，普通合成步骤可以直接用背包。
                    boolean physicalSteps = interSteps.stream().anyMatch(
                            step -> step.modType() != ModType.GENERIC);
                    if (physicalSteps && storageEndpoint == null) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.network_unavailable"));
                        return;
                    }
                    ResolutionStep terminalStep = CraftTerminalSteps.smithingTerminalStep(recipeId, repeatCount);
                    ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                            recipe, player.serverLevel().registryAccess());
                    recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(
                            recipe, recipeOutput, targetOutput);
                    try {
                        CraftPlanGraph completeGraph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                                smithingInputGraph, terminalStep, recipeOutput,
                                player.serverLevel());
                        launchGraphAsyncChain(player, completeGraph, storageEndpoint,
                                recipeId, forcedRecipes, dim, pos,
                                targetOutput, outputDestination,
                                machineSelectionMode, materialLocks);
                        return;
                    } catch (IllegalArgumentException | ArithmeticException exception) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-Craft] smithing terminal graph composition failed recipe={} reason={}",
                                recipeId, exception.getMessage());
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.unsupported_machine",
                                recipe.getClass().getSimpleName()));
                        return;
                    }
                }
                if (!missing.isEmpty()) {
                    // 锻造缺料也要报缺料，不能落到“不支持的机器类型”误导玩家。
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Craft] smithing planning failed recipe={} missing={}",
                            recipeId, missing);
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials",
                            CraftPacketUtils.formatMissingSummary(missing)));
                    return;
                }
            }
        }

        RSIntegrationMod.LOGGER.warn(
                "[RSI-Craft] no complete graph could be resolved recipe={} modType={} repeatCount={}",
                recipeId, modType == null ? "unknown" : modType.id(), repeatCount);
        player.sendSystemMessage(Component.translatable(
                "rsi.generic.error.unsupported_machine",
                recipe.getClass().getSimpleName()));
    }

    private static void logExecutionAvailability(ResourceLocation recipeId,
                                                  Map<StackKey, Long> available) {
        if (!RSIntegrationMod.LOGGER.isDebugEnabled()) return;
        int tea = 0;
        int oak = 0;
        for (var entry : available.entrySet()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (id == null) continue;
            if (id.toString().equals("immortalers_delight:leisamboo_tea")) tea += entry.getValue();
            if (id.toString().equals("minecraft:oak_planks")) oak += entry.getValue();
        }
        RSIntegrationMod.LOGGER.debug("[RSI-ExecAvail] recipe={} keys={} tea={} oak_planks={}",
                recipeId, available.size(), tea, oak);
    }

    private static void logAvailabilityAudit(
            ResourceLocation recipeId,
            @Nullable CraftStorageEndpoint endpoint,
            List<String> missing,
            Map<StackKey, Long> available) {
        Set<String> missingIds = new HashSet<>(missing);
        List<String> matching = new ArrayList<>();
        for (Map.Entry<StackKey, Long> entry : available.entrySet()) {
            String descriptionId = entry.getKey().item().getDescriptionId();
            if (!missingIds.contains(descriptionId)) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            matching.add(itemId + " x" + entry.getValue()
                    + (entry.getKey().tag() == null ? "" : " tag=" + entry.getKey().tag()));
        }
        String reference = "none";
        if (endpoint != null) {
            try {
                reference = endpoint.session().reference().toString();
            } catch (RuntimeException exception) {
                reference = "unavailable:" + exception.getClass().getSimpleName();
            }
        }
        RSIntegrationMod.LOGGER.warn(
                "[RSI-ExecAvailAudit] recipe={} endpoint={} missing={} matchingKeys={}",
                recipeId, reference, missing,
                matching.isEmpty() ? "<none>" : matching);
    }

    @Nullable
    static Map<StackKey, Long> refreshMissingAvailability(
            ServerPlayer player,
            @Nullable CraftStorageEndpoint endpoint,
            List<String> missing,
            Map<StackKey, Long> available) {
        if (endpoint == null || missing.isEmpty()) return null;
        Set<Item> itemTypes = new HashSet<>();
        Map<String, Set<Item>> index = itemTypesByDescriptionId();
        for (String descriptionId : missing) {
            itemTypes.addAll(index.getOrDefault(descriptionId, Set.of()));
        }
        if (itemTypes.isEmpty()) return null;
        Map<StackKey, Long> updates = new HashMap<>();
        for (Map.Entry<StackKey, Long> entry : MaterialSources.listAvailableForTypes(
                player, endpoint, itemTypes).entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            Long previous = available.get(entry.getKey());
            if (previous == null || !previous.equals(entry.getValue())) {
                updates.put(entry.getKey(), entry.getValue());
            }
        }
        if (updates.isEmpty()) return null;
        Map<StackKey, Long> refreshed = new HashMap<>(available);
        refreshed.putAll(updates);
        RSIntegrationMod.LOGGER.info(
                "[RSI-ExecAvail] refreshed missing types through narrow snapshot recipeTypes={}",
                itemTypes.stream()
                        .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                        .sorted()
                        .toList());
        return Map.copyOf(refreshed);
    }

    private static Map<String, Set<Item>> itemTypesByDescriptionId() {
        Map<String, Set<Item>> cached = itemTypesByDescriptionIdCache;
        if (cached != null) return cached;
        synchronized (CraftExecutionRequestService.class) {
            if (itemTypesByDescriptionIdCache != null) return itemTypesByDescriptionIdCache;
            // 物品注册表在游戏启动后不再变化，缺料字符串到物品类型的索引只需构建一次。
            Map<String, Set<Item>> built = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                built.computeIfAbsent(item.getDescriptionId(), ignored -> new HashSet<>())
                        .add(item);
            }
            built.replaceAll((ignored, items) -> Set.copyOf(items));
            itemTypesByDescriptionIdCache = Map.copyOf(built);
            return itemTypesByDescriptionIdCache;
        }
    }

    private static final class IngredientNeed {
        final Ingredient ingredient;
        int count;
        IngredientNeed(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = count;
        }
    }

}
