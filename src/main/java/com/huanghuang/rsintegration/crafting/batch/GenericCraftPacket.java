package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;

import java.lang.reflect.Field;

import com.huanghuang.rsintegration.feature.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraphCompactor;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.recipe.viewer.RecipeIdNormalizer;
import com.huanghuang.rsintegration.recipe.viewer.LongTravailCrownRecipes;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.MaterialVariantPreferences;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.mods.farmersdelight.CosmopolitanTisaneRecipeResolver;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import io.netty.handler.codec.DecoderException;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;


import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.RSGridMatrixNormalizer;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.network.machine.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePublisher;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.crafting.plan.MaxCraftableSearch;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.BirdcageEggCatalog;
import com.huanghuang.rsintegration.mods.biomancy.BiomancyDigestingRecipeResolver;
import com.huanghuang.rsintegration.mods.aether.AetherFurnaceBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.mods.immortalersdelight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EmbersPlanInfo;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingCatalog;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsDynamicApparatusRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulCraftingTask;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualHelper;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.ChainRepeatController;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.PreviewRateLimiter;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.ForcedRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector.RecipeGraphUnavailableException;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.planning.PlanRequestService;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator.StalePlanningResultException;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.mods.goety.GoetyRecipeHandler;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRRecipeHandler;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class GenericCraftPacket {
    @Nullable
    private static volatile Map<String, Set<Item>> itemTypesByDescriptionIdCache;

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

    private static Map<IngredientKey, PlanResponse.Availability> directMaterialView(
            DirectMaterialAllocator.Result allocation, Map<StackKey, Long> available) {
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
                new PlanResponse.Availability(count, stored.getOrDefault(key, 0L))));
        return result;
    }

    private static final int MAX_GOETY_CANDIDATE_WARNINGS = 32;
    private static final LogSampler FAILURE_LOG_SAMPLER = new LogSampler(2_000);
    private ResourceLocation recipeId;
    private final boolean preview;
    /** itemRegKey → forced recipeId (only for preview mode, empty when unused) */
    private final Map<String, String> forcedRecipes;
    private Map<String, ItemStack> materialLocks = Map.of();
    private PlanningPreferences planningPreferences = PlanningPreferences.empty();
    private final ResourceLocation dim;
    private final BlockPos pos;
    private final int repeatCount;
    private final boolean inferMode;
    /** JEI-provided base item for FA ApplyModifierRecipe prefill */
    private final ItemStack baseItem;
    /**
     * JEI-provided concrete output (ghost slot) the player clicked. Lets the
     * server distinguish NBT-variant outputs that share one recipe id — e.g. WR
     * arcane iterator "Curse II" vs "Curse I" both resolve to the same recipe.
     * Null when the client didn't supply it (backward compatible).
     */
    private final ItemStack targetOutput;
    /** Client-generated correlation id for preview responses; zero is legacy. */
    private long requestId;
    private OutputDestination outputDestination = OutputDestination.RS_NETWORK;
    /** Explicit backend-qualified target selected in the plan UI; null means server default. */
    @Nullable
    private StorageReference storageReference;
    private boolean maximize;
    /** Execute only independently craftable intermediate materials. */
    private boolean partialPreparation;
    private MachineSelectionMode machineSelectionMode = MachineSelectionMode.AUTO;

    /** Preview mode: compute plan and send GUI to client. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview) {
        this(recipeId, preview, Collections.emptyMap(), null, null, 1);
    }

    /** Preview mode with forced recipe overrides (for OR-path selection). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes) {
        this(recipeId, preview, forcedRecipes, null, null, 1);
    }

    /** Convenience: without explicit machine binding. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos) {
        this(recipeId, preview, forcedRecipes, dim, pos, 1);
    }

    /** Convenience: with repeat count, no infer mode. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, false);
    }

    /** Convenience: with infer mode, no base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, null);
    }

    /** All parameters, including JEI base item for FA smithing prefill. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, null);
    }

    /** Master constructor: adds the JEI ghost-output target for NBT-variant recipes. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this.recipeId = recipeId;
        this.preview = preview;
        this.forcedRecipes = forcedRecipes != null ? forcedRecipes : Collections.emptyMap();
        this.dim = dim;
        this.pos = pos;
        this.repeatCount = Math.max(1, Math.min(repeatCount, RSIntegrationConfig.REPEAT_COUNT_MAX.get()));
        this.inferMode = inferMode;
        this.baseItem = baseItem != null ? baseItem.copy() : null;
        this.targetOutput = targetOutput != null && !targetOutput.isEmpty() ? targetOutput.copy() : null;
        this.requestId = 0L;
        this.storageReference = null;
    }

    /** Full constructor with an explicit preview correlation id. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, targetOutput);
        if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("requestId out of range");
        }
        // Constructors delegate through the legacy master constructor; assign
        // the bounded id only after all common field normalization is complete.
        this.requestId = requestId;
    }

    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId, OutputDestination outputDestination) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId);
        this.outputDestination = outputDestination == null
                ? OutputDestination.RS_NETWORK : outputDestination;
    }

    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId, OutputDestination outputDestination,
                              @Nullable StorageReference storageReference) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId, outputDestination);
        this.storageReference = storageReference;
    }

    public static GenericCraftPacket maxPreview(ResourceLocation recipeId,
                                                 Map<String, String> forcedRecipes,
                                                 @Nullable ResourceLocation dim,
                                                 @Nullable BlockPos pos,
                                                 @Nullable ItemStack baseItem,
                                                 @Nullable ItemStack targetOutput,
                                                 long requestId) {
        GenericCraftPacket packet = new GenericCraftPacket(recipeId, true, forcedRecipes,
                dim, pos, 1, false, baseItem, targetOutput, requestId,
                OutputDestination.RS_NETWORK);
        packet.maximize = true;
        return packet;
    }

    /** Convenience: execute mode. */
    public GenericCraftPacket(ResourceLocation recipeId) {
        this(recipeId, false, Collections.emptyMap(), null, null, 1);
    }

    /** JEI-initiated preview for a mod recipe with known machine location. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, 1);
    }

    /** JEI-initiated preview with repeat, inferMode, and specific base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem);
    }

    /** JEI-initiated preview carrying the clicked ghost output (NBT-variant target). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem, targetOutput);
    }

    public void encode(FriendlyByteBuf buf) {
        GenericCraftPacketCodec.write(buf, wireData());
    }

    public static GenericCraftPacket decode(FriendlyByteBuf buf) {
        return fromWireData(GenericCraftPacketCodec.read(buf));
    }

    private GenericCraftPacketCodec.WireData wireData() {
        return new GenericCraftPacketCodec.WireData(
                recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId, outputDestination, storageReference,
                maximize, machineSelectionMode, materialLocks, partialPreparation,
                planningPreferences);
    }

    private static GenericCraftPacket fromWireData(GenericCraftPacketCodec.WireData data) {
        GenericCraftPacket packet = new GenericCraftPacket(
                data.recipeId(), data.preview(), data.forcedRecipes(), data.dimension(),
                data.position(), data.repeatCount(), data.inferMode(), data.baseItem(),
                data.targetOutput(), data.requestId(), data.outputDestination(),
                data.storageReference());
        packet.maximize = data.maximize();
        packet.machineSelectionMode = data.machineSelectionMode();
        packet.materialLocks = data.materialLocks();
        packet.planningPreferences = data.planningPreferences();
        packet.partialPreparation = data.partialPreparation();
        return packet;
    }

    public GenericCraftPacket withMachineSelectionMode(MachineSelectionMode mode) {
        machineSelectionMode = mode == null ? MachineSelectionMode.AUTO : mode;
        return this;
    }

    public GenericCraftPacket withMaterialLocks(Map<String, ItemStack> locks) {
        materialLocks = MaterialLocks.immutableCopy(locks);
        return this;
    }

    public GenericCraftPacket withPlanningPreferences(@Nullable PlanningPreferences preferences) {
        planningPreferences = preferences == null
                ? PlanningPreferences.empty() : preferences;
        return this;
    }

    /** Explicitly opt this execution into intermediate-material preparation. */
    public GenericCraftPacket withPartialPreparation(boolean enabled) {
        partialPreparation = enabled;
        return this;
    }

    boolean isPartialPreparation() {
        return partialPreparation;
    }

    boolean isPreviewRequest() {
        return preview;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public GenericCraftPacket withRecipeId(ResourceLocation value) {
        recipeId = Objects.requireNonNull(value, "recipeId");
        return this;
    }

    Map<String, ItemStack> materialLocks() {
        return materialLocks;
    }

    Map<String, String> forcedRecipes() {
        return forcedRecipes;
    }

    PlanningPreferences planningPreferences() {
        return planningPreferences;
    }

    public GenericCraftPacket withStorageReference(@Nullable StorageReference reference) {
        this.storageReference = reference;
        return this;
    }

    @Nullable
    public StorageReference storageReference() {
        return storageReference;
    }

    boolean isMaximizeRequest() {
        return maximize;
    }

    MachineSelectionMode machineSelectionMode() {
        return machineSelectionMode;
    }

    OutputDestination outputDestination() {
        return outputDestination;
    }

    public static void handle(GenericCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        handle(packet, contextSupplier, packet.partialPreparation);
    }

    static void handlePreparation(GenericCraftPacket packet,
                                  Supplier<NetworkEvent.Context> contextSupplier) {
        handle(packet, contextSupplier, true);
    }

    private static void handle(GenericCraftPacket packet,
                               Supplier<NetworkEvent.Context> contextSupplier,
                               boolean preparationRequest) {
        RSIntegrationMod.debug("[RSI-Generic] handle() ENTRY: recipeId={} preview={} dim={} pos={} repeat={} storage={}",
                packet.recipeId, packet.preview, packet.dim, packet.pos, packet.repeatCount,
                packet.storageReference == null ? "default" : packet.storageReference);
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: player is null, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof FakePlayer) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: FakePlayer, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        // Supplementary: catch fake players that extend ServerPlayer directly
        // without implementing FakePlayer (some mods do this).
        if (player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: not in player list (fake?), recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (packet.preview && PreviewRateLimiter.isRateLimited(player.getUUID())) {
            RSIntegrationMod.debug("[RSI-Generic] handle() DROP: rate-limited, recipeId={} player={}",
                    packet.recipeId, player.getGameProfile().getName());
            Component message = Component.translatable("rsi.plan.failure.request_pending");
            player.sendSystemMessage(message);
            context.enqueueWork(() -> PlanningProgressServer.failDirect(
                    player, packet.requestId, packet.recipeId, message));
            context.setPacketHandled(true);
            return;
        }
        final long previewGeneration = packet.preview
                ? CraftPlanningRuntime.planRequests().begin(player.getUUID()) : 0L;
        if (packet.preview) {
            PlanningProgressServer.begin(player.getUUID(), packet.requestId,
                    previewGeneration, packet.recipeId);
        }
        RSIntegrationMod.debug("[RSI-Generic] handle() enqueueWork: recipeId={} preview={}",
                packet.recipeId, packet.preview);
        context.enqueueWork(() -> {
            Consumer<ServerPlayer> action = readyPlayer ->
                    executeRequest(readyPlayer, packet, previewGeneration, preparationRequest);
            if (warmUpReady(player.serverLevel())) {
                action.accept(player);
                return;
            }
            if (RecipeIndex.generationBuildFailed()) {
                Component message = Component.translatable("rsi.plan.failure.catalog_unavailable");
                player.sendSystemMessage(message);
                PlanningProgressServer.fail(player, previewGeneration, message);
                return;
            }
            boolean queued = CraftRequestQueues.offerWarmUp(
                    new DeferredCraftRequestQueue.Entry<>(
                    player.getUUID(), packet.preview, previewGeneration, action));
            if (!queued) {
                Component message = Component.translatable("rsi.plan.failure.planner_busy");
                player.sendSystemMessage(message);
                PlanningProgressServer.fail(player, previewGeneration, message);
                return;
            }
            if (packet.preview) {
                PlanningProgressServer.warmingUp(player.getUUID(), previewGeneration);
            } else {
                player.sendSystemMessage(Component.translatable("rsi.plan.failure.catalog_loading"));
            }
            RSIntegrationMod.debug(
                    "[RSI-Generic] deferred until recipe warm-up: recipeId={} preview={} queued={}",
                    packet.recipeId, packet.preview, CraftRequestQueues.warmUpSize());
        });
        context.setPacketHandled(true);
    }

    private static boolean warmUpReady(ServerLevel level) {
        return RecipeIndex.isReady(level);
    }

    private static void executeRequest(ServerPlayer player, GenericCraftPacket packet,
                                       long previewGeneration, boolean preparationRequest) {
        try {
            if (packet.preview) {
                if (!CraftPlanningRuntime.planRequests().isCurrent(player.getUUID(), previewGeneration)) return;
                PlanningProgressServer.running(player.getUUID(), previewGeneration,
                        PlanningProgressSnapshot.Phase.PREPARING);
                RSIntegrationMod.debug(
                        "[RSI-Generic] handle() -> tryBuildPlan: recipeId={}", packet.recipeId);
                if (packet.maximize) {
                    CraftMaxCraftableSearchService.start(
                            player, new CraftMaxCraftableSearchService.Request(
                                    packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                                    packet.baseItem, packet.targetOutput, packet.requestId,
                                    packet.storageReference, packet.materialLocks),
                            previewGeneration);
                } else {
                    tryBuildPlan(player, packet.recipeId, packet.forcedRecipes,
                            packet.dim, packet.pos, packet.repeatCount, packet.baseItem,
                            packet.targetOutput, packet.requestId, previewGeneration,
                            packet.storageReference, packet.materialLocks,
                            packet.planningPreferences);
                }
            } else {
                RSIntegrationMod.debug(
                        "[RSI-Generic] handle() -> queue execution: recipeId={} forced={} destination={}",
                        packet.recipeId, packet.forcedRecipes.size(), packet.outputDestination);
                queueExecutionRequest(player, packet, preparationRequest);
            }
        } catch (Throwable e) {
            RSIntegrationMod.LOGGER.error("[RSI-Generic] Failed for {}:", packet.recipeId, e);
            try {
                Component message = CraftFailureMessages.build(e, packet.recipeId);
                player.sendSystemMessage(message);
                if (packet.preview) PlanningProgressServer.fail(player, previewGeneration, message);
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Failed to send error message to player", ex);
            }
        }
    }

    private static void queueExecutionRequest(ServerPlayer player, GenericCraftPacket packet,
                                              boolean preparationRequest) {
        queueExecutionRequest(player,
                readyPlayer -> executeQueuedRequest(
                        readyPlayer, packet, preparationRequest), packet.recipeId);
    }

    private static void queueExecutionRequest(ServerPlayer player,
                                               Consumer<ServerPlayer> action,
                                               ResourceLocation recipeId) {
        Consumer<ServerPlayer> guardedAction = readyPlayer -> {
            try {
                action.accept(readyPlayer);
            } catch (CraftingPlanningTimeoutException timeout) {
                PerformanceMonitor.recordResolveTimeout();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-exec] deferred server-thread resolver timeout recipe={}", recipeId);
                readyPlayer.sendSystemMessage(Component.translatable(
                        "rsi.plan.failure.dynamic_plan_unavailable"));
            } catch (Throwable failure) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Deferred execution failed for {}:", recipeId, failure);
                readyPlayer.sendSystemMessage(CraftFailureMessages.build(failure, recipeId));
            }
        };
        boolean queued = CraftRequestQueues.offerExecution(
                new DeferredCraftRequestQueue.Entry<>(
                player.getUUID(), false, 0L,
                guardedAction));
        if (!queued) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planner_busy"));
            return;
        }
        RSIntegrationMod.debug("[RSI-Generic] execution queued: recipeId={} queueSize={}",
                recipeId, CraftRequestQueues.executionSize());
    }

    private static void executeQueuedRequest(ServerPlayer player, GenericCraftPacket packet,
                                             boolean preparationRequest) {
        try {
            if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                    && !RSGridMatrixNormalizer.returnToSelectedNetwork(
                            player, packet.storageReference)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.matrix_return_failed"));
                return;
            }
            RSIntegrationMod.debug(
                    "[RSI-Generic] queued execution -> tryResolve: recipeId={} forced={} destination={}",
                    packet.recipeId, packet.forcedRecipes.size(), packet.outputDestination);
            tryResolve(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                    packet.repeatCount, packet.inferMode, packet.baseItem, packet.targetOutput,
                    packet.outputDestination, packet.machineSelectionMode,
                    packet.storageReference, packet.materialLocks,
                    false, preparationRequest, packet.planningPreferences);
        } catch (CraftingPlanningTimeoutException timeout) {
            PerformanceMonitor.recordResolveTimeout();
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-exec] queued server-thread resolver timeout recipe={}", packet.recipeId);
            player.sendSystemMessage(Component.translatable(
                    "rsi.plan.failure.dynamic_plan_unavailable"));
        } catch (Throwable e) {
            RSIntegrationMod.LOGGER.error("[RSI-Generic] Queued execution failed for {}:",
                    packet.recipeId, e);
            try {
                player.sendSystemMessage(CraftFailureMessages.build(e, packet.recipeId));
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Failed to send queued execution error", ex);
            }
        }
    }

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

    private static void recordMissingMaterial(
            List<String> missing,
            ImmutableRecipeGraph.IngredientRef unresolved) {
        if (unresolved.alternatives().isEmpty()) return;
        ImmutableRecipeGraph.MaterialRef material = unresolved.alternatives().get(0);
        missing.add(CraftPacketUtils.missingMaterialKey(material.itemId()));
    }

    record BindingAwareGraph(ImmutableRecipeGraph graph,
                             Set<ResourceLocation> blockedOutputIds) {}

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

    /**
     * Apply non-machine runtime requirements before binding filtering. Outputs
     * rejected by a catalyst or other handler-specific prerequisite are not
     * machine-blocked and must not trigger the no-bound-machine fallback.
     */
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
                    // 首次删除时才复制已通过的前缀，保留原有候选顺序。
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
                // 只有通过运行条件校验的配方才参与机器未绑定的诊断。
                if (hasRuntimeCandidate) blockedOutputs.add(entry.getKey().itemId());
            }
        }
        if (filtered == null) return new BindingAwareGraph(graph, Set.of());
        // 与原过滤行为一致：生成派生图时不保留原图中的空桶。
        filtered.values().removeIf(List::isEmpty);
        return new BindingAwareGraph(new ImmutableRecipeGraph(filtered),
                Set.copyOf(blockedOutputs));
    }

    static BindingAwareGraph bindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph) {
        return bindingAwareGraph(player, graph, false);
    }

    /** Consumer 检查要求中间机器至少存在一台当前未租用的候选。 */
    static BindingAwareGraph consumerBindingAwareGraph(
            ServerPlayer player,
            ImmutableRecipeGraph graph
    ) {
        return bindingAwareGraph(player, graph, true);
    }

    private static BindingAwareGraph bindingAwareGraph(
            ServerPlayer player,
            ImmutableRecipeGraph graph,
            boolean requireIdleMachine
    ) {
        Map<ResourceLocation, Optional<Recipe<?>>> recipes = new HashMap<>();
        Function<ResourceLocation, Recipe<?>> resolve = recipeId ->
                recipes.computeIfAbsent(recipeId,
                        id -> Optional.ofNullable(CraftRecipeResolver.resolve(player.serverLevel(), id)))
                        .orElse(null);
        return filterRecipeGraph(graph, node -> {
            Recipe<?> recipe = resolve.apply(node.recipeId());
            if (recipe == null) return true;
            return CraftMachineBindingSupport.isRecipeAvailableForPlanning(player, recipe);
        }, node -> {
            ModType type = ModType.byId(node.modTypeId());
            if (!CraftExecutionPolicy.requiresBoundMachine(type)) return true;
            Recipe<?> recipe = resolve.apply(node.recipeId());
            if (recipe == null || !CraftExecutionPolicy.requiresBoundMachine(recipe, type)) return true;
            // Keep vanilla smithing nodes in the planning graph so recursive
            // previews can show the complete upgrade chain. Execution still
            // requires a bound smithing table at the terminal entry point.
            if (CraftExecutionPolicy.isSmithingRecipe(recipe)) return true;
            return requireIdleMachine
                    ? CraftMachineBindingSupport.hasIdleBindingForExecutionType(
                    player, recipe, type)
                    : CraftMachineBindingSupport.hasBindingForExecutionType(
                    player, recipe, type);
        });
    }

    static BindingAwareGraph scopedBindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph, ResourceLocation targetRecipeId) {
        BindingAwareGraph filtered = bindingAwareGraph(player,
                ImmutableRecipeGraphProjector.restrictToDependencies(graph, targetRecipeId));
        if (!filtered.graph().recipesById().containsKey(targetRecipeId)) {
            // 第三方机器终端可能无法参与依赖裁剪，但完整绑定图仍保留其节点。
            // 纯规划器会从该终端重新限定依赖范围，因此这里无需整图参与搜索。
            filtered = bindingAwareGraph(player, graph);
        }
        Map<ResourceLocation, List<ImmutableRecipeGraph.IngredientRef>> replacements =
                new HashMap<>();
        for (ImmutableRecipeGraph.RecipeNode node : filtered.graph().recipesById().values()) {
            if (!ModIds.ID_EMBERS_STAMPER.equals(node.modTypeId())) continue;
            Recipe<?> recipe = CraftRecipeResolver.resolve(player.serverLevel(), node.recipeId());
            if (recipe == null) continue;
            List<IngredientSpec> declared =
                    CraftPacketUtils.extractSingleOperationRecursiveIngredientSpecs(recipe);
            if (declared == null || declared.isEmpty()) continue;
            List<IngredientSpec> contextual = CraftPacketUtils.applyPlanningIngredientContext(
                    recipe, player, declared);
            if (contextual == null || contextual.equals(declared)) continue;
            List<ImmutableRecipeGraph.IngredientRef> inputs = new ArrayList<>();
            boolean projectable = true;
            for (IngredientSpec spec : contextual) {
                if (spec.isEmpty()) continue;
                ImmutableRecipeGraph.IngredientRef input =
                        ImmutableRecipeGraphProjector.projectIngredient(spec);
                if (input == null) {
                    projectable = false;
                    break;
                }
                inputs.add(input);
            }
            if (projectable) replacements.put(node.recipeId(), List.copyOf(inputs));
        }
        return replacements.isEmpty() ? filtered : new BindingAwareGraph(
                ImmutableRecipeGraphProjector.withRecipeInputs(filtered.graph(), replacements),
                filtered.blockedOutputIds());
    }

    @Nullable
    private static PlanCache.Entry findValidatedExecutionPlan(
            ServerPlayer player, ResourceLocation recipeId, Map<String, String> forcedRecipes,
            int repeatCount, @Nullable ItemStack targetOutput, @Nullable ModType modType,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks) {
        PlanCache.Key exactKey = CraftPlanCacheKeys.key(player.getUUID(), recipeId,
                forcedRecipes, repeatCount, targetOutput, modType, materialLocks);
        PlanCache cache = CraftPlanningRuntime.planCache();
        PlanCache.Entry entry = cache.peekForExecution(exactKey);
        boolean semanticLease = false;
        if (entry == null && materialLocks.isEmpty()) {
            PlanCache.Entry latest = cache.peekLatestForExecution(player.getUUID());
            if (latest != null && matchesExecutionLease(
                    latest, player, recipeId, forcedRecipes, repeatCount,
                    targetOutput, modType)) {
                entry = latest;
                semanticLease = true;
            }
        }
        if (entry == null) {
            if (Diagnostics.isEnabled()) {
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Diag] execution cache miss recipe={} terminal={}",
                        recipeId, modType == null ? "generic" : modType.id());
            }
            return null;
        }
        ResourceKey<Level> levelKey = dimension != null
                ? ResourceKey.create(
                Registries.DIMENSION, dimension)
                : player.serverLevel().dimension();
        BlockPos lookupPos = position != null ? position : player.blockPosition();
        boolean valid = entry.plan().success()
                && !stampStateAffectsPlan(modType, entry.plan())
                && PlanningStateValidator.revalidateForExecution(
                player, entry.snapshot(), entry.plan(),
                levelKey, lookupPos, storageReference);
        boolean consumed = semanticLease
                ? cache.consumeLatestForExecution(player.getUUID(), entry)
                : cache.consumeForExecution(exactKey, entry);
        if (!consumed) {
            RSIntegrationMod.debug(
                    "[RSI-Generic] Execution lease changed before validation completed: recipeId={}",
                    recipeId);
            return null;
        }
        if (!valid) {
            if (Diagnostics.isEnabled()) {
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Diag] execution cache state rejected recipe={} terminal={}",
                        recipeId, modType == null ? "generic" : modType.id());
            }
            RSIntegrationMod.debug("[RSI-Generic] Rejected execution cache state: recipeId={} reason=state-or-required-supply",
                    recipeId);
            return null;
        }
        if (Diagnostics.isEnabled()) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Diag] execution cache accepted recipe={} terminal={} graphRoots={}",
                    recipeId, modType == null ? "generic" : modType.id(),
                    entry.resolvedGraph() == null ? -1 : entry.resolvedGraph().rootDemands().size());
        }
        return entry;
    }

    private static boolean matchesExecutionLease(
            PlanCache.Entry entry, ServerPlayer player, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, int repeatCount,
            @Nullable ItemStack targetOutput, @Nullable ModType modType) {
        if (entry == null || entry.plan() == null || entry.snapshot() == null
                || !entry.plan().success() || entry.resolvedGraph() == null) return false;
        if (!entry.snapshot().playerId().equals(player.getUUID())
                || !entry.snapshot().recipeId().equals(recipeId)
                || entry.snapshot().recipeRevision() != CraftPlanningRevision.current()) return false;
        Map<ResourceLocation, ResourceLocation> forcedOverrides =
                CraftForcedRecipeOverrides.resolve(player, forcedRecipes).overrides();
        if (forcedOverrides == null
                || !entry.snapshot().forcedRecipes().equals(forcedOverrides)) return false;
        PlanResponse plan = entry.plan();
        if (!recipeId.toString().equals(plan.recipeId())
                || plan.repeatCount() != repeatCount) return false;
        String expectedType = modType == null ? "generic" : modType.id();
        if (plan.executionModTypeId() != null
                && !expectedType.equals(plan.executionModTypeId())) return false;
        ItemStack plannedOutput = plan.clickedOutput();
        if (plannedOutput == null || plannedOutput.isEmpty()) {
            return targetOutput == null || targetOutput.isEmpty();
        }
        return targetOutput != null && !targetOutput.isEmpty()
                && ItemStack.isSameItemSameTags(plannedOutput, targetOutput);
    }

    private static boolean tryExecuteCachedCraftingPlan(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dim,
            @Nullable BlockPos pos, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            PlanCache.Entry cached, Map<String, ItemStack> materialLocks) {
        ModType terminalType = modTypeForCachedExecution(recipe);
        ModRecipeHandler terminalHandler = ModRecipeHandlers.claimFor(recipe).winner();
        if (terminalHandler != null && !terminalHandler.hasDeterministicPrimaryOutput(recipe)) return false;
        if (cached.resolvedGraph() == null) {
            return false;
        }
        ItemStack recipeOutput = recipe instanceof CraftingRecipe craftingRecipe
                ? craftingRecipe.getResultItem(player.serverLevel().registryAccess()).copy()
                : ModRecipeHandlers.tryGetResultItem(recipe, player.serverLevel().registryAccess());
        if (GoetyDynamicRitualRecipe.isSupported(recipe)) {
            recipeOutput = GoetyDynamicRitualRecipe.validatedOutput(recipe, targetOutput);
        }
        if (recipeOutput.isEmpty()) return false;
        ResourceLocation terminalRecipeType = recipeId;
        ResolutionStep terminalStep = new ResolutionStep(recipeId, terminalType,
                terminalRecipeType, List.of(), List.of(), inferMode, Math.max(1, repeatCount));
        recipeOutput = CraftTerminalSteps.selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
        try {
            CraftPlanGraph completeGraph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                    cached.resolvedGraph(), terminalStep, recipeOutput,
                    player.serverLevel());
            List<ResolutionStep> steps = ExecutionEquivalence.projectFlatSteps(completeGraph);
            RSIntegrationMod.debug(
                    "[RSI-Generic] Executing revalidated preview input graph for {} nodes={}",
                    recipeId, completeGraph.nodes().size());
            launchGraphAsyncChain(player, completeGraph, storageEndpoint,
                    recipeId, forcedRecipes, dim, pos,
                    targetOutput, outputDestination, machineSelectionMode,
                    materialLocks);
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

    private static ModType modTypeForCachedExecution(Recipe<?> recipe) {
        ModRecipeHandler claimed =
                ModRecipeHandlers.claimFor(recipe).winner();
        return modTypeForCachedExecution(
                claimed, ModType.classifyRecipe(recipe));
    }

    static ModType modTypeForCachedExecution(
            @Nullable ModRecipeHandler claimed,
            @Nullable ModType type
    ) {
        if (claimed != null && claimed.modType() != ModType.GENERIC) {
            return claimed.modType();
        }
        return type == null ? ModType.GENERIC : type;
    }

    private static void tickExecutionRequests(MinecraftServer server) {
        DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                CraftRequestQueues.pollExecution();
        if (request == null) return;
        if (System.nanoTime() - request.queuedAtNanos()
                >= CraftRequestQueues.CATALOG_TIMEOUT_NANOS) {
            failExpiredCatalogRequest(server, request);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            request.payload().accept(player);
        }
    }

    /** Runs at most one valid deferred request after a complete generation is ready. */
    public static void tickWarmUpRequests(MinecraftServer server) {
        PlanningProgressServer.tick(server, GenericCraftPacket::cancelTimedOutPlanning);
        // Runtime recipe drift is handled from the server tick, never from the
        // packet handler, so the first craft request cannot synchronously rebuild
        // the complete recipe catalog on its network task.
        RecipeIndex.refreshDynamicRuntimeIfNeeded(server.overworld());
        expireCatalogRequest(server, CraftRequestQueues.pollExpiredWarmUp(System.nanoTime()));
        expireCatalogRequest(server, CraftRequestQueues.pollExpiredExecution(System.nanoTime()));
        tickTypedPreviewRequests(server);
        if (warmUpReady(server.overworld())) {
            tickExecutionRequests(server);
        }
        if (RecipeIndex.generationBuildFailed()) {
            // Keep even failure notifications incremental. A full warm-up queue
            // can contain dozens of clicks and draining it here used to create a
            // server-thread burst exactly when the recipe catalog was unhealthy.
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                    CraftRequestQueues.pollWarmUp();
            if (request != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                if (player != null) {
                    Component message = Component.translatable("rsi.plan.failure.catalog_unavailable");
                    player.sendSystemMessage(message);
                    if (request.preview()) {
                        PlanningProgressServer.fail(player, request.generation(), message);
                    }
                }
            }
            return;
        }
        if (!warmUpReady(server.overworld())) return;
        int remaining = CraftRequestQueues.warmUpSize();
        while (remaining-- > 0) {
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                    CraftRequestQueues.pollWarmUp();
            if (request == null) return;
            if (System.nanoTime() - request.queuedAtNanos()
                    >= CraftRequestQueues.CATALOG_TIMEOUT_NANOS) {
                failExpiredCatalogRequest(server, request);
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
            if (player == null) continue;
            if (request.preview()
                    && !CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation())) continue;
            request.payload().accept(player);
            return;
        }
    }

    private static void expireCatalogRequest(MinecraftServer server,
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        if (request == null) return;
        failExpiredCatalogRequest(server, request);
    }

    private static void failExpiredCatalogRequest(MinecraftServer server,
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null || request.preview()
                && !CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation())) return;
        Component message = Component.translatable("rsi.plan.failure.catalog_wait_timeout");
        player.sendSystemMessage(message);
        if (request.preview()) {
            PlanningProgressServer.fail(player, request.generation(), message);
            cancelTimedOutPlanning(request.playerId(), request.generation());
        }
    }

    private static void cancelTimedOutPlanning(UUID playerId, long generation) {
        PlanRequestService requests = CraftPlanningRuntime.planRequests();
        if (requests.isCurrent(playerId, generation)) {
            requests.forget(playerId);
        }
    }

    private static void tickTypedPreviewRequests(MinecraftServer server) {
        int admissions;
        int timeoutMs;
        try {
            admissions = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK.get();
            timeoutMs = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS.get();
        } catch (Exception ignored) {
            admissions = RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK;
            timeoutMs = RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS;
        }
        TypedPreviewAdmissionQueue queue = CraftPlanningRuntime.typedPreviewRequests();
        int admitted = queue.run(Math.max(1, admissions), System.nanoTime(),
                Math.max(1, timeoutMs) * 1_000_000L, request -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                    return player != null && !player.hasDisconnected()
                            && CraftPlanningRuntime.planRequests().isCurrent(request.playerId(), request.generation());
                });
        PerformanceMonitor.recordTypedPreviewAdmitted(admitted, queue.size());
    }

    // ── execute: resolve ingredients, craft result, give result to player ──

    // ── FA ritual lookup (delegates to FaRitualHelper) ──────────

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
        CraftPreparationLauncher.launch(player, executableGraph, recipeId, storageEndpoint,
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
        CraftExecutionLauncher.launchGraph(
                player, graph, storageEndpoint, recipeId,
                forcedRecipes, dim, pos, targetOutput,
                outputDestination, machineSelectionMode, materialLocks);
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode) {
        tryResolve(player, recipeId, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode, null);
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode,
                                   @Nullable StorageReference storageReference) {
        tryResolve(player, recipeId, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageReference, Map.of());
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
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
                storageReference, materialLocks, false, false);
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
                storageReference, materialLocks, true, false);
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
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
                                   boolean partialPreparation) {
        tryResolve(player, recipeId, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, outputDestination, machineSelectionMode,
                storageReference, materialLocks, typedGraphOnly, partialPreparation,
                PlanningPreferences.empty());
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
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
                                   PlanningPreferences preferences) {
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
            // CrockPot pure-category recipes carry no fixed ingredient list — the batch delegate
            // selects items by food value at run time (Phase 2). Let these through with an empty
            // spec list so they reach the machine dispatch below; the no-bound-machine guard still
            // blocks genuinely unrunnable cases.
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
            if (virtualHandler == null || virtualHandler.modType() != modType
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
        CraftStorageEndpoint contextEndpoint = StorageRestockSupport.resolve(player).orElse(null);
        CraftStorageEndpoint storageEndpoint = null;
        // A backend-qualified reference is authoritative.
        if (storageReference != null) {
            var endpoint = CraftStorageEndpoints.resolve(storageReference, player);
            // A selected reference is authoritative.  Never replace a stale
            // reference with the last RS session or another backend's default:
            // doing so makes a single RS click permanently lock later crafts
            // to RS and makes planning/execution use different snapshots.
            if (endpoint.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Storage] selected reference unavailable; refusing backend fallback backend={} id={} player={}",
                        storageReference.backendId(), storageReference.networkId(),
                        player.getGameProfile().getName());
                player.sendSystemMessage(Component.translatable("rsi.generic.error.network_unavailable"));
                return;
            }
            storageEndpoint = endpoint.orElse(null);
        } else if (contextEndpoint != null
                && !CraftStorageEndpoints.isRefinedStorage(contextEndpoint)) {
            storageEndpoint = contextEndpoint;
        } else {
            storageEndpoint = CraftPlanStorageService.resolveEndpoint(
                    player, dim, pos, modType);
            if (storageEndpoint != null) {
                contextEndpoint = storageEndpoint;
            }
            // Keep execution and planning symmetric when the terminal context
            // is absent from the packet. The registry resolver still applies
            // backend order and permissions; it does not blindly select BD.
            if (storageEndpoint == null) {
                var fallback = contextEndpoint != null
                        ? Optional.of(contextEndpoint)
                        : CraftStorageEndpoints.resolveDefault(player);
                if (fallback.isPresent()) {
                    storageEndpoint = fallback.orElseThrow();
                }
            }
            if (storageEndpoint != null) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage] execution resolved default endpoint backend={} id={}",
                        storageEndpoint.session().reference().backendId(),
                        storageEndpoint.session().reference().networkId());
            }
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ExecAvail] recipe={} preparation={} endpoint={} reference={} destination={}",
                recipeId, partialPreparation,
                storageEndpoint == null ? "none" : storageEndpoint.session().reference().backendId(),
                storageReference == null ? "default" : storageReference,
                outputDestination);
        // The preview snapshot is fingerprinted with the canonical selected
        // backend reference.  Execution may arrive with a legacy/null packet
        // reference, so validate against the endpoint actually selected above
        // or the cache will be rejected on every confirmation.
        final StorageReference executionStorageReference = storageEndpoint != null
                ? storageEndpoint.session().reference() : storageReference;

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

        // Smithing is authorized by its bound table above, then assembled
        // directly from the concrete extracted inputs below. Physical machines
        // and logical virtual recipes both need their typed terminal delegate:
        // the latter have no block, but may still depend on recursively crafted
        // inputs (for example Malum void favor consuming a craftable relic).
        // The auto-crafting flag controls intermediate resolution, not whether
        // the terminal must still be represented by a typed graph node.
        if (CraftExecutionPolicy.requiresTypedTerminalExecution(recipe, modType)
                && effectiveDim != null && effectivePos != null
                && modType != ModType.byId("smithing")) {
            if (modType != null) {
                List<IngredientSpec> executionSpecs = specs;
                if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                    ServerLevel crockPotLevel = CraftPacketUtils.resolveLevel(
                            player.server, effectiveDim, player);
                    // 使用请求已经选定的端点，避免类别解析重新选择其他后端。
                    List<IngredientSpec> categorySpecs =
                            CrockPotBatchDelegate.buildCategoryPlanIngredients(
                                    recipe, storageEndpoint, player, crockPotLevel, effectivePos);
                    if (categorySpecs == null || categorySpecs.isEmpty()) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.crockpot.error.food_values"));
                        return;
                    }
                    executionSpecs = categorySpecs;
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
                PlanCache.Entry cachedMachinePlan = findValidatedExecutionPlan(
                        player, recipeId, forcedRecipes, repeatCount, targetOutput, modType,
                        effectiveDim, effectivePos, executionStorageReference, materialLocks);
                if (cachedMachinePlan != null
                        && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                        dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                        outputDestination, machineSelectionMode, storageEndpoint,
                        cachedMachinePlan, materialLocks)) {
                    return;
                }
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
                if (!typedGraphOnly && safePreferences.isEmpty()) {
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
                    if (!typedGraphOnly && safePreferences.isEmpty()
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
                            cachedMachinePlan != null,
                            cachedMachinePlan != null && cachedMachinePlan.purePlan() != null,
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
            if (CraftExecutionPolicy.canUseDirectCraftingReservation(
                    recipe, player.serverLevel().registryAccess())) {
                PlanCache.Entry cachedCraftingPlan = findValidatedExecutionPlan(player, recipeId,
                        forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                        executionStorageReference, materialLocks);
                if (cachedCraftingPlan != null
                        && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                        dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                        outputDestination, machineSelectionMode, storageEndpoint,
                        cachedCraftingPlan, materialLocks)) {
                    return;
                }
            }
            // Every projected crafting-table recipe belongs on the immutable worker.
            // The bounded typed resolver remains authoritative for execution.
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
            ItemStack recipeOutput = cr.getResultItem(
                    player.serverLevel().registryAccess()).copy();
            PlanCache.Entry cachedPreResolved = findValidatedExecutionPlan(player, recipeId,
                    forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                    executionStorageReference, materialLocks);
            if (cachedPreResolved != null
                    && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                    dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, storageEndpoint,
                    cachedPreResolved, materialLocks)) {
                return;
            }
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
            if (!allSteps.isEmpty() && graphMissing.isEmpty()) {
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
            PlanCache.Entry cachedExecutionPlan = findValidatedExecutionPlan(player, recipeId,
                    forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                    executionStorageReference, materialLocks);
            if (cachedExecutionPlan != null && tryExecuteCachedCraftingPlan(player, cr2, recipeId,
                    forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, storageEndpoint,
                    cachedExecutionPlan, materialLocks)) {
                return;
            }
            Map<StackKey, Long> avail = listAvailable(player, storageEndpoint);
            logExecutionAvailability(recipeId, avail);
            List<String> missingCheck = new ArrayList<>();
            ItemStack recipeOutput = cr2.getResultItem(
                    player.serverLevel().registryAccess()).copy();
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
            if (planSteps != null && !planSteps.isEmpty() && missingCheck.isEmpty()) {
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

            RSIntegrationMod.debug("[RSI-Generic] Unified resolver: nothing to resolve for {}, falling through to direct extraction", recipeId);
        }

        // Guard: non-CraftingRecipe mod recipes REQUIRE a bound machine.
        // Falling through to grouped extraction would consume items without
        // actually running the machine crafting.
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
        if (modType == ModType.byId("smithing") && storageEndpoint != null
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
                if (!directSmithing.available()) {
                    // Smithing inputs are immutable planner data as well. Do not spend the
                    // server-thread compatibility slice before trying the background route.
                    if (!typedGraphOnly && safePreferences.isEmpty()
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
                        if (!typedGraphOnly && safePreferences.isEmpty()
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
                    if (interSteps != null && !interSteps.isEmpty() && missing.isEmpty()) {
                        ResolutionStep terminalStep = CraftTerminalSteps.smithingTerminalStep(recipeId, repeatCount);
                        boolean physicalSteps = interSteps.stream().anyMatch(
                                step -> step.modType() != ModType.GENERIC);
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
        synchronized (GenericCraftPacket.class) {
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

    // ── preview: build plan and send to client ───────────────────

    static boolean hasSelectedCraftingPlanner(boolean pure, boolean directTerminal,
                                               boolean typed) {
        return pure || directTerminal || typed;
    }

    static boolean isSnapshotActive(ServerPlayer player, PlanningSnapshot snapshot,
                                    long previewGeneration) {
        return !player.hasDisconnected() && !player.isRemoved()
                && snapshot.requestGeneration() == previewGeneration
                && CraftPlanningRuntime.planRequests().isCurrent(player.getUUID(), previewGeneration)
                && CraftPlanningRevision
                .isCurrent(snapshot.recipeRevision());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false,
                false, CraftPlanResultDispatcher.networkSink(player, requestId, previewGeneration), null, Map.of());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable StorageReference storageReference) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false,
                false, CraftPlanResultDispatcher.networkSink(player, requestId, previewGeneration), storageReference, Map.of());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable BlockPos pos,
                                      int repeatCount, @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false,
                false, CraftPlanResultDispatcher.networkSink(player, requestId, previewGeneration), storageReference, materialLocks);
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
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
                                      CraftPlanResultDispatcher.Sink sink) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan,
                precomputedSnapshot, asyncAttempted,
                reuseValidatedSnapshot, sink, null, Map.of());
    }

    static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
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
                             Map<String, ItemStack> materialLocks) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan, precomputedSnapshot,
                asyncAttempted, reuseValidatedSnapshot, sink,
                storageReference, materialLocks, null, PlanningPreferences.empty());
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
                // For any category-constrained recipe (pure or mixed), run
                // the food-value-aware selection so the plan preview shows
                // exactly the items the batch delegate will place — both
                // specific MustContain ingredients and filler items.
                ResourceKey<Level> cpDim = dim != null
                        ? ResourceKey.create(Registries.DIMENSION, dim)
                        : player.serverLevel().dimension();
                BlockPos cpPos = pos != null ? pos : player.blockPosition();
                CraftStorageEndpoint cpEndpoint = storageReference != null
                        ? CraftStorageEndpoints.resolve(storageReference, player).orElse(null)
                        : CraftPacketUtils.resolveEndpointForCraft(
                                player, cpDim, cpPos).orElse(null);
                specs = CrockPotBatchDelegate.buildCategoryPlanIngredients(
                        recipe, cpEndpoint, player, player.serverLevel(), cpPos);
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

        final PlanCache.Key cacheKey = CraftPlanCacheKeys.key(player.getUUID(), recipeId,
                forcedRecipes, repeatCount, clickedOutput, recipeModType, materialLocks,
                safePreferences);
        ResourceKey<Level> planDimKey = dim != null
                ? ResourceKey.create(
                        Registries.DIMENSION, dim)
                : player.serverLevel().dimension();
        BlockPos planLookupPos = pos != null ? pos : player.blockPosition();
        CraftPlanStorageService.Context storageContext =
                CraftPlanStorageService.resolvePlanContext(
                        player, dim, pos, recipeModType, storageReference);
        if (storageContext.failed()) {
            sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnostic.error(storageContext.failure()));
            return;
        }
        CraftStorageEndpoint planningEndpoint = storageContext.endpoint();
        final StorageReference planStorageReference = storageContext.reference();
        List<StorageNetworkDescriptor> storageNetworks = storageContext.networks();

        PlanCache.Entry cached = CraftPlanningRuntime.planCache().get(cacheKey, System.nanoTime());
        // 印模槽属于机器实时状态，旧快照不能决定是否需要递归制作印模。
        if (stampStateAffectsPlan(previewModType, cached == null ? null : cached.plan())) {
            cached = null;
        }
        if (cached != null && cached.plan().success() && cached.plan().graph() != null
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
                BindingAwareGraph bindingGraph = recipe instanceof CraftingRecipe
                        || backgroundProjectable
                        ? scopedBindingAwareGraph(player,
                                ImmutableRecipeGraphProjector.capture(player.serverLevel()), recipeId)
                        : new BindingAwareGraph(new ImmutableRecipeGraph(Map.of()), Set.of());
                planningSnapshot = PlanningSnapshotFactory.capture(
                        player.getUUID(), previewGeneration, recipeId, available,
                        effectiveOverrides,
                        bindingGraph.graph(),
                        PlanningStateValidator.networkFingerprint(
                                planStorageReference, available),
                        PlanningStateValidator.bindingFingerprint(player, planDimKey, planLookupPos),
                        bindingGraph.blockedOutputIds(),
                        !(recipe instanceof CraftingRecipe) && !backgroundProjectable,
                        safePreferences);
            } finally {
                PerformanceMonitor.recordPlanningSnapshot(System.nanoTime() - snapshotStarted);
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
            sink.success(cached.plan(), planningSnapshot);
            return;
        }
        if (cached != null) {
            RSIntegrationMod.debug(
                    "[RSI-tryBuildPlan] Rejected preview cache because the planning snapshot changed: recipeId={}",
                    recipeId);
        }

        // A Market trade is a fixed one-input/one-output exchange. When its
        // payment is already present, recursive resolution cannot add useful
        // work, but expanding a large repeat count through the generic resolver
        // performs thousands of main-thread inventory probes. Build the exact
        // same terminal plan arithmetically in constant time. If payment is not
        // directly available, retain the generic path so it can craft the cost.
        if (recipe instanceof MarketRecipeWrapper market
                && effectiveOverrides.isEmpty()
                && CraftMarketPlanService.tryBuildDirectPlan(
                        player, market, recipeId, repeatCount,
                planTargetOutput, dim, pos, available, planningSnapshot, cacheKey,
                planStartNanos, sink)) {
            return;
        }

        boolean hasFixedTerminalInputs = recipeSpecs.stream()
                .anyMatch(spec -> spec != null && !spec.isEmpty());
        boolean executionMachineAvailable = !CraftExecutionPolicy.requiresBoundMachine(recipe, recipeModType)
                || CraftMachineBindingSupport.hasBindingForExecutionType(player, recipe, recipeModType);
        DirectMaterialAllocator.Result directPreviewAllocation =
                executionMachineAvailable && effectiveOverrides.isEmpty()
                && hasFixedTerminalInputs
                        ? DirectMaterialAllocator.allocateScaled(recipeSpecs, available)
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
        if (!asyncAttempted && CraftPlanDecisionRules.shouldUseAsyncPreviewRoute(directTerminalPlan,
                planningSnapshot.mainThreadOnly(),
                effectiveOverrides.isEmpty() || ForcedRecipeGraphProjector.apply(
                        planningSnapshot.recipeGraph(), effectiveOverrides) != null,
                !materialLocks.isEmpty())) {
            var routing = new AsyncPurePlanningService.RouteInputs(
                    routingAvailability(planningSnapshot.availableItems()), demandMaxNodes,
                    reusableCatalystOutputIds, reusableCatalystRecipeIds, pureIncompatibleOutputIds);
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
                                || failure instanceof AsyncPlanningCoordinator
                                .StalePlanningResultException) {
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
        // NODE_LIMIT is only a conservative routing-probe cutoff. Keep those
        // requests on the immutable background planner without switching this
        // attempt to the typed server-thread resolver.
        boolean projectedOverrides = effectiveOverrides.isEmpty()
                || routedPlan != null
                && routedPlan.snapshot().equals(planningSnapshot)
                && routedPlan.forcedRecipesProjected();
        boolean pureRoute = demandTree.backgroundCompatible()
                && projectedOverrides
                && materialLocks.isEmpty()
                && !planningSnapshot.mainThreadOnly();
        // 规划只需要可用的存储端点，不能再用 RS 原生网络做可用性判断。
        boolean typedResolverAvailable = RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()
                && planningEndpoint != null;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-plan] planner route recipe={} pure={} coverage={} nodes={} unresolved={} overrides={} mainThreadOnly={} catalystRoute={} targetCatalyst={}",
                recipeId, pureRoute, demandTree.status(), demandTree.visitedNodes(),
                demandTree.unresolved(), !effectiveOverrides.isEmpty(), planningSnapshot.mainThreadOnly(),
                demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst);
        boolean terminalPureResult = directTerminalPlan
                || precomputedPlan != null;
        boolean timedOutMissingRoute = terminalPureResult
                && CraftPlanDecisionRules.canOpenBoundedMissingPlan(precomputedPlan, demandTree);
        if (!directTerminalPlan && terminalPureResult
                && precomputedPlan.feasibility() == PureRecipePlanner.Feasibility.UNKNOWN
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
        boolean requiresTypedCatalystRoute = CraftPlanDecisionRules.requiresTypedCatalystRoute(
                demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst,
                precomputedPlan, reusableCatalystOutputIds);
        boolean requiresTypedIncompleteRoute = CraftPlanDecisionRules.shouldRetryTypedPlanning(
                precomputedPlan, planningSnapshot.bindingBlockedOutputIds());
        boolean selectedPureResolver = terminalPureResult && projectedOverrides
                && !requiresTypedCatalystRoute
                && !requiresTypedIncompleteRoute;
        if (requiresTypedIncompleteRoute) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-plan] retrying typed preview after incomplete pure plan recipe={} missing={}",
                    recipeId, CraftPlanDecisionRules.summarizePureDemands(precomputedPlan.missing()));
        }
        boolean needsTypedResolver = !selectedPureResolver;
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
            long typedResolverStarted = System.nanoTime();
            int typedTimeoutMs = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get();
            try {
                CraftingResolver.ActiveRootRecipe activeRoot =
                        new CraftingResolver.ActiveRootRecipe(recipeId, targetOutput);
                planGraph = CraftExecutionPolicy.usesPhysicalMachineInputSlots(recipe)
                        ? CraftingResolver.resolveMachineGraphForScaledSpecsWithTypes(
                                recursiveRecipeSpecs, available, player.serverLevel(),
                                player, planningEndpoint, missing, forcedOverrides, true, typedTimeoutMs,
                                activeRoot, materialLocks, safePreferences)
                        : CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                                recursiveRecipeSpecs, available, player.serverLevel(),
                                player, planningEndpoint, missing, forcedOverrides, true, typedTimeoutMs,
                                activeRoot, materialLocks, safePreferences);
            } catch (CraftingPlanningTimeoutException timeout) {
                PerformanceMonitor.recordResolveTimeout();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-plan] typed planner timeout recipe={} timeoutMs={} specs={} missing={} directReservation={} coverage={} unresolved={} catalystRoute={} targetCatalyst={} mainThreadOnly={} overrides={}",
                        recipeId, typedTimeoutMs, recursiveRecipeSpecs.size(), missing,
                        directTerminalPlan, demandTree.status(), demandTree.unresolved(),
                        demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst,
                        planningSnapshot.mainThreadOnly(), !effectiveOverrides.isEmpty());
                // A typed retry can time out after the immutable planner has already
                // produced a bounded dependency trace. Keep that trace as an
                // explicitly infeasible preview so the player can inspect and open
                // recursive sub-recipes instead of losing the whole screen to a
                // transient 500 ms resolver deadline. This path never authorizes
                // execution: the response remains infeasible and retains its
                // shortage/machine warnings below.
                if (CraftPlanDecisionRules.canUseBoundedTraceAfterTypedTimeout(
                        precomputedPlan, demandTree, directTerminalPlan)) {
                    if (missing.isEmpty()) {
                        for (var unresolved : precomputedPlan.missing()) {
                            recordMissingMaterial(missing, unresolved);
                        }
                    }
                    selectedTypedResolver = false;
                    selectedPureResolver = true;
                    usedPurePlan = true;
                    resolutionSteps = PurePlanAdapter.toResolutionSteps(
                            precomputedPlan, planningSnapshot.recipeGraph());
                    planGraph = null;
                } else {
                    if (missing.isEmpty()) {
                        sink.error(PlanOutcome.BUDGET_EXHAUSTED,
                                IntegrationDiagnostic.error(
                                        IntegrationDiagnosticCode.PLANNING_TIMEOUT));
                    } else {
                        sink.error(PlanOutcome.MISSING_INPUTS,
                                IntegrationDiagnostic.error(
                                        IntegrationDiagnosticCode.MISSING_MATERIALS));
                    }
                    return;
                }
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-plan] Typed resolver failed for {}", recipeId, failure);
                sink.error(PlanOutcome.RUNTIME_UNAVAILABLE,
                        IntegrationDiagnostic.error(IntegrationDiagnosticCode.INTERNAL_ERROR));
                return;
            } finally {
                PerformanceMonitor.recordTypedResolver(System.nanoTime() - typedResolverStarted);
            }
            if (planGraph != null) {
                resolutionSteps = ExecutionEquivalence.projectFlatSteps(planGraph);
            }
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
        Map<Item, Long> itemAvailable = new HashMap<>();
        for (var e : available.entrySet()) {
            itemAvailable.merge(e.getKey().item(), e.getValue(), SaturatingLongMath::add);
        }
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
            ItemStack output = RecipeIndex.tryGetResultItem(
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
                    // hasBindingForRecipe re-classifies the recipe, so vanilla
                    // furnace/blast/smoker/stonecutter recipes — which classifyRecipe
                    // treats as null/GENERIC — are always available and need no bound
                    // machine. The old hasAnyBindingForType(vanilla_furnace) check
                    // wrongly hid those alternatives (e.g. the blast-furnace path for
                    // refined_beeswax_bar), leaving only the stonecutter step with no
                    // switch button even though the resolver could use either.
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
                        if (e.recipe() instanceof CraftingRecipe cr) {
                            altOut = cr.getResultItem(player.serverLevel().registryAccess());
                        } else {
                            altOut = ModRecipeHandlers.tryGetResultItem(e.recipe(), player.serverLevel().registryAccess());
                        }
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

            steps.add(new PlanStep(recipeId, planTargetOutput, repeatCount, targetInputs,
                    targetAlts, recipeModType, targetDepth, !targetAlts.isEmpty(),
                    targetW, targetH, targetAltModTypes, targetInputRoles));

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

        PlanGraphView planGraphView = planGraph != null ? PlanGraphView.from(planGraph) : null;
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

        boolean nbtMismatch = false;
        if (!feasible) {
            nbtMismatch = CraftPlanDecisionRules.hasNbtMismatch(materials, itemAvailable);
            boolean bindingBlocked = precomputedPlan != null
                    && CraftPlanDecisionRules.allMissingRequireBlockedOutput(
                            precomputedPlan.missing(),
                    planningSnapshot.bindingBlockedOutputIds());
            if (allExecutionMachinesLeased) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.machines_leased"));
            } else if (bindingBlocked) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.no_bound_machine"));
            } else if (CraftPlanDecisionRules.hasCyclicPureFailure(precomputedPlan)) {
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
                CraftPlanDecisionRules.hasCyclicPureFailure(precomputedPlan);
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
            if (FAILURE_LOG_SAMPLER.allow("cycle:" + recipeId)) {
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

        PlanResponseDraft responseDraft = new PlanResponseDraft(
                feasible,
                targetName,
                planTargetOutput,
                steps,
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

        int responseStepCount = steps.size();
        int responseGraphNodes = planGraphView != null ? planGraphView.nodes().size() : 0;
        boolean responseFeasible = feasible;
        CraftPlanGraph resolvedGraphForCache =
                cacheInputGraph != null ? cacheInputGraph : planGraph;
        CraftPlanResponseFinalizer.submit(
                player, recipeId, previewGeneration, planningSnapshot, responseDraft,
                planDimKey, planLookupPos, planStorageReference, cacheKey,
                precomputedPlan, resolvedGraphForCache, responseStepCount,
                responseGraphNodes, responseFeasible, planStartNanos, sink);
    }

    /** Sends a planner failure without discarding the exact shortage and quantity. */
    static void sendPurePlanningFailure(
            ServerPlayer player, PureRecipePlanner.Result result,
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
        Component details = CraftFailureMessages.pureMissingSummary(result.missing());
        player.sendSystemMessage(Component.translatable(
                "rsi.generic.error.missing_materials", details));
    }

    private static boolean stampStateAffectsPlan(@Nullable ModType terminalType,
                                                 @Nullable PlanResponse plan) {
        if (terminalType != null && ModIds.ID_EMBERS_STAMPER.equals(terminalType.id())) return true;
        return plan != null && plan.steps().stream().anyMatch(step ->
                step.modType() != null && ModIds.ID_EMBERS_STAMPER.equals(step.modType().id()));
    }

    /** Give item to player only if still connected. Prevents ghost items
     *  when a player disconnects mid-batch and the item is voided. */
    private static void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            // Route every fallback delivery through PlayerUtils.  The direct
            // ItemHandlerHelper path mutates the server inventory but does not
            // broadcast the changed slots when a custom container is open,
            // leaving a client-side ghost stack until the inventory is reopened.
            PlayerUtils.safeGiveToStorage(player, stack, null);
        }
    }

    /**
     * Reports a plan failure to the client.
     *
     * <p>The message rides {@code modWarnings} as an unresolved Component. It must
     * not go through {@code missing}, which is a translation-key channel the client
     * feeds to {@code localizeItemNames} — and it must not be pre-rendered with
     * {@code getString()}, because a dedicated server cannot resolve {@code rsi.*}
     * keys.</p>
     */
    /** Clears preview plans when recipe data is reloaded. */
    public static void clearPlanCache() {
        CraftPlanningRuntime.planCache().clear();
    }

    /** Projects each physical stack once so broad and exact-NBT demands cannot reuse it. */
    private static Map<ImmutableRecipeGraph.MaterialRef, Long> routingAvailability(
            Map<StackKey, Long> available) {
        return ImmutableRecipeGraphProjector.projectAvailability(available);
    }

    public static void onPlayerLogout(UUID playerId) {
        CraftPlanResultDispatcher.clear(playerId);
        CraftRequestQueues.removePlayer(playerId);
        CraftPlanningRuntime.removePlayer(playerId);
        PlanningProgressServer.remove(playerId);
    }

    /** Cancels preview workers during server shutdown before world objects are torn down. */
    public static synchronized void cancelAllPlanning() {
        CraftPlanResultDispatcher.clear(null);
        CraftPlanningRuntime.shutdown();
        CraftRequestQueues.clear();
        PlanningProgressServer.clear();
        ImmutableRecipeGraphProjector.clearCache();
    }

    /** Applies server-configured planner resource limits by replacing the bounded executor. */
    public static synchronized void reloadPlanningConfig() {
        CraftPlanResultDispatcher.clear(null);
        MaterialVariantPreferences.refresh();
        CraftPlanningRuntime.reload();
        PlanningProgressServer.clear();
    }

}
