package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
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
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.TrackedNetworkInsertion;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePublisher;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.crafting.plan.MaxCraftableSearch;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.BirdcageEggCatalog;
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
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualHelper;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.RSAltarBindingResolver;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.ChainRepeatController;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.loadbalancer.LoadBalancer;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.PreviewRateLimiter;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.SynchronousFallbackReason;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.planning.PlanRequestService;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.GoetyRecipeHandler;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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

    private static Map<StackKey, Integer> listAvailable(ServerPlayer player,
                                                          @Nullable INetwork network) {
        return network == null
                ? MaterialSources.listAllAvailable(player, (INetwork) null)
                : MaterialSources.listAllAvailable(player,
                CraftStorageEndpoints.fromLegacyNetwork(network));
    }

    private static Map<StackKey, Integer> listAvailable(ServerPlayer player,
                                                          @Nullable INetwork network,
                                                          @Nullable CraftStorageEndpoint endpoint) {
        return endpoint == null ? listAvailable(player, network)
                : MaterialSources.listAllAvailable(player, endpoint);
    }

    private static DirectReservationCheck probeDirectTerminalReservation(
            ServerPlayer player, ResourceLocation recipeId, List<IngredientSpec> specs,
            Map<StackKey, Integer> available, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint) {
        if (specs.stream().noneMatch(spec -> spec != null && !spec.isEmpty())) {
            return new DirectReservationCheck(false, "no-fixed-inputs", "unknown");
        }
        DirectMaterialAllocator.Result allocation = DirectMaterialAllocator.allocate(specs, available);
        if (!allocation.feasible()) {
            int index = allocation.missingIngredientIndex();
            IngredientSpec missing = index >= 0 && index < specs.size() ? specs.get(index) : null;
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
                ItemStack reserved = endpoint != null
                        ? probe.reserveExactAcrossNetworkAndInventory(
                                template, fragment.count(), endpoint, player)
                        : probe.reserveExactAcrossNetworkAndInventory(
                                template, fragment.count(), network, player);
                if (reserved.isEmpty() || reserved.getCount() != fragment.count()) {
                    probe.rollback(player);
                    String material = String.valueOf(BuiltInRegistries.ITEM.getKey(template.getItem()))
                            + (template.hasTag() ? template.getTag() : "");
                    RSIntegrationMod.LOGGER.debug(
                            "[RSI-DirectReserve] live reservation rejected recipe={} material={} count={} directAllocation=true",
                            recipeId, material, fragment.count());
                    return new DirectReservationCheck(false, material, "EXACT_ALLOCATION");
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
            DirectMaterialAllocator.Result allocation, Map<StackKey, Integer> available) {
        Map<IngredientKey, Integer> needed = new LinkedHashMap<>();
        Map<IngredientKey, Integer> stored = new LinkedHashMap<>();
        for (DirectMaterialAllocator.Allocation fragment : allocation.allocations()) {
            IngredientKey key = IngredientKey.of(fragment.material().toStack());
            needed.merge(key, fragment.count(), Integer::sum);
        }
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            IngredientKey key = IngredientKey.of(entry.getKey().toStack());
            if (needed.containsKey(key)) stored.merge(key, entry.getValue(), Integer::sum);
        }
        Map<IngredientKey, PlanResponse.Availability> result = new LinkedHashMap<>();
        needed.forEach((key, count) -> result.put(key,
                new PlanResponse.Availability(count, stored.getOrDefault(key, 0))));
        return result;
    }

    private static boolean hasRefinedStorage() {
        return net.minecraftforge.fml.ModList.get().isLoaded(ModIds.REFINED_STORAGE);
    }
    private static final int MAX_DEFERRED_WARM_UP_REQUESTS = 128;
    private static final int MAX_DEFERRED_EXECUTION_REQUESTS = 64;
    private static final LogSampler FAILURE_LOG_SAMPLER = new LogSampler(2_000);
    private static volatile PlanRequestService PLAN_REQUESTS = newDefaultPlanRequestService();
    private static volatile TypedPreviewAdmissionQueue TYPED_PREVIEW_REQUESTS =
            new TypedPreviewAdmissionQueue(
                    RSIntegrationConfig.DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY);
    private static final DeferredCraftRequestQueue<Consumer<ServerPlayer>> WARM_UP_REQUESTS =
            new DeferredCraftRequestQueue<>(MAX_DEFERRED_WARM_UP_REQUESTS);
    private static final DeferredCraftRequestQueue<Consumer<ServerPlayer>> EXECUTION_REQUESTS =
            new DeferredCraftRequestQueue<>(MAX_DEFERRED_EXECUTION_REQUESTS);

    private static PlanRequestService newDefaultPlanRequestService() {
        return newPlanRequestService(CraftingPlanningConfig.defaults());
    }

    private static PlanRequestService newPlanRequestService() {
        return newPlanRequestService(CraftingPlanningConfig.load());
    }

    private static PlanRequestService newPlanRequestService(CraftingPlanningConfig config) {
        return new PlanRequestService(
                config.workers(), config.queueCapacity(),
                config.maxSearchStates(), config.maxMemoizedFailures(),
                config.pureTimeoutMs());
    }

    // The short TTL serves preview deduplication. PlanCache separately retains
    // the plan currently shown to each player until it is confirmed or replaced.
    private static final PlanCache PLAN_CACHE = new PlanCache();

    private final ResourceLocation recipeId;
    private final boolean preview;
    /** itemRegKey → forced recipeId (only for preview mode, empty when unused) */
    private final Map<String, String> forcedRecipes;
    private Map<String, ItemStack> materialLocks = Map.of();
    private final ResourceLocation dim;
    private final net.minecraft.core.BlockPos pos;
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
                              @Nullable net.minecraft.core.BlockPos pos) {
        this(recipeId, preview, forcedRecipes, dim, pos, 1);
    }

    /** Convenience: with repeat count, no infer mode. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, false);
    }

    /** Convenience: with infer mode, no base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, null);
    }

    /** All parameters, including JEI base item for FA smithing prefill. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, null);
    }

    /** Master constructor: adds the JEI ghost-output target for NBT-variant recipes. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
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
                              @Nullable net.minecraft.core.BlockPos pos,
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
                              @Nullable net.minecraft.core.BlockPos pos,
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
                              @Nullable net.minecraft.core.BlockPos pos,
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
                                                 @Nullable net.minecraft.core.BlockPos pos,
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
                              @Nullable net.minecraft.core.BlockPos pos) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, 1);
    }

    /** JEI-initiated preview with repeat, inferMode, and specific base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem);
    }

    /** JEI-initiated preview carrying the clicked ghost output (NBT-variant target). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem, targetOutput);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(preview);
        buf.writeVarInt(forcedRecipes.size());
        for (var e : forcedRecipes.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBoolean(pos != null);
        if (pos != null) buf.writeBlockPos(pos);
        buf.writeVarInt(repeatCount);
        buf.writeBoolean(inferMode);
        buf.writeBoolean(baseItem != null);
        if (baseItem != null) buf.writeItem(baseItem);
        buf.writeBoolean(targetOutput != null);
        if (targetOutput != null) buf.writeItem(targetOutput);
        buf.writeBoolean(requestId != 0L);
        if (requestId != 0L) buf.writeVarLong(requestId);
        outputDestination.write(buf);
        buf.writeBoolean(storageReference != null);
        if (storageReference != null) {
            buf.writeNbt(StorageReferenceCodec.encode(storageReference));
        }
        buf.writeBoolean(maximize);
        machineSelectionMode.write(buf);
        buf.writeVarInt(materialLocks.size());
        for (Map.Entry<String, ItemStack> entry : materialLocks.entrySet()) {
            buf.writeUtf(entry.getKey(), MaterialLocks.MAX_KEY_LENGTH);
            buf.writeItem(entry.getValue().copyWithCount(1));
        }
        // Strict packets retain the legacy byte layout. The opt-in marker is
        // only present for preparation requests, so old clients decode safely.
        if (partialPreparation) buf.writeBoolean(true);
    }

    public static GenericCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean preview = buf.readBoolean();
        int forcedCount = buf.readVarInt();
        // Reject rather than truncate: silently capping the loop at 128 would
        // leave the remaining declared pairs in the buffer, desyncing every
        // subsequent read (dim/pos) for a malicious or corrupt packet.
        if (forcedCount < 0 || forcedCount > 128) {
            throw new io.netty.handler.codec.DecoderException(
                    "GenericCraftPacket forcedCount out of range: " + forcedCount);
        }
        Map<String, String> forced = new HashMap<>();
        for (int i = 0; i < forcedCount; i++) {
            String key = buf.readUtf();
            String value = buf.readUtf();
            if (!key.isEmpty() && !value.isEmpty()
                    && ResourceLocation.tryParse(key) != null
                    && ResourceLocation.tryParse(value) != null) {
                forced.put(key, value);
            }
        }
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        net.minecraft.core.BlockPos pos = null;
        if (buf.readBoolean()) {
            net.minecraft.core.BlockPos raw = buf.readBlockPos();
            if (raw.getX() >= -30000000 && raw.getX() <= 30000000
                    && raw.getY() >= -64 && raw.getY() <= 2048
                    && raw.getZ() >= -30000000 && raw.getZ() <= 30000000) {
                pos = raw;
            }
        }
        int rawRepeatCount = buf.readVarInt();
        if (rawRepeatCount < 1 || rawRepeatCount > RSIntegrationConfig.REPEAT_COUNT_MAX.get())
            throw new IllegalArgumentException("invalid repeat count");
        int repeatCount = rawRepeatCount;
        boolean inferMode = buf.readBoolean();
        ItemStack baseItem = buf.readBoolean() ? buf.readItem() : null;
        // targetOutput is mandatory in protocol v18.
        ItemStack targetOutput = null;
        if (buf.readBoolean()) {
            targetOutput = buf.readItem();
        }
        long requestId = 0L;
        if (buf.isReadable() && buf.readBoolean()) {
            requestId = buf.readVarLong();
            if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
                throw new io.netty.handler.codec.DecoderException("GenericCraftPacket requestId out of range");
            }
        }
        OutputDestination outputDestination = buf.isReadable()
                ? OutputDestination.read(buf)
                : OutputDestination.RS_NETWORK;
        StorageReference storageReference = null;
        if (buf.isReadable() && buf.readBoolean()) {
            storageReference = StorageReferenceCodec.decode(buf.readNbt())
                    .orElseThrow(() -> new io.netty.handler.codec.DecoderException(
                            "invalid GenericCraftPacket storage reference"));
        }
        boolean maximize = buf.readBoolean();
        if (maximize && !preview) {
            throw new io.netty.handler.codec.DecoderException("maximize requires preview mode");
        }
        MachineSelectionMode machineSelectionMode = buf.isReadable()
                ? MachineSelectionMode.read(buf) : MachineSelectionMode.AUTO;
        Map<String, ItemStack> materialLocks = new LinkedHashMap<>();
        if (buf.isReadable()) {
            int materialLockCount = buf.readVarInt();
            if (materialLockCount < 0 || materialLockCount > MaterialLocks.MAX_LOCKS) {
                throw new io.netty.handler.codec.DecoderException(
                        "GenericCraftPacket materialLockCount out of range: " + materialLockCount);
            }
            for (int i = 0; i < materialLockCount; i++) {
                String key = buf.readUtf(MaterialLocks.MAX_KEY_LENGTH);
                ItemStack selected = buf.readItem();
                if (!key.isEmpty() && !selected.isEmpty()) {
                    materialLocks.put(key, selected.copyWithCount(1));
                }
            }
        }
        boolean partialPreparation = buf.isReadable() && buf.readBoolean();
        if (buf.isReadable()) {
            throw new io.netty.handler.codec.DecoderException("trailing GenericCraftPacket data");
        }
        GenericCraftPacket packet = new GenericCraftPacket(recipeId, preview, forced, dim, pos,
                repeatCount, inferMode, baseItem, targetOutput, requestId, outputDestination,
                storageReference);
        packet.maximize = maximize;
        packet.machineSelectionMode = machineSelectionMode;
        packet.materialLocks = MaterialLocks.immutableCopy(materialLocks);
        packet.partialPreparation = partialPreparation;
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

    ResourceLocation recipeId() {
        return recipeId;
    }

    Map<String, ItemStack> materialLocks() {
        return materialLocks;
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
        if (player instanceof net.minecraftforge.common.util.FakePlayer) {
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
            context.setPacketHandled(true);
            return;
        }
        final long previewGeneration = packet.preview
                ? PLAN_REQUESTS.begin(player.getUUID()) : 0L;
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
                player.sendSystemMessage(Component.translatable(
                        "rsi.plan.failure.catalog_unavailable"));
                return;
            }
            boolean queued = WARM_UP_REQUESTS.offer(new DeferredCraftRequestQueue.Entry<>(
                    player.getUUID(), packet.preview, previewGeneration, action));
            if (!queued) {
                player.sendSystemMessage(Component.translatable("rsi.plan.failure.planner_busy"));
                return;
            }
            RSIntegrationMod.debug(
                    "[RSI-Generic] deferred until recipe warm-up: recipeId={} preview={} queued={}",
                    packet.recipeId, packet.preview, WARM_UP_REQUESTS.size());
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
                if (!PLAN_REQUESTS.isCurrent(player.getUUID(), previewGeneration)) return;
                RSIntegrationMod.debug(
                        "[RSI-Generic] handle() -> tryBuildPlan: recipeId={}", packet.recipeId);
                if (packet.maximize) {
                    findMaxCraftable(player, packet, previewGeneration);
                } else {
                    tryBuildPlan(player, packet.recipeId, packet.forcedRecipes,
                            packet.dim, packet.pos, packet.repeatCount, packet.baseItem,
                            packet.targetOutput, packet.requestId, previewGeneration,
                            packet.storageReference, packet.materialLocks);
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
                player.sendSystemMessage(buildFailureMessage(e, packet.recipeId));
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
                readyPlayer.sendSystemMessage(buildFailureMessage(failure, recipeId));
            }
        };
        boolean queued = EXECUTION_REQUESTS.offer(new DeferredCraftRequestQueue.Entry<>(
                player.getUUID(), false, 0L,
                guardedAction));
        if (!queued) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planner_busy"));
            return;
        }
        RSIntegrationMod.debug("[RSI-Generic] execution queued: recipeId={} queueSize={}",
                recipeId, EXECUTION_REQUESTS.size());
    }

    private static void executeQueuedRequest(ServerPlayer player, GenericCraftPacket packet,
                                             boolean preparationRequest) {
        try {
            RSIntegrationMod.debug(
                    "[RSI-Generic] queued execution -> tryResolve: recipeId={} forced={} destination={}",
                    packet.recipeId, packet.forcedRecipes.size(), packet.outputDestination);
            tryResolve(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                    packet.repeatCount, packet.inferMode, packet.baseItem, packet.targetOutput,
                    packet.outputDestination, packet.machineSelectionMode,
                    packet.storageReference, packet.materialLocks,
                    false, preparationRequest);
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
                player.sendSystemMessage(buildFailureMessage(e, packet.recipeId));
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Generic] Failed to send queued execution error", ex);
            }
        }
    }

    /** Budget for the legacy live-object compatibility resolver only. */
    private static int compatibilityResolverBudgetMs() {
        try {
            return compatibilityResolverBudgetMs(
                    RSIntegrationConfig.CRAFTING_RESOLVE_TIMEOUT_MS.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS;
        }
    }

    static int compatibilityResolverBudgetMs(int resolveTimeoutMs) {
        return Math.max(1, resolveTimeoutMs);
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

    private static BindingAwareGraph bindingAwareGraph(
            ServerPlayer player, ImmutableRecipeGraph graph) {
        return filterRecipeGraph(graph, node -> {
            ModType type = ModType.byId(node.modTypeId());
            if (!requiresBoundMachine(type)) return true;
            Recipe<?> recipe = player.serverLevel().getRecipeManager()
                    .byKey(node.recipeId()).orElse(null);
            if (recipe == null || !requiresBoundMachine(recipe, type)) return true;
            // Keep vanilla smithing nodes in the planning graph so recursive
            // previews can show the complete upgrade chain. Execution still
            // requires a bound smithing table at the terminal entry point.
            if (isSmithingRecipe(recipe)) return true;
            return hasBindingForExecutionType(player, recipe, type);
        });
    }

    /**
     * Preview and confirmation must derive an identical cache key.  The
     * preview route includes the resolved terminal type so a generic crafting
     * recipe and a machine recipe sharing an id can never cross-reuse a plan.
     */
    static PlanCache.Key planCacheKey(UUID playerId, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes, int repeatCount,
                                      @Nullable ItemStack clickedOutput,
                                      @Nullable ModType terminalType) {
        return planCacheKey(playerId, recipeId, forcedRecipes, repeatCount,
                clickedOutput, terminalType, Map.of());
    }

    static PlanCache.Key planCacheKey(UUID playerId, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes, int repeatCount,
                                      @Nullable ItemStack clickedOutput,
                                      @Nullable ModType terminalType,
                                      Map<String, ItemStack> materialLocks) {
        return new PlanCache.Key(playerId, recipeId, forcedRecipes, repeatCount,
                clickedOutputCacheToken(clickedOutput) + "|"
                        + (terminalType == null ? "generic" : terminalType.id()),
                MaterialLocks.cacheTokens(materialLocks));
    }

    @Nullable
    private static PlanCache.Entry findValidatedExecutionPlan(
            ServerPlayer player, ResourceLocation recipeId, Map<String, String> forcedRecipes,
            int repeatCount, @Nullable ItemStack targetOutput, @Nullable ModType modType,
            @Nullable ResourceLocation dimension, @Nullable net.minecraft.core.BlockPos position,
            @Nullable StorageReference storageReference,
            Map<String, ItemStack> materialLocks) {
        PlanCache.Key exactKey = planCacheKey(player.getUUID(), recipeId,
                forcedRecipes, repeatCount, targetOutput, modType, materialLocks);
        PlanCache.Entry entry = PLAN_CACHE.takeForExecution(exactKey);
        if (entry == null) entry = PLAN_CACHE.get(exactKey, System.nanoTime());
        if (entry == null && (targetOutput == null || targetOutput.isEmpty()
                || !targetOutput.hasTag())) {
            PlanCache.Key fallbackKey = planCacheKey(player.getUUID(), recipeId,
                    forcedRecipes, repeatCount, null, modType, materialLocks);
            entry = PLAN_CACHE.takeForExecution(fallbackKey);
            if (entry == null) entry = PLAN_CACHE.get(fallbackKey, System.nanoTime());
        }
        if (entry == null || !entry.plan().success()) return null;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey = dimension != null
                ? net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimension)
                : player.serverLevel().dimension();
        net.minecraft.core.BlockPos lookupPos = position != null ? position : player.blockPosition();
        if (!PlanningStateValidator.revalidateForExecution(player, entry.snapshot(), entry.plan(),
                levelKey, lookupPos, storageReference)) {
            RSIntegrationMod.debug("[RSI-Generic] Rejected execution cache state: recipeId={} reason=state-or-required-supply",
                    recipeId);
            return null;
        }
        return entry;
    }

    private static boolean tryExecuteCachedCraftingPlan(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dim,
            @Nullable net.minecraft.core.BlockPos pos, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable INetwork network, @Nullable CraftStorageEndpoint storageEndpoint,
            PlanCache.Entry cached) {
        ModType terminalType = modTypeForCachedExecution(recipe);
        if (canUsePureExecutionPlan(cached.purePlan(), terminalType)) {
            List<ResolutionStep> steps = new ArrayList<>(PurePlanAdapter.toResolutionSteps(
                    cached.purePlan(), cached.snapshot().recipeGraph()));
            steps.add(genericTerminalStep(recipeId, repeatCount));
            RSIntegrationMod.debug("[RSI-Generic] Executing revalidated pure preview plan for {}",
                    recipeId);
            CachedPurePlanExecution execution = selectCachedPurePlanExecution(
                    steps, storageEndpoint != null,
                    RSIntegrationConfig.CRAFTING_VANILLA_OPERATIONS_PER_TICK.get());
            if (execution == CachedPurePlanExecution.REPLAN) {
                RSIntegrationMod.debug(
                        "[RSI-Generic] Cached pure plan for {} contains physical steps but has no storage endpoint; resolving again",
                        recipeId);
                return false;
            }
            if (execution == CachedPurePlanExecution.ASYNCHRONOUS) {
                LegacyExecutionMetrics.Reason reason = canExecuteSynchronously(steps)
                        ? LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD
                        : LegacyExecutionMetrics.rejectedGraphReason(steps);
                launchAsyncChain(player, steps, reason,
                        network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                        inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
            } else {
                executeSyncLoop(player, steps, network, storageEndpoint, recipeId, repeatCount,
                        "Intermediate crafting failed");
            }
            return true;
        }
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
        recipeOutput = selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
        try {
            CraftPlanGraph completeGraph = composeEquivalentTerminalGraph(
                    cached.resolvedGraph(), terminalStep, recipeOutput);
            List<ResolutionStep> steps = ExecutionEquivalence.projectFlatSteps(completeGraph);
            RSIntegrationMod.debug(
                    "[RSI-Generic] Executing revalidated typed preview graph for {} nodes={}",
                    recipeId, completeGraph.nodes().size());
            if (!canExecuteSynchronously(steps)
                    || storageEndpoint != null && shouldExecuteGenericChainAsync(steps)) {
                launchGraphAsyncChain(player, completeGraph, terminalStep, network, storageEndpoint,
                        repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem,
                        targetOutput, outputDestination, machineSelectionMode);
            } else {
                executeSyncLoop(player, steps, network, storageEndpoint, recipeId, repeatCount,
                        "Intermediate crafting failed");
            }
            return true;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Generic] Cached typed graph cannot be composed for {}; resolving again",
                    recipeId, exception);
            return false;
        }
    }

    private static ModType modTypeForCachedExecution(Recipe<?> recipe) {
        ModType type = ModType.classifyRecipe(recipe);
        return type == null ? ModType.GENERIC : type;
    }

    static boolean canUsePureExecutionPlan(@Nullable PureRecipePlanner.Result plan,
                                           ModType terminalType) {
        // Pure plans describe crafting-table operations only. A physical
        // terminal must retain its typed delegate and wait for real output.
        return terminalType == ModType.GENERIC && canUsePrecomputedPlan(plan);
    }

    /**
     * Queue a direct execution request through the immutable planner when no
     * preview cache is available.  The old path immediately entered the typed
     * resolver with a tiny hard-coded server-thread guard, which made complex
     * CraftTweaker recipes fail before they had a chance to be planned.
     */
    private static boolean queuePureExecutionPlan(
            ServerPlayer player, CraftingRecipe recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dim,
            @Nullable net.minecraft.core.BlockPos pos, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference) {
        if (storageEndpoint == null || !forcedRecipes.isEmpty()
                || !RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) return false;

        ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                recipe, player.serverLevel().registryAccess());
        if (recipeOutput.isEmpty()) return false;

        final BindingAwareGraph bindingGraph;
        try {
            bindingGraph = bindingAwareGraph(player,
                    ImmutableRecipeGraphProjector.capture(player.serverLevel()));
        } catch (RuntimeException unavailable) {
            RSIntegrationMod.debug("[RSI-exec] immutable graph unavailable for {}: {}",
                    recipeId, unavailable.toString());
            return false;
        }
        ImmutableRecipeGraph recipeGraph = bindingGraph.graph();
        if (!recipeGraph.recipesById().containsKey(recipeId)) return false;

        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, storageEndpoint);
        Set<ResourceLocation> reusableCatalystOutputIds =
                RSIntegrationConfig.ENABLE_CATALYST_RECIPE_PREFERENCE.get()
                        ? RecipeIndex.reusableCatalystOutputIds(player.serverLevel()) : Set.of();
        var routeInputs = new AsyncPurePlanningService.RouteInputs(
                routingAvailability(available), RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.get(),
                reusableCatalystOutputIds, RecipeIndex.reusableCatalystRecipeIds(player.serverLevel()),
                RecipeIndex.pureIncompatibleOutputIds(player.serverLevel()));
        ResourceLocation effectiveDim = dim != null
                ? dim : player.serverLevel().dimension().location();
        net.minecraft.core.BlockPos effectivePos = pos != null
                ? pos : player.blockPosition();
        StorageReference selectedReference = storageEndpoint.session().reference();
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(
                player.getUUID(), 0L, recipeId, available, Map.of(), recipeGraph,
                PlanningStateValidator.networkFingerprint(selectedReference, available),
                PlanningStateValidator.bindingFingerprint(player,
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, effectiveDim),
                        effectivePos), bindingGraph.blockedOutputIds(), false);

        PLAN_REQUESTS.submitRouted(snapshot, routeInputs, repeatCount, player.getServer()::execute,
                RSIntegrationConfig.CRAFTING_MAX_STEPS.get(), completed -> {
                    PureDemandTreeInspector.Result route = completed.inspection();
                    if (!route.backgroundCompatible() || completed.plan() == null) {
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-exec] immutable route unavailable recipe={} status={} unresolved={} catalystRoute={}",
                                recipeId, route.status(), route.unresolved(), route.catalystRouteAvailable());
                        tryResolveTypedFallbackOnly(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                inferMode, baseItem, targetOutput, outputDestination,
                                machineSelectionMode, storageReference, Map.of());
                        return;
                    }
                    PureRecipePlanner.Result result = completed.plan();
                    if (!result.feasible()) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] pure planner rejected recipe={} status={} feasibility={} missing={} states={} backtracks={} memoHits={} directReservationAttempted=true",
                                recipeId, result.status(), result.feasibility(), result.missing(),
                                result.expandedStates(), result.backtracks(), result.memoHits());
                        if (shouldRetryTypedPlanning(
                                result, snapshot.bindingBlockedOutputIds())) {
                            RSIntegrationMod.LOGGER.info(
                                    "[RSI-exec] retrying typed execution after pure planner rejection recipe={}",
                                    recipeId);
                            tryResolveTypedFallbackOnly(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                    inferMode, baseItem, targetOutput, outputDestination,
                                    machineSelectionMode, storageReference, Map.of());
                            return;
                        }
                        String key = missingTouchesBlockedOutput(
                                result.missing(), snapshot.bindingBlockedOutputIds())
                                ? "rsi.plan.failure.no_bound_machine"
                                : purePlanningFailureKey(result, snapshot.availableItems());
                        player.sendSystemMessage(Component.translatable(key));
                        return;
                    }
                    if (!PlanningStateValidator.revalidateForExecution(player, snapshot,
                            net.minecraft.resources.ResourceKey.create(
                                    net.minecraft.core.registries.Registries.DIMENSION, effectiveDim),
                            effectivePos, selectedReference)) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.plan.failure.missing_materials"));
                        return;
                    }
                    List<ResolutionStep> steps = new ArrayList<>(PurePlanAdapter.toResolutionSteps(
                            result, snapshot.recipeGraph()));
                    steps.add(genericTerminalStep(recipeId, repeatCount));
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-exec] async pure plan ready recipe={} steps={} states={}",
                            recipeId, steps.size(), result.expandedStates());
                    launchEndpointAsyncChain(player, steps,
                            LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD,
                            storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dim, pos, inferMode, baseItem, targetOutput,
                            outputDestination, machineSelectionMode);
                }, failure -> {
                    if (!(failure instanceof java.util.concurrent.CancellationException)) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] async pure planning failed recipe={} directReservationAttempted=true failure={}",
                                recipeId, failure.toString(), failure);
                        String key = failure instanceof CraftingPlanningTimeoutException
                                ? "rsi.plan.failure.complexity_limit"
                                : "rsi.plan.failure.missing_materials";
                        player.sendSystemMessage(Component.translatable(key));
                    }
                });
        RSIntegrationMod.debug("[RSI-exec] queued async pure planning recipe={} repeat={}",
                recipeId, repeatCount);
        return true;
    }

    /**
     * Background fallback for a machine terminal whose intermediate resolver
     * exceeded the server-thread slice. The synthetic root is planning-only;
     * execution still appends the typed machine terminal and therefore cannot
     * bypass Goety/Ars/other delegate validation.
     */
    private static boolean queuePureExecutionPlanForPhysicalRecipe(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            Map<String, String> forcedRecipes, @Nullable ResourceLocation dim,
            @Nullable net.minecraft.core.BlockPos pos, int repeatCount, boolean inferMode,
            @Nullable ItemStack baseItem, @Nullable ItemStack targetOutput,
            OutputDestination outputDestination, MachineSelectionMode machineSelectionMode,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable StorageReference storageReference, List<IngredientSpec> scaledSpecs,
            ItemStack recipeOutput, ModType terminalType,
            Map<String, ItemStack> materialLocks) {
        if (storageEndpoint == null || !forcedRecipes.isEmpty()
                || !RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()
                || recipeOutput == null || recipeOutput.isEmpty()) return false;

        final BindingAwareGraph bindingGraph;
        try {
            bindingGraph = bindingAwareGraph(player,
                    ImmutableRecipeGraphProjector.capture(player.serverLevel()));
        } catch (RuntimeException unavailable) {
            RSIntegrationMod.debug("[RSI-exec] immutable graph unavailable for physical recipe {}: {}",
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

        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected =
                new HashMap<>();
        baseGraph.recipesByOutput().forEach((key, value) ->
                projected.put(key, new ArrayList<>(value)));
        ImmutableRecipeGraph.MaterialRef outputRef = ImmutableRecipeGraphProjector.material(
                recipeOutput, recipeOutput.hasTag());
        ImmutableRecipeGraph.RecipeNode syntheticRoot = new ImmutableRecipeGraph.RecipeNode(
                recipeId, outputRef, Math.max(1, recipeOutput.getCount()), inputs);
        projected.computeIfAbsent(outputRef, ignored -> new ArrayList<>()).add(syntheticRoot);
        ImmutableRecipeGraph planningGraph = new ImmutableRecipeGraph(projected);

        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, storageEndpoint);
        var routeInputs = new AsyncPurePlanningService.RouteInputs(
                routingAvailability(available), RSIntegrationConfig.CRAFTING_PURE_DEMAND_MAX_NODES.get(),
                RSIntegrationConfig.ENABLE_CATALYST_RECIPE_PREFERENCE.get()
                        ? RecipeIndex.reusableCatalystOutputIds(player.serverLevel()) : Set.of(),
                RecipeIndex.reusableCatalystRecipeIds(player.serverLevel()),
                RecipeIndex.pureIncompatibleOutputIds(player.serverLevel()));
        ResourceLocation effectiveDim = dim != null
                ? dim : player.serverLevel().dimension().location();
        net.minecraft.core.BlockPos effectivePos = pos != null ? pos : player.blockPosition();
        StorageReference selectedReference = storageEndpoint.session().reference();
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(
                player.getUUID(), 0L, recipeId, available, Map.of(), planningGraph,
                PlanningStateValidator.networkFingerprint(selectedReference, available),
                PlanningStateValidator.bindingFingerprint(player,
                        ResourceKey.create(Registries.DIMENSION, effectiveDim), effectivePos),
                bindingGraph.blockedOutputIds(), false);

        // The synthetic root represents the physical terminal recipe. Plan every
        // requested execution here so its recursively-produced inputs match the
        // terminal step appended below; planning one root left later batches
        // waiting on intermediates that were never scheduled.
        PLAN_REQUESTS.submitRouted(snapshot, routeInputs, Math.max(1, repeatCount),
                player.getServer()::execute, RSIntegrationConfig.CRAFTING_MAX_STEPS.get(), completed -> {
                    PureDemandTreeInspector.Result route = completed.inspection();
                    if (!route.backgroundCompatible() || completed.plan() == null) {
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-exec] immutable physical route unavailable recipe={} status={} unresolved={} catalystRoute={}",
                                recipeId, route.status(), route.unresolved(), route.catalystRouteAvailable());
                        tryResolveTypedFallbackOnly(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                inferMode, baseItem, targetOutput, outputDestination,
                                machineSelectionMode, storageReference, materialLocks);
                        return;
                    }
                    PureRecipePlanner.Result result = completed.plan();
                    if (!result.feasible()) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] physical planner rejected recipe={} status={} feasibility={} missing={} states={} backtracks={} memoHits={} directReservationAttempted=true",
                                recipeId, result.status(), result.feasibility(), result.missing(),
                                result.expandedStates(), result.backtracks(), result.memoHits());
                        if (shouldRetryTypedPlanning(
                                result, snapshot.bindingBlockedOutputIds())) {
                            RSIntegrationMod.LOGGER.info(
                                    "[RSI-exec] retrying typed physical execution after pure planner rejection recipe={}",
                                    recipeId);
                            tryResolveTypedFallbackOnly(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                    inferMode, baseItem, targetOutput, outputDestination,
                                    machineSelectionMode, storageReference, materialLocks);
                            return;
                        }
                        String key = missingTouchesBlockedOutput(
                                result.missing(), snapshot.bindingBlockedOutputIds())
                                ? "rsi.plan.failure.no_bound_machine"
                                : purePlanningFailureKey(result, snapshot.availableItems());
                        player.sendSystemMessage(Component.translatable(key));
                        return;
                    }
                    if (!PlanningStateValidator.revalidateForExecution(player, snapshot,
                            ResourceKey.create(Registries.DIMENSION, effectiveDim), effectivePos,
                            selectedReference)) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.plan.failure.missing_materials"));
                        return;
                    }
                    List<ResolutionStep> steps = new ArrayList<>(
                            PurePlanAdapter.toResolutionSteps(result, planningGraph).stream()
                                    .filter(step -> !step.recipeId().equals(recipeId)).toList());
                    steps.add(backgroundPhysicalTerminalStep(
                            recipeId, terminalType, inferMode, repeatCount));
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-exec] async physical plan ready recipe={} steps={} states={}",
                            recipeId, steps.size(), result.expandedStates());
                    launchEndpointAsyncChain(player, steps,
                            LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD,
                            storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dim, pos, inferMode, baseItem, targetOutput,
                            outputDestination, machineSelectionMode);
                }, failure -> {
                    if (!(failure instanceof CancellationException)) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-exec] async physical planning failed recipe={} directReservationAttempted=true failure={}",
                                recipeId, failure.toString(), failure);
                        String key = failure instanceof CraftingPlanningTimeoutException
                                ? "rsi.plan.failure.complexity_limit"
                                : "rsi.plan.failure.missing_materials";
                        player.sendSystemMessage(Component.translatable(key));
                    }
                });
        RSIntegrationMod.debug("[RSI-exec] queued async physical planning recipe={} repeat={}",
                recipeId, repeatCount);
        return true;
    }

    private static void tickExecutionRequests(MinecraftServer server) {
        DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request = EXECUTION_REQUESTS.poll();
        if (request == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            request.payload().accept(player);
        }
    }

    /** Runs at most one valid deferred request after a complete generation is ready. */
    public static void tickWarmUpRequests(MinecraftServer server) {
        // Runtime recipe drift is handled from the server tick, never from the
        // packet handler, so the first craft request cannot synchronously rebuild
        // the complete recipe catalog on its network task.
        RecipeIndex.refreshDynamicRuntimeIfNeeded(server.overworld());
        tickTypedPreviewRequests(server);
        tickExecutionRequests(server);
        if (RecipeIndex.generationBuildFailed()) {
            // Keep even failure notifications incremental. A full warm-up queue
            // can contain dozens of clicks and draining it here used to create a
            // server-thread burst exactly when the recipe catalog was unhealthy.
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request = WARM_UP_REQUESTS.poll();
            if (request != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                if (player != null) player.sendSystemMessage(Component.translatable(
                        "rsi.plan.failure.catalog_unavailable"));
            }
            return;
        }
        if (!warmUpReady(server.overworld())) return;
        int remaining = WARM_UP_REQUESTS.size();
        while (remaining-- > 0) {
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request =
                    WARM_UP_REQUESTS.poll();
            if (request == null) return;
            ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
            if (player == null) continue;
            if (request.preview()
                    && !PLAN_REQUESTS.isCurrent(request.playerId(), request.generation())) continue;
            request.payload().accept(player);
            return;
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
        TypedPreviewAdmissionQueue queue = TYPED_PREVIEW_REQUESTS;
        int admitted = queue.run(Math.max(1, admissions), System.nanoTime(),
                Math.max(1, timeoutMs) * 1_000_000L, request -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                    return player != null && !player.hasDisconnected()
                            && PLAN_REQUESTS.isCurrent(request.playerId(), request.generation());
                });
        PerformanceMonitor.recordTypedPreviewAdmitted(admitted, queue.size());
    }

    /**
     * Converts common outer-layer failures into stable, translated messages.
     * The complete exception remains in the server log for diagnostics; raw
     * JVM messages are not suitable player-facing text and are often English.
     */
    private static Component buildFailureMessage(Throwable failure, ResourceLocation recipeId) {
        if (containsNetworkNullFailure(failure)) {
            return Component.translatable("rsi.generic.error.network_unavailable");
        }
        if (containsPlanningTimeout(failure)) {
            return Component.translatable("rsi.plan.failure.dynamic_plan_unavailable");
        }
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        return Component.translatable("rsi.generic.error.craft_failed", recipeId + " - " + detail);
    }

    private static boolean containsNetworkNullFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("network is null")
                    || (normalized.contains("getitemstoragetracker") && normalized.contains("null"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPlanningTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CraftingPlanningTimeoutException) return true;
        }
        return false;
    }

    // ── execute: resolve ingredients, craft result, give result to player ──

    // ── FA ritual lookup (delegates to FaRitualHelper) ──────────

    /**
     * Look up a recipe from RecipeManager first, then fall back to
     * FARegistries.RITUAL (wrapping the FA Ritual in a FaRitualWrapper).
     */
    static Recipe<?> resolveRecipe(ServerLevel level, ResourceLocation recipeId) {
        // Strip JEI pagination prefix if present (e.g. mod:jei.real_path -> mod:real_path)
        recipeId = unwrapJeiId(recipeId);
        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe != null) return recipe;

        recipe = BirdcageEggCatalog.resolve(level, recipeId);
        if (recipe != null) return recipe;

        recipe = com.huanghuang.rsintegration.mods.farmersdelight
                .CosmopolitanTisaneRecipeResolver.resolve(recipeId);
        if (recipe != null) return recipe;

        if ("rs_integration".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("vanilla_brewing/")) {
            com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog
                    .ensureBuilt(level);
            recipe = com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog
                    .byId(recipeId);
            if (recipe != null) return recipe;
        }

        // FA ApplyModifierRecipe lives under smithing/ subdirectory in
        // RecipeManager (e.g. forbidden_arcanus:smithing/apply_eternal_modifier)
        // but the JEI fake recipe sends only the synthetic ID
        // (e.g. forbidden_arcanus:apply_eternal_modifier).
        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = level.getRecipeManager().byKey(
                    new ResourceLocation(recipeId.getNamespace(),
                            "smithing/" + recipeId.getPath())).orElse(null);
            if (recipe != null) return recipe;
        }

        // FA rituals live in a separate registry. Avoid probing that optional
        // registry for every ordinary recipe; besides being wasted work, a
        // missing FA installation would otherwise emit a reflection failure
        // on every planning branch.
        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = resolveFARitual(level, recipeId);
            if (recipe != null) return recipe;
        }
        recipe = MarketBatchDelegate.resolveMarketEntry(recipeId);
        if (recipe != null) return recipe;
        recipe = ApotheosisGemCuttingCatalog.byId(recipeId);
        if (recipe != null) return recipe;
        if (net.minecraftforge.fml.ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)) {
            recipe = com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog.byId(recipeId);
            if (recipe != null) return recipe;
        }
        if (net.minecraftforge.fml.ModList.get().isLoaded(ModIds.PMMO)) {
            recipe = com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog.byId(recipeId);
            if (recipe != null) return recipe;
        }
        recipe = CrabTrapRecipeResolver.resolveRecipe(level, recipeId);
        if (recipe != null) return recipe;
        var firon = LithumAltarRecipeResolver.resolve(recipeId);
        if (firon != null && LithumAltarRecipeResolver.isComplete(firon)) {
            return new LithumAltarRecipeWrapper(recipeId, firon);
        }

        // Scan FA rituals directly only for FA IDs (bypasses cache in case of
        // registry-key vs lookup-key mismatch).
        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = FaRitualHelper.resolveFARitualScan(level, recipeId);
            if (recipe != null) return recipe;
        }

        RSIntegrationMod.LOGGER.warn("[RSI-resolveRecipe] All lookups failed for {}", recipeId);
        return null;
    }

    private static Recipe<?> resolveFARitual(ServerLevel level, ResourceLocation recipeId) {
        if (!ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) return null;
        Object ritual = FaRitualHelper.getRitualById(recipeId, level);
        if (ritual == null) {
            RSIntegrationMod.debug("[RSI-Generic] FA ritual not found in registry: {}", recipeId);
            return null;
        }
        return FaRitualHelper.wrapFaRitual(recipeId, ritual);
    }

    /** Returns the blockKey keyword that a machine must contain to be
     *  compatible with the given recipe, or {@code null} for unknown types. */
    private static String getMachineKeywordForRecipe(Recipe<?> recipe) {
        if (recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe) {
            return "smithing_table";
        }
        if (recipe instanceof AbstractCookingRecipe acr) {
            // JEI category is baked into the recipe; use class name hint first
            String cn = recipe.getClass().getName();
            if (cn.contains("Campfire")) return "campfire";
            // Fallback: cooking type field (BLOCK recipes have isBlastFurnace etc.)
            // AbstractCookingRecipe itself doesn't expose the type, so use known subclasses
            if (cn.contains("Blasting")) return "blast_furnace";
            if (cn.contains("Smoking")) return "smoker";
            return "furnace"; // generic furnace
        }
        if (recipe instanceof StonecutterRecipe) {
            return "stonecutter";
        }
        return null; // non-vanilla or unknown — don't filter
    }

    /** Strip JEI pagination prefix from pseudo-IDs like {@code mod:jei.real_path/page}. */
    private static ResourceLocation unwrapJeiId(ResourceLocation id) {
        return com.huanghuang.rsintegration.compat.jei.JeiRecipeIdNormalizer.normalize(id);
    }

    /**
     * Launches a deferred plan without putting Refined Storage's optional
     * {@code INetwork} type in the planner callback descriptor. The JVM resolves
     * every captured invokedynamic type when the callback is created, even when
     * the captured network value is {@code null} for another storage backend.
     */
    private static void launchEndpointAsyncChain(
            ServerPlayer player, List<ResolutionStep> steps,
            LegacyExecutionMetrics.Reason legacyReason,
            CraftStorageEndpoint storageEndpoint, int repeatCount,
            ResourceLocation recipeId, Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dim, @Nullable net.minecraft.core.BlockPos pos,
            boolean inferMode, @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode) {
        if ("refinedstorage".equals(
                storageEndpoint.session().reference().backendId().value())) {
            launchAsyncChain(player, steps, legacyReason,
                    CraftStorageEndpoints.legacyNetwork(storageEndpoint), storageEndpoint,
                    repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem,
                    targetOutput, outputDestination, machineSelectionMode);
            return;
        }
        launchAsyncChain(player, steps, legacyReason, null, storageEndpoint,
                repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem,
                targetOutput, outputDestination, machineSelectionMode);
    }

    /** Launch an async craft chain with standard onDone/scheduleNext wiring. */
    private static void launchAsyncChain(ServerPlayer player, List<ResolutionStep> steps,
                                          LegacyExecutionMetrics.Reason legacyReason,
                                          INetwork network, int repeatCount,
                                          ResourceLocation recipeId,
                                          Map<String, String> forcedRecipes,
                                          @Nullable ResourceLocation dim,
                                          @Nullable net.minecraft.core.BlockPos pos,
                                          boolean inferMode, @Nullable ItemStack baseItem,
                                          @Nullable ItemStack targetOutput,
                                          OutputDestination outputDestination,
                                          MachineSelectionMode machineSelectionMode) {
        launchAsyncChain(player, steps, legacyReason, network, null, repeatCount, recipeId,
                forcedRecipes, dim, pos, inferMode, baseItem, targetOutput,
                outputDestination, machineSelectionMode);
    }

    private static boolean tryPrepareIntermediateMaterials(
            ServerPlayer player, ResourceLocation recipeId, Recipe<?> recipe,
            @Nullable ModType modType, List<IngredientSpec> specs, ItemStack recipeOutput,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            @Nullable INetwork network, CraftStorageEndpoint storageEndpoint,
            MachineSelectionMode machineSelectionMode,
            @Nullable ResourceLocation dim, @Nullable net.minecraft.core.BlockPos pos,
            Map<String, ItemStack> materialLocks) {
        Map<StackKey, Integer> available = listAvailable(player, network, storageEndpoint);
        List<String> missing = new ArrayList<>();
        CraftPlanGraph graph;
        try {
            graph = isPhysicalMachineRecipe(recipe, modType)
                    ? CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                            specs, available, player.serverLevel(), player, network, missing,
                            forcedOverrides, true, compatibilityResolverBudgetMs(),
                            new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks)
                    : CraftingResolver.resolveGraphForSpecsWithTypes(
                            specs, available, player.serverLevel(), player, network, missing,
                            forcedOverrides, true, compatibilityResolverBudgetMs(),
                            new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks);
        } catch (CraftingPlanningTimeoutException timeout) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.preparation.failed", recipeId.toString()));
            return false;
        }

        Component unresolvedIntermediate = summarizeUnresolvedInputs(graph);
        Component stillMissing = summarizePreparationMissing(graph);
        if (stillMissing == null && !missing.isEmpty()) {
            stillMissing = CraftPacketUtils.formatMissingSummary(missing);
        }
        CraftPlanGraph executableGraph = pruneBlockedPreparationGraph(graph);
        if (!isPreparationGraphExecutable(executableGraph)) {
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
        Component products = summarizePreparedOutputs(executableGraph);
        if (products == null) products = Component.translatable("rsi.plan.unknown_item");
        if (stillMissing == null) stillMissing = Component.literal("-");
        launchPreparationGraphAsyncChain(player, executableGraph, recipeId, network, storageEndpoint,
                dim, pos, machineSelectionMode, products, stillMissing);
        return true;
    }

    static boolean isPreparationGraphExecutable(CraftPlanGraph graph) {
        return graph != null && !graph.nodes().isEmpty()
                && graph.unresolvedDemands().isEmpty();
    }

    /**
     * Removes nodes that cannot run and every downstream consumer that depends on them.
     * Independent upstream branches remain useful preparation products, including producers
     * whose output was originally destined for a now-blocked consumer.
     */
    @Nullable
    static CraftPlanGraph pruneBlockedPreparationGraph(CraftPlanGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) return null;
        if (graph.unresolvedDemands().isEmpty()) return graph;

        Set<NodeId> blocked = graph.unresolvedDemands().stream()
                .map(unresolved -> unresolved.consumer().nodeId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed;
        do {
            changed = false;
            for (var allocation : graph.allocations()) {
                if (!(allocation.source() instanceof MaterialSource.ProducerOutput producer)) continue;
                if (blocked.contains(producer.outputPort().nodeId())
                        && blocked.add(allocation.consumer().nodeId())) {
                    changed = true;
                }
            }
        } while (changed);

        Set<NodeId> retained = graph.nodes().stream()
                .map(CraftNode::id)
                .filter(nodeId -> !blocked.contains(nodeId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (retained.isEmpty()) return null;

        List<CraftNode> nodes = graph.nodes().stream()
                .filter(node -> retained.contains(node.id()))
                .toList();
        List<com.huanghuang.rsintegration.crafting.graph.MaterialAllocation> allocations =
                graph.allocations().stream()
                        .filter(allocation -> retained.contains(allocation.consumer().nodeId()))
                        .filter(allocation -> !(allocation.source()
                                instanceof MaterialSource.ProducerOutput producer)
                                || retained.contains(producer.outputPort().nodeId()))
                        .toList();
        List<NodeId> topologicalOrder = graph.topologicalOrder().stream()
                .filter(retained::contains)
                .toList();

        Map<OutputPortId, Integer> internallyConsumed = new HashMap<>();
        for (var allocation : allocations) {
            if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                internallyConsumed.merge(producer.outputPort(), allocation.quantity(), Integer::sum);
            }
        }
        List<RootDemand> roots = new ArrayList<>();
        for (CraftNode node : nodes) {
            for (var output : node.outputs()) {
                int deliverable = output.quantity()
                        - internallyConsumed.getOrDefault(output.id(), 0);
                if (deliverable <= 0) continue;
                ItemStack display = output.material().toStack(1);
                roots.add(new RootDemand(Ingredient.of(display), deliverable, 0, display,
                        List.of(new RootAllocation(
                                new MaterialSource.ProducerOutput(output.id()),
                                output.material(), deliverable))));
            }
        }
        if (roots.isEmpty()) return null;

        CraftPlanGraph pruned = new CraftPlanGraph(graph.version(), nodes, allocations,
                roots, List.of(), topologicalOrder, graph.planningRevision());
        CraftPlanValidator.validate(pruned);
        return pruned;
    }

    @Nullable
    private static Component summarizePreparedOutputs(CraftPlanGraph graph) {
        Map<OutputPortId, Integer> consumed = new HashMap<>();
        for (var allocation : graph.allocations()) {
            if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                consumed.merge(producer.outputPort(), allocation.quantity(), Integer::sum);
            }
        }
        Map<MaterialKey, Integer> totals = new LinkedHashMap<>();
        for (CraftNode node : graph.nodes()) {
            for (var output : node.outputs()) {
                int returned = output.quantity() - consumed.getOrDefault(output.id(), 0);
                if (returned > 0) totals.merge(output.material(), returned, Integer::sum);
            }
        }
        return formatMaterialTotals(totals);
    }

    @Nullable
    private static Component summarizeUnresolvedRoots(CraftPlanGraph graph) {
        Map<MaterialKey, Integer> totals = new LinkedHashMap<>();
        for (var root : graph.rootDemands()) {
            if (root.unresolvedQuantity() <= 0) continue;
            ItemStack display = root.displayHint();
            if (display.isEmpty()) continue;
            totals.merge(MaterialKey.of(display), root.unresolvedQuantity(), Integer::sum);
        }
        return formatMaterialTotals(totals);
    }

    @Nullable
    private static Component summarizePreparationMissing(CraftPlanGraph graph) {
        Map<MaterialKey, Integer> totals = new LinkedHashMap<>();
        for (var unresolved : graph.unresolvedDemands()) {
            ItemStack display = unresolved.displayHint();
            if (!display.isEmpty()) {
                totals.merge(MaterialKey.of(display), unresolved.quantity(), Integer::sum);
            }
        }
        for (var root : graph.rootDemands()) {
            if (root.unresolvedQuantity() <= 0) continue;
            ItemStack display = root.displayHint();
            if (!display.isEmpty()) {
                totals.merge(MaterialKey.of(display), root.unresolvedQuantity(), Integer::sum);
            }
        }
        return formatMaterialTotals(totals);
    }

    @Nullable
    private static Component summarizeUnresolvedInputs(CraftPlanGraph graph) {
        Map<MaterialKey, Integer> totals = new LinkedHashMap<>();
        for (var unresolved : graph.unresolvedDemands()) {
            ItemStack display = unresolved.displayHint();
            if (display.isEmpty()) continue;
            totals.merge(MaterialKey.of(display), unresolved.quantity(), Integer::sum);
        }
        return formatMaterialTotals(totals);
    }

    @Nullable
    private static Component formatMaterialTotals(Map<MaterialKey, Integer> totals) {
        if (totals.isEmpty()) return null;
        var summary = Component.empty();
        int index = 0;
        for (Map.Entry<MaterialKey, Integer> entry : totals.entrySet()) {
            if (index++ > 0) summary.append(Component.literal("、"));
            summary.append(entry.getKey().toStack(entry.getValue()).getHoverName())
                    .append(Component.literal(" x" + entry.getValue()));
        }
        return summary;
    }

    private static void launchPreparationGraphAsyncChain(
            ServerPlayer player, CraftPlanGraph graph, ResourceLocation recipeId,
            @Nullable INetwork network, CraftStorageEndpoint storageEndpoint,
            @Nullable ResourceLocation dim,
            @Nullable net.minecraft.core.BlockPos pos, MachineSelectionMode machineSelectionMode,
            Component products, Component stillMissing) {
        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network,
                storageEndpoint, graph);
        chain.setOutputDestination(OutputDestination.RS_NETWORK);
        chain.setMachineSelection(recipeId, machineSelectionMode, dim, pos);
        UUID playerId = player.getUUID();
        MinecraftServer server = player.getServer();
        chain.onDone(() -> {
            ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(playerId);
            if (online == null) return;
            online.sendSystemMessage(Component.translatable(
                    chain.state() == AsyncCraftChain.State.COMPLETED
                            ? "rsi.generic.preparation.completed"
                            : "rsi.generic.preparation.failed_detail",
                    products, stillMissing));
        });
        AsyncCraftManager.getInstance().submit(chain);
        player.sendSystemMessage(Component.translatable(
                "rsi.generic.preparation.started", products, stillMissing));
    }

    private static void launchAsyncChain(ServerPlayer player, List<ResolutionStep> steps,
                                          LegacyExecutionMetrics.Reason legacyReason,
                                          @Nullable INetwork network,
                                          @Nullable CraftStorageEndpoint storageEndpoint,
                                          int repeatCount,
                                          ResourceLocation recipeId,
                                          Map<String, String> forcedRecipes,
                                          @Nullable ResourceLocation dim,
                                          @Nullable net.minecraft.core.BlockPos pos,
                                          boolean inferMode, @Nullable ItemStack baseItem,
                                          @Nullable ItemStack targetOutput,
                                          OutputDestination outputDestination,
                                          MachineSelectionMode machineSelectionMode) {
        // Resolver steps already contain the total physical execution count for
        // the requested repeats; do not multiply intermediate mod steps here.
        final UUID capturedUuid = player.getUUID();
        final var capturedServer = player.getServer();
        final StorageReference repeatReference = storageEndpoint == null
                ? null : storageEndpoint.session().reference();
        // The resolver already expanded all intermediate executions.
        final int effectiveRepeat = 1;
        LegacyFlatExecutionService.launch(player, network, storageEndpoint, steps, legacyReason, recipeId,
                targetOutput, outputDestination, runningChain -> {
                    runningChain.setMachineSelection(recipeId, machineSelectionMode, dim, pos);
                    runningChain.onDone(() ->
                        ChainRepeatController.scheduleNext(
                                runningChain, capturedServer, capturedUuid, effectiveRepeat,
                                runningChain.getMachineCount(),
                                (p, rem) -> queueExecutionRequest(p,
                                        next -> tryResolve(next, recipeId, forcedRecipes, dim, pos, rem,
                                                inferMode, baseItem, targetOutput, outputDestination,
                                                machineSelectionMode, repeatReference), recipeId)));
                });
    }

    private static void launchGraphAsyncChain(ServerPlayer player, CraftPlanGraph graph,
                                               ResolutionStep terminalStep, INetwork network,
                                               int repeatCount, ResourceLocation recipeId,
                                               Map<String, String> forcedRecipes,
                                               @Nullable ResourceLocation dim,
                                               @Nullable net.minecraft.core.BlockPos pos,
                                               boolean inferMode, @Nullable ItemStack baseItem,
                                               @Nullable ItemStack targetOutput,
                                               OutputDestination outputDestination,
                                               MachineSelectionMode machineSelectionMode) {
        launchGraphAsyncChain(player, graph, terminalStep, network, null, repeatCount, recipeId,
                forcedRecipes, dim, pos, inferMode, baseItem, targetOutput,
                outputDestination, machineSelectionMode);
    }

    private static void launchGraphAsyncChain(ServerPlayer player, CraftPlanGraph graph,
                                               ResolutionStep terminalStep, @Nullable INetwork network,
                                               @Nullable CraftStorageEndpoint storageEndpoint,
                                               int repeatCount, ResourceLocation recipeId,
                                               Map<String, String> forcedRecipes,
                                               @Nullable ResourceLocation dim,
                                               @Nullable net.minecraft.core.BlockPos pos,
                                               boolean inferMode, @Nullable ItemStack baseItem,
                                               @Nullable ItemStack targetOutput,
                                               OutputDestination outputDestination,
                                               MachineSelectionMode machineSelectionMode) {
        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network,
                storageEndpoint, graph);
        if (!chain.isGraphExecution()) {
            ModType legacyType = graph.nodes().stream()
                    .map(node -> ModType.byId(node.modTypeId()))
                    .filter(type -> type.flatExecutionReason() != null)
                    .findFirst().orElse(terminalStep.modType());
            LegacyExecutionMetrics.record(
                    LegacyExecutionMetrics.Reason.MOD_REQUIRES_FLAT_EXECUTION,
                    recipeId, legacyType);
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] launch craftId={} graphNodes={} terminalRecipe={} executor={} terminalEmbedded=true",
                chain.getCraftId(), graph.topologicalOrder().size(), terminalStep.recipeId(),
                chain.isGraphExecution() ? "graph" : "flat");
        chain.setTargetOutput(targetOutput);
        chain.setOutputDestination(outputDestination);
        chain.setMachineSelection(recipeId, machineSelectionMode, dim, pos);
        UUID playerId = player.getUUID();
        var server = player.getServer();
        final StorageReference repeatReference = storageEndpoint == null
                ? null : storageEndpoint.session().reference();
        AsyncCraftManager.getInstance().submit(chain);
        chain.onDone(() -> ChainRepeatController.scheduleNext(
                chain, server, playerId, 1, chain.getMachineCount(),
                (p, rem) -> queueExecutionRequest(p,
                        next -> tryResolve(next, recipeId, forcedRecipes, dim, pos, rem,
                                inferMode, baseItem, targetOutput, outputDestination,
                                machineSelectionMode, repeatReference), recipeId)));
        player.sendSystemMessage(TextBuilder.translate(
                outputDestination == OutputDestination.PLAYER_INVENTORY
                        ? "rsi.async.chain_started_player"
                        : "rsi.async.chain_started",
                chain.stepsCount()).build());
    }

    /** Execute a resolver-expanded GENERIC-only chain once. */
    private static boolean executeSyncLoop(ServerPlayer player, List<ResolutionStep> steps,
                                            INetwork network, @Nullable CraftStorageEndpoint storageEndpoint,
                                            ResourceLocation recipeId,
                                            int repeatCount, String failMsg) {
        if (!canExecuteSynchronously(steps)) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-Generic] Refusing synchronous execution of typed machine chain for {}: {}",
                    recipeId, steps.stream().map(step -> step.modType().id()).toList());
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.craft_failed", "physical machine requires async execution"));
            return false;
        }
        if (!CraftPacketUtils.executeCraftingSteps(player, steps, network, storageEndpoint)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps failed for {} (repeatCount={})",
                    recipeId, repeatCount);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", failMsg));
            return false;
        }
        player.sendSystemMessage(Component.translatable(
                "rsi.generic.craft_completed", recipeId.toString(), totalExecutions(steps)));
        return true;
    }

    static int totalExecutions(List<ResolutionStep> steps) {
        long total = 0L;
        for (ResolutionStep step : steps) {
            total += Math.max(1, step.executions());
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    static boolean shouldExecuteGenericChainAsync(List<ResolutionStep> steps) {
        return shouldExecuteGenericChainAsync(
                steps, RSIntegrationConfig.CRAFTING_VANILLA_OPERATIONS_PER_TICK.get());
    }

    static boolean shouldExecuteGenericChainAsync(
            List<ResolutionStep> steps, int syncOperationThreshold) {
        return totalExecutions(steps) > Math.max(0, syncOperationThreshold);
    }

    enum CachedPurePlanExecution {
        SYNCHRONOUS,
        ASYNCHRONOUS,
        REPLAN
    }

    static CachedPurePlanExecution selectCachedPurePlanExecution(
            List<ResolutionStep> steps, boolean hasStorageEndpoint,
            int syncOperationThreshold) {
        if (!canExecuteSynchronously(steps)) {
            return hasStorageEndpoint
                    ? CachedPurePlanExecution.ASYNCHRONOUS
                    : CachedPurePlanExecution.REPLAN;
        }
        return hasStorageEndpoint
                && shouldExecuteGenericChainAsync(steps, syncOperationThreshold)
                ? CachedPurePlanExecution.ASYNCHRONOUS
                : CachedPurePlanExecution.SYNCHRONOUS;
    }

    static boolean canExecuteSynchronously(List<ResolutionStep> steps) {
        return steps.stream().allMatch(step -> step.modType() == ModType.GENERIC);
    }

    static ResolutionStep genericTerminalStep(ResourceLocation recipeId, int repeatCount) {
        return new ResolutionStep(recipeId, ModType.GENERIC,
                new ResourceLocation("minecraft:crafting"), List.of(), List.of(), false,
                Math.max(1, repeatCount));
    }

    static List<ResolutionStep> genericExecutionSteps(
            List<ResolutionStep> intermediateSteps, ResourceLocation recipeId, int repeatCount) {
        List<ResolutionStep> executionSteps = new ArrayList<>(intermediateSteps);
        executionSteps.add(genericTerminalStep(recipeId, repeatCount));
        return List.copyOf(executionSteps);
    }

    static ResolutionStep smithingTerminalStep(ResourceLocation recipeId, int repeatCount) {
        // The request is binding-gated before the chain starts. Keeping this
        // terminal generic lets it assemble from freshly produced inputs.
        return new ResolutionStep(recipeId, ModType.GENERIC,
                new ResourceLocation("minecraft:smithing"), List.of(), List.of(), false,
                Math.max(1, repeatCount));
    }

    static List<ResolutionStep> smithingAsyncSteps(
            List<ResolutionStep> intermediateSteps, ResourceLocation recipeId, int repeatCount) {
        if (intermediateSteps.stream().allMatch(step -> step.modType() == ModType.GENERIC)) {
            return List.of();
        }
        List<ResolutionStep> chain = new ArrayList<>(intermediateSteps);
        chain.add(smithingTerminalStep(recipeId, repeatCount));
        return List.copyOf(chain);
    }

    static ResolutionStep backgroundPhysicalTerminalStep(
            ResourceLocation recipeId, ModType terminalType,
            boolean inferMode, int repeatCount) {
        return terminalType == ModType.byId("smithing")
                ? smithingTerminalStep(recipeId, repeatCount)
                : new ResolutionStep(recipeId, terminalType, recipeId,
                        List.of(), List.of(), inferMode, Math.max(1, repeatCount));
    }

    private static CraftPlanGraph composeEquivalentTerminalGraph(
            CraftPlanGraph inputGraph, ResolutionStep terminalStep, ItemStack output) {
        CraftPlanGraph completeGraph = TerminalGraphComposer.compose(
                inputGraph, terminalStep, output);
        List<ResolutionStep> expectedFlat = new ArrayList<>(
                ExecutionEquivalence.projectFlatSteps(inputGraph));
        expectedFlat.add(terminalStep);
        ExecutionEquivalence.Report equivalence =
                ExecutionEquivalence.compare(completeGraph, expectedFlat);
        if (!equivalence.equivalent()) {
            throw new IllegalArgumentException(
                    "terminal graph execution mismatch: " + equivalence.mismatches());
        }
        return completeGraph;
    }

    static ItemStack selectTerminalGraphOutput(Recipe<?> recipe, ItemStack recipeOutput,
                                               @Nullable ItemStack clickedOutput) {
        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        return selectTerminalGraphOutput(recipe, recipeOutput, clickedOutput, handler);
    }

    static ItemStack selectTerminalGraphOutput(Recipe<?> recipe, ItemStack recipeOutput,
                                               @Nullable ItemStack clickedOutput,
                                               @Nullable ModRecipeHandler handler) {
        boolean runtimeNbt = handler != null && handler.hasRuntimeDependentPrimaryNbt(recipe);
        ItemStack declared = runtimeNbt
                ? itemOnlyDeclaration(recipeOutput)
                : CraftingResolver.resolveDeclaredOutput(recipe, recipeOutput);
        if (declared.isEmpty() || clickedOutput == null || clickedOutput.isEmpty()
                || clickedOutput.getItem() != declared.getItem() || runtimeNbt) {
            logTerminalOutputSelection(recipe, handler, runtimeNbt, recipeOutput,
                    clickedOutput, declared, false);
            return declared;
        }
        boolean useClicked = handler != null
                ? handler.useClickedPrimaryOutput(recipe, declared, clickedOutput)
                : declared.hasTag() && clickedOutput.hasTag();
        ItemStack selected = useClicked
                ? clickedOutput.copyWithCount(declared.getCount())
                : declared;
        logTerminalOutputSelection(recipe, handler, runtimeNbt, recipeOutput,
                clickedOutput, selected, useClicked);
        return selected;
    }

    private static ItemStack itemOnlyDeclaration(ItemStack output) {
        return output == null || output.isEmpty()
                ? ItemStack.EMPTY
                : new ItemStack(output.getItem(), output.getCount());
    }

    private static void logTerminalOutputSelection(
            Recipe<?> recipe, @Nullable ModRecipeHandler handler, boolean runtimeNbt,
            ItemStack recipeOutput, @Nullable ItemStack clickedOutput,
            ItemStack selected, boolean useClicked) {
        if (!"goety".equals(recipe.getId().getNamespace())) return;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-GraphOutput] recipe={} handler={} runtimeNbt={} useClicked={} "
                        + "recipeTag={} clickedTag={} selectedTag={}",
                recipe.getId(), handler == null ? "none" : handler.getClass().getSimpleName(),
                runtimeNbt, useClicked, recipeOutput.getTag(),
                clickedOutput == null ? null : clickedOutput.getTag(), selected.getTag());
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable net.minecraft.core.BlockPos pos,
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
                                   @Nullable net.minecraft.core.BlockPos pos,
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
                                   @Nullable net.minecraft.core.BlockPos pos,
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

    private static void tryResolveTypedFallbackOnly(
                                   ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable net.minecraft.core.BlockPos pos,
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
                                   @Nullable net.minecraft.core.BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput,
                                   OutputDestination outputDestination,
                                   MachineSelectionMode machineSelectionMode,
                                   @Nullable StorageReference storageReference,
                                   Map<String, ItemStack> materialLocks,
                                   boolean typedFallbackOnly,
                                   boolean partialPreparation) {
        // v3.4: convert forced recipe overrides for the resolver (same format as tryBuildPlan).
        Map<ResourceLocation, ResourceLocation> forcedOverrides = null;
        if (!forcedRecipes.isEmpty()) {
            forcedOverrides = new HashMap<>();
            for (var e : forcedRecipes.entrySet()) {
                ResourceLocation itemKey = ResourceLocation.tryParse(e.getKey());
                ResourceLocation forcedId = ResourceLocation.tryParse(e.getValue());
                if (itemKey == null || forcedId == null
                        || (!CraftingResolver.isStackPreferenceKey(itemKey)
                        && !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemKey))
                        || resolveRecipe(player.serverLevel(), forcedId) == null) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.invalid_forced_recipe",
                            String.valueOf(e.getKey()), String.valueOf(e.getValue())));
                    return;
                }
                forcedOverrides.put(itemKey, forcedId);
            }
        }

        Recipe<?> recipe = resolveRecipe(player.serverLevel(), recipeId);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !CraftPacketUtils.isCraftingRecipeAvailable(craftingRecipe, player)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_stage_missing"));
            return;
        }
        ModType modType = resolveExecutionModType(player, recipe, dim, pos);

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

        if (repeatCount > 1
                && com.huanghuang.rsintegration.recipe.GoetyRecipeHandler
                .requiresManualConfirmation(recipe)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.goety.error.manual_single_only"));
            return;
        }
        if (requiresBoundMachine(recipe, modType)
                && !hasBindingForExecutionType(player, recipe, modType)) {
            logBindingRejection("execute-entry", player, recipe, modType, dim, pos);
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.no_bound_machine", modType.id()));
            return;
        }
        if (modType != null && modType.isVirtual()) {
            ModRecipeHandler virtualHandler = ModRecipeHandlers.handlerFor(recipe);
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
        INetwork network = hasRefinedStorage()
                && (storageReference == null
                || "refinedstorage".equals(storageReference.backendId().value()))
                && (contextEndpoint == null
                || !"beyonddimensions".equals(contextEndpoint.session().reference().backendId().value()))
                ? resolveNetworkForRecipe(player, dim, pos, modType) : null;
        CraftStorageEndpoint storageEndpoint = null;
        // A backend-qualified reference is authoritative for non-RS backends.
        if (storageReference != null && !"refinedstorage".equals(storageReference.backendId().value())) {
            network = null;
        }
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
        } else if (network != null) {
            storageEndpoint = CraftStorageEndpoints.fromLegacyNetwork(network);
        } else {
            // Keep execution and planning symmetric when the terminal context
            // is absent from the packet. The registry resolver still applies
            // backend order and permissions; it does not blindly select BD.
            var fallback = contextEndpoint != null
                    ? java.util.Optional.of(contextEndpoint)
                    : CraftStorageEndpoints.resolveDefault(player);
            if (fallback.isPresent()) {
                storageEndpoint = fallback.orElseThrow();
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage] execution resolved default endpoint backend={} id={}",
                        storageEndpoint.session().reference().backendId(),
                        storageEndpoint.session().reference().networkId());
            }
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ExecAvail] recipe={} preparation={} network={} endpoint={} reference={} destination={}",
                recipeId, partialPreparation, network != null,
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
        // through to the grouped-extraction fallback which bypasses the
        // machine entirely and may fail or give results for free.
        ResourceLocation effectiveDim = dim;
        net.minecraft.core.BlockPos effectivePos = pos;
        if ((effectiveDim == null || effectivePos == null) && modType != null && modType.isVirtual()) {
            effectiveDim = player.level().dimension().location();
            effectivePos = player.blockPosition();
        } else if ((effectiveDim == null || effectivePos == null)
                && isPhysicalMachineRecipe(recipe, modType)) {
            String reqKeyword = getMachineKeywordForRecipe(recipe);
            for (var m : AltarBindingRegistry
                    .getBoundMachinesForRecipe(player, modType, recipeId)) {
                if (reqKeyword != null && m.blockKey() != null && !m.blockKey().contains(reqKeyword))
                    continue;
                effectiveDim = m.dim();
                effectivePos = m.pos();
                if (storageEndpoint == null && network == null && (storageReference == null
                        || "refinedstorage".equals(storageReference.backendId().value()))) {
                    if (hasRefinedStorage()) {
                        network = resolveNetworkForRecipe(player, effectiveDim, effectivePos, modType);
                    }
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
            ItemStack preparationOutput = routeRecipeOutput(modType,
                    ModRecipeHandlers.tryGetResultItem(
                            recipe, player.serverLevel().registryAccess()));
            List<IngredientSpec> preparationSpecs = scaleTerminalIngredientSpecs(
                    recipe, specs, preparationOutput, repeatCount);
            if (preparationSpecs.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.preparation.none", recipeId.toString(), "-"));
                return;
            }
            if (!tryPrepareIntermediateMaterials(player, recipeId, recipe, modType,
                    preparationSpecs, preparationOutput, forcedOverrides, network,
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
        // the terminal may fall through to the direct-extraction compatibility loop.
        if (requiresTypedTerminalExecution(recipe, modType)
                && effectiveDim != null && effectivePos != null
                && modType != ModType.byId("smithing")) {
            if (modType != null) {
                List<IngredientSpec> executionSpecs = specs;
                if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                    ServerLevel crockPotLevel = CraftPacketUtils.resolveLevel(
                            player.server, effectiveDim, player);
                    List<IngredientSpec> categorySpecs = CrockPotBatchDelegate.buildCategoryPlanIngredients(
                            recipe, network, crockPotLevel, effectivePos);
                    if (categorySpecs == null || categorySpecs.isEmpty()) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.crockpot.error.food_values"));
                        return;
                    }
                    executionSpecs = categorySpecs;
                }
                // The terminal executes repeatCount times, so its input DAG must
                // cover the complete batch. Otherwise the flat compatibility
                // path produces one intermediate and the second terminal run
                // fails while trying to reserve material that was never planned.
                ItemStack recipeOutput = arsDynamic
                        ? ArsDynamicApparatusRecipe.validatedOutput(recipe, targetOutput)
                        : goetyDynamic
                                ? GoetyDynamicRitualRecipe.validatedOutput(recipe, targetOutput)
                                : ModRecipeHandlers.tryGetResultItem(
                                        recipe, player.serverLevel().registryAccess());
                recipeOutput = routeRecipeOutput(modType, recipeOutput);
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
                        outputDestination, machineSelectionMode, network, storageEndpoint,
                        cachedMachinePlan)) {
                    return;
                }
                List<IngredientSpec> graphSpecs = scaleTerminalIngredientSpecs(
                        recipe, executionSpecs, recipeOutput, repeatCount);
                if (FarmersDelightRecipeHandler.getCuttingBoardToolIngredient(recipe) != null) {
                    graphSpecs = FarmersDelightRecipeHandler
                            .cuttingBoardGraphIngredients(graphSpecs);
                }
                Map<StackKey, Integer> avail = listAvailable(player, network, storageEndpoint);
                logExecutionAvailability(recipeId, avail);
                DirectReservationCheck directReservation = probeDirectTerminalReservation(
                        player, recipeId, graphSpecs, avail, network, storageEndpoint);
                if (directReservation.available()) {
                    ResolutionStep terminalStep = new ResolutionStep(recipeId, modType, recipeId,
                            List.of(), List.of(), inferMode, repeatCount);
                    launchAsyncChain(player, List.of(terminalStep),
                            LegacyExecutionMetrics.Reason.DIRECT_TERMINAL_RESERVATION,
                            network, storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            effectiveDim, effectivePos, inferMode, baseItem, targetOutput,
                            outputDestination, machineSelectionMode);
                    return;
                }
                // GRAPH_SAFE physical recipes are fully represented by immutable values.
                // Queue their dependency planning before touching the legacy server-thread
                // resolver; its short compatibility slice must never reject a normal chain.
                if (!typedFallbackOnly && queuePureExecutionPlanForPhysicalRecipe(player, recipe, recipeId,
                        forcedRecipes, dim, pos, repeatCount, inferMode, baseItem,
                        targetOutput, outputDestination, machineSelectionMode, storageEndpoint,
                        executionStorageReference, graphSpecs, recipeOutput,
                        modType, materialLocks)) {
                    return;
                }
                List<String> missing = new ArrayList<>();
                CraftPlanGraph inputGraph;
                try {
                    inputGraph = usesPhysicalMachineInputSlots(recipe)
                            ? CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                                    graphSpecs, avail, player.serverLevel(), player, network, missing,
                                    forcedOverrides, false, compatibilityResolverBudgetMs(),
                                    new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks)
                            : CraftingResolver.resolveGraphForSpecsWithTypes(
                                    graphSpecs, avail, player.serverLevel(), player, network, missing,
                                    forcedOverrides, false, compatibilityResolverBudgetMs(),
                                    new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks);
                } catch (CraftingPlanningTimeoutException timeout) {
                    if (!typedFallbackOnly && queuePureExecutionPlanForPhysicalRecipe(player, recipe, recipeId,
                            forcedRecipes, dim, pos, repeatCount, inferMode, baseItem,
                            targetOutput, outputDestination, machineSelectionMode, storageEndpoint,
                            executionStorageReference, graphSpecs, recipeOutput,
                            modType, materialLocks)) {
                        return;
                    }
                    throw timeout;
                }
                if (!missing.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                    return;
                }
                ResolutionStep terminalStep = new ResolutionStep(recipeId, modType, recipeId,
                        List.of(), List.of(), inferMode, repeatCount);
                recipeOutput = selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
                ModRecipeHandler recipeHandler = ModRecipeHandlers.handlerFor(recipe);
                boolean deterministicPrimary = recipeHandler == null
                        || recipeHandler.hasDeterministicPrimaryOutput(recipe);
                TerminalGraphExecutionPolicy.Decision graphDecision =
                        TerminalGraphExecutionPolicy.decide(
                                inferMode, !graphSpecs.isEmpty(), recipeOutput,
                                deterministicPrimary,
                                isSelfAmplifyingRecipe(
                                        recipe, player.serverLevel().registryAccess()));
                if (graphDecision.composable()) {
                    try {
                        CraftPlanGraph completeGraph = composeEquivalentTerminalGraph(
                                inputGraph, terminalStep, recipeOutput);
                        launchGraphAsyncChain(player, completeGraph, terminalStep,
                                network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                                inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                        return;
                    } catch (IllegalArgumentException | ArithmeticException exception) {
                        RSIntegrationMod.LOGGER.warn(
                                "[RSI-Craft] terminal graph composition rejected recipe={} reason={}",
                                recipeId, exception.getMessage());
                    }
                }
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Craft] terminal graph fallback recipe={} reason={} repeatCount={} terminalExecutions={}", recipeId,
                        graphDecision.reason(),
                        repeatCount, terminalStep.executions());
                UUID playerId = player.getUUID();
                var server = player.getServer();
                final CraftStorageEndpoint selectedStorageEndpoint = storageEndpoint;
                LegacyFlatExecutionService.launchIncompleteGraph(
                        player, network, storageEndpoint, inputGraph, terminalStep, repeatCount,
                        LegacyExecutionMetrics.fromTerminalDecision(graphDecision.reason()),
                        recipeId, targetOutput, outputDestination,
                        fallback -> {
                            final StorageReference repeatReference = selectedStorageEndpoint == null
                                    ? null : selectedStorageEndpoint.session().reference();
                            fallback.setMachineSelection(recipeId, machineSelectionMode, dim, pos);
                            fallback.onDone(() -> ChainRepeatController.scheduleNext(
                                    fallback, server, playerId, 1, fallback.getMachineCount(),
                                    (p, rem) -> queueExecutionRequest(p,
                                            next -> tryResolve(next, recipeId, forcedRecipes, dim, pos, rem,
                                                    inferMode, baseItem, targetOutput, outputDestination,
                                                    machineSelectionMode, repeatReference), recipeId)));
                        });
                return;
            }
        }

        // Directly stored terminal inputs are authoritative. Recursive planning
        // cannot improve this case and is actively harmful for identity recipes,
        // deep CraftTweaker graphs and reusable catalysts that the pure graph
        // intentionally cannot represent.
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !isPhysicalMachineRecipe(recipe, modType)) {
            ItemStack directOutput = ModRecipeHandlers.tryGetResultItem(
                    recipe, player.serverLevel().registryAccess());
            List<IngredientSpec> directSpecs = scaleTerminalIngredientSpecs(
                    recipe, extractPlanIngredientSpecs(craftingRecipe), directOutput, repeatCount);
            Map<StackKey, Integer> directAvailable = listAvailable(
                    player, network, storageEndpoint);
            DirectReservationCheck directReservation = probeDirectTerminalReservation(
                    player, recipeId, directSpecs, directAvailable, network, storageEndpoint);
            if (directReservation.available()) {
                launchAsyncChain(player, List.of(genericTerminalStep(recipeId, repeatCount)),
                        LegacyExecutionMetrics.Reason.DIRECT_TERMINAL_RESERVATION,
                        network, storageEndpoint, repeatCount, recipeId, forcedRecipes,
                        effectiveDim, effectivePos, inferMode, baseItem, targetOutput,
                        outputDestination, machineSelectionMode);
                return;
            }
            PlanCache.Entry cachedCraftingPlan = findValidatedExecutionPlan(player, recipeId,
                    forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                    executionStorageReference, materialLocks);
            if (cachedCraftingPlan != null
                    && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                    dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, network, storageEndpoint,
                    cachedCraftingPlan)) {
                return;
            }
            // Every projected crafting-table recipe belongs on the immutable worker.
            // The bounded server-thread resolver below is only a compatibility path
            // for recipes the pure graph cannot represent.
            if (!typedFallbackOnly && materialLocks.isEmpty()
                    && queuePureExecutionPlan(player, craftingRecipe, recipeId, forcedRecipes,
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
                && !isPhysicalMachineRecipe(recipe, modType)
                && forcedRecipes.isEmpty() && materialLocks.isEmpty()) {
            ItemStack recipeOutput = cr.getResultItem(
                    player.serverLevel().registryAccess()).copy();
            PlanCache.Entry cachedPreResolved = findValidatedExecutionPlan(player, recipeId,
                    forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                    executionStorageReference, materialLocks);
            if (cachedPreResolved != null
                    && tryExecuteCachedCraftingPlan(player, recipe, recipeId, forcedRecipes,
                    dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, network, storageEndpoint,
                    cachedPreResolved)) {
                return;
            }
            List<IngredientSpec> graphSpecs = scaleTerminalIngredientSpecs(
                    recipe, extractPlanIngredientSpecs(cr), recipeOutput, repeatCount);
            Map<StackKey, Integer> available = listAvailable(player, network, storageEndpoint);
            List<String> graphMissing = new ArrayList<>();
            CraftPlanGraph inputGraph;
            try {
                inputGraph = CraftingResolver.resolveGraphForSpecsWithTypes(
                        graphSpecs, available, player.serverLevel(), player, network,
                        graphMissing, null, false, compatibilityResolverBudgetMs(),
                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks);
            } catch (CraftingPlanningTimeoutException timeout) {
                if (!typedFallbackOnly && queuePureExecutionPlan(player, cr, recipeId, forcedRecipes, dim, pos,
                        repeatCount, inferMode, baseItem, targetOutput, outputDestination,
                        machineSelectionMode, storageEndpoint,
                        executionStorageReference)) {
                    return;
                }
                throw timeout;
            }
            List<ResolutionStep> allSteps = ExecutionEquivalence.projectFlatSteps(inputGraph);
            if (!allSteps.isEmpty() && graphMissing.isEmpty()) {
                boolean legacySyntheticStep = allSteps.stream().anyMatch(
                        step -> step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP));
                boolean needsAsync = outputDestination == OutputDestination.PLAYER_INVENTORY
                        || allSteps.stream().anyMatch(step -> step.modType() != ModType.GENERIC);
                boolean selfAmplifying = isSelfAmplifyingRecipe(
                        recipe, player.serverLevel().registryAccess());
                if (needsAsync && storageEndpoint != null && !legacySyntheticStep) {
                    ResolutionStep terminalStep = genericTerminalStep(recipeId, repeatCount);
                    recipeOutput = selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
                    if (!selfAmplifying) {
                        try {
                            CraftPlanGraph completeGraph = composeEquivalentTerminalGraph(
                                    inputGraph, terminalStep, recipeOutput);
                            launchGraphAsyncChain(player, completeGraph, terminalStep,
                                    network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                                    inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                            return;
                        } catch (IllegalArgumentException | ArithmeticException exception) {
                            RSIntegrationMod.LOGGER.warn(
                                    "[RSI-Craft] pre-resolved terminal graph composition rejected recipe={} reason={}",
                                    recipeId, exception.getMessage());
                        }
                    }
                }
                if (allSteps.stream().anyMatch(s -> s.modType() != ModType.GENERIC
                        || s.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP))) {
                    // Multi-block intermediates → async chain
                    List<ResolutionStep> execSteps = genericExecutionSteps(
                            allSteps, recipeId, repeatCount);
                    if (storageEndpoint == null) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.network_unavailable"));
                        return;
                    }
                    launchAsyncChain(player, execSteps,
                            selfAmplifying
                                    ? LegacyExecutionMetrics.Reason.SELF_AMPLIFYING_TERMINAL
                                    : LegacyExecutionMetrics.rejectedGraphReason(execSteps),
                            network, storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dim, pos, inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                    return;
                }
                // All GENERIC steps → execute sync chain
                List<ResolutionStep> execSteps = genericExecutionSteps(
                        allSteps, recipeId, repeatCount);
                if (storageEndpoint != null && (outputDestination == OutputDestination.PLAYER_INVENTORY
                        || shouldExecuteGenericChainAsync(execSteps))) {
                    launchAsyncChain(player, execSteps,
                            outputDestination == OutputDestination.PLAYER_INVENTORY
                                    ? LegacyExecutionMetrics.Reason.GRAPH_COMPOSITION_REJECTED
                                    : LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD,
                            network, storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dim, pos, inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                    return;
                }
                executeSyncLoop(player, execSteps, network, storageEndpoint, recipeId, repeatCount, "Intermediate crafting failed");
                return;
            }
        }

        // Unified resolution pass: use the same resolver as tryBuildPlan so the
        // execute path sees the same recipe chain the preview showed. Only for
        // CraftingRecipe (the typed resolver works with shaped/shapeless ingredients).
        if (recipe instanceof CraftingRecipe cr2
                && !isPhysicalMachineRecipe(recipe, modType)
                && RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            PlanCache.Entry cachedExecutionPlan = findValidatedExecutionPlan(player, recipeId,
                    forcedRecipes, repeatCount, targetOutput, modType, effectiveDim, effectivePos,
                    executionStorageReference, materialLocks);
            if (cachedExecutionPlan != null && tryExecuteCachedCraftingPlan(player, cr2, recipeId,
                    forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, targetOutput,
                    outputDestination, machineSelectionMode, network, storageEndpoint,
                    cachedExecutionPlan)) {
                return;
            }
            Map<StackKey, Integer> avail = listAvailable(player, network, storageEndpoint);
            logExecutionAvailability(recipeId, avail);
            List<String> missingCheck = new ArrayList<>();
            ItemStack recipeOutput = cr2.getResultItem(
                    player.serverLevel().registryAccess()).copy();
            List<IngredientSpec> scaledSpecs = scaleTerminalIngredientSpecs(
                    recipe, CraftPacketUtils.extractIngredientSpecs(cr2), recipeOutput, repeatCount);
            CraftPlanGraph inputGraph;
            try {
                inputGraph = CraftingResolver.resolveGraphForSpecsWithTypes(
                        scaledSpecs, avail, player.serverLevel(),
                        player, network, missingCheck, forcedOverrides, false,
                        compatibilityResolverBudgetMs(),
                        new CraftingResolver.ActiveRootRecipe(recipeId, recipeOutput), materialLocks);
            } catch (CraftingPlanningTimeoutException timeout) {
                if (!typedFallbackOnly && materialLocks.isEmpty() && queuePureExecutionPlan(
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
                boolean legacySyntheticStep = planSteps.stream().anyMatch(
                        step -> step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP));
                boolean needsAsync = outputDestination == OutputDestination.PLAYER_INVENTORY
                        || planSteps.stream().anyMatch(step -> step.modType() != ModType.GENERIC);
                boolean selfAmplifying = isSelfAmplifyingRecipe(
                        recipe, player.serverLevel().registryAccess());
                List<ResolutionStep> execSteps2 = new ArrayList<>(planSteps);
                ResolutionStep terminalStep = genericTerminalStep(recipeId, repeatCount);
                execSteps2.add(terminalStep);
                if (needsAsync && storageEndpoint != null && !legacySyntheticStep) {
                    recipeOutput = selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
                    if (!selfAmplifying) {
                        try {
                            CraftPlanGraph completeGraph = composeEquivalentTerminalGraph(
                                    inputGraph, terminalStep, recipeOutput);
                            launchGraphAsyncChain(player, completeGraph, terminalStep,
                                    network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                                    inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                            return;
                        } catch (IllegalArgumentException | ArithmeticException exception) {
                            RSIntegrationMod.LOGGER.warn(
                                    "[RSI-Craft] crafting terminal graph composition rejected recipe={} reason={}",
                                    recipeId, exception.getMessage());
                        }
                    }
                }
                if (storageEndpoint != null && (needsAsync || shouldExecuteGenericChainAsync(execSteps2))) {
                    launchAsyncChain(player, execSteps2,
                            needsAsync
                                    ? selfAmplifying
                                    ? LegacyExecutionMetrics.Reason.SELF_AMPLIFYING_TERMINAL
                                    : LegacyExecutionMetrics.rejectedGraphReason(execSteps2)
                                    : LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD,
                            network, storageEndpoint, repeatCount, recipeId, forcedRecipes,
                            dim, pos, inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                    return;
                }
                executeSyncLoop(player, execSteps2, network, storageEndpoint, recipeId, repeatCount, "Intermediate crafting failed");
                return;
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
        if (isPhysicalMachineRecipe(recipe, modType)
                && (effectiveDim == null || effectivePos == null)) {
            logBindingRejection("execute-context", player, recipe, modType,
                    effectiveDim, effectivePos);
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.no_bound_machine", modType.id()));
            return;
        }

        // Re-resolve network in case the top-level resolution failed but
        // ensureMaterialAvailable succeeded via binding/NBT fallback internally.
        // The ledger's NETWORK entries need a valid network for commit extraction.
        if (storageEndpoint == null && network == null && hasRefinedStorage()
                && (storageReference == null
                || "refinedstorage".equals(storageReference.backendId().value()))) {
            network = CraftPacketUtils.resolveNetworkForCraft(player,
                    player.serverLevel().dimension(), player.blockPosition());
        }

        // Resolve intermediate crafting steps for smithing recipes.
        // Smithing skips the CraftingRecipe-only paths above, so intermediates
        // (e.g. diamond from diamond block) would not be crafted otherwise.
        if (modType == ModType.byId("smithing") && storageEndpoint != null
                && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
            ItemStack smithingOutput = ModRecipeHandlers.tryGetResultItem(
                    recipe, player.serverLevel().registryAccess());
            List<IngredientSpec> smithingSpecs = scaleTerminalIngredientSpecs(
                    recipe, specs, smithingOutput, repeatCount);
            if (!smithingSpecs.isEmpty()) {
                Map<StackKey, Integer> avail = listAvailable(player, network, storageEndpoint);
                DirectReservationCheck directSmithing = probeDirectTerminalReservation(
                        player, recipeId, smithingSpecs, avail, network, storageEndpoint);
                if (!directSmithing.available()) {
                    // Smithing inputs are immutable planner data as well. Do not spend the
                    // server-thread compatibility slice before trying the background route.
                    if (!typedFallbackOnly && queuePureExecutionPlanForPhysicalRecipe(
                            player, recipe, recipeId, forcedRecipes, dim, pos, repeatCount,
                            inferMode, baseItem, targetOutput, outputDestination,
                            machineSelectionMode, storageEndpoint,
                            executionStorageReference, smithingSpecs, smithingOutput, modType,
                            materialLocks)) {
                        return;
                    }
                    List<String> missing = new ArrayList<>();
                    CraftPlanGraph smithingInputGraph;
                    try {
                        smithingInputGraph = CraftingResolver.resolveGraphForSpecsWithTypes(
                        smithingSpecs, avail, player.serverLevel(), player, network, missing,
                                forcedOverrides, false, compatibilityResolverBudgetMs(), null, materialLocks);
                    } catch (CraftingPlanningTimeoutException timeout) {
                        if (!typedFallbackOnly && queuePureExecutionPlanForPhysicalRecipe(
                                player, recipe, recipeId, forcedRecipes, dim, pos, repeatCount,
                                inferMode, baseItem, targetOutput, outputDestination,
                                machineSelectionMode, storageEndpoint,
                                executionStorageReference, smithingSpecs, smithingOutput, modType,
                                materialLocks)) {
                            return;
                        }
                        throw timeout;
                    }
                    List<ResolutionStep> interSteps =
                            ExecutionEquivalence.projectFlatSteps(smithingInputGraph);
                    if (interSteps != null && !interSteps.isEmpty() && missing.isEmpty()) {
                    List<ResolutionStep> asyncSteps = smithingAsyncSteps(
                            interSteps, recipeId, repeatCount);
                    if (asyncSteps.isEmpty()) {
                        if (!CraftPacketUtils.executeCraftingSteps(player, interSteps, network, storageEndpoint)) {
                            player.sendSystemMessage(Component.translatable(
                                    "rsi.generic.error.craft_failed", "Smithing intermediate failed"));
                            return;
                        }
                    } else {
                        ResolutionStep terminalStep = asyncSteps.get(asyncSteps.size() - 1);
                        boolean legacySyntheticStep = interSteps.stream().anyMatch(
                                step -> step.recipeId().equals(
                                        CraftingResolver.TAINT_EARTH_HEART_STEP));
                        if (!legacySyntheticStep) {
                            ItemStack recipeOutput = ModRecipeHandlers.tryGetResultItem(
                                    recipe, player.serverLevel().registryAccess());
                            recipeOutput = selectTerminalGraphOutput(recipe, recipeOutput, targetOutput);
                            boolean selfAmplifying = isSelfAmplifyingRecipe(
                                    recipe, player.serverLevel().registryAccess());
                            if (!selfAmplifying) {
                                try {
                                    CraftPlanGraph completeGraph = composeEquivalentTerminalGraph(
                                            smithingInputGraph, terminalStep, recipeOutput);
                                    launchGraphAsyncChain(player, completeGraph, terminalStep,
                                            network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                                            inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
                                    return;
                                } catch (IllegalArgumentException | ArithmeticException exception) {
                                    RSIntegrationMod.LOGGER.warn(
                                            "[RSI-Craft] smithing terminal graph composition rejected recipe={} reason={}",
                                            recipeId, exception.getMessage());
                                }
                            }
                        }
                        launchAsyncChain(player, asyncSteps,
                                isSelfAmplifyingRecipe(
                                        recipe, player.serverLevel().registryAccess())
                                        ? LegacyExecutionMetrics.Reason.SELF_AMPLIFYING_TERMINAL
                                        : LegacyExecutionMetrics.rejectedGraphReason(asyncSteps),
                                network, storageEndpoint, repeatCount, recipeId,
                                forcedRecipes, dim, pos, inferMode, baseItem, targetOutput,
                                outputDestination, machineSelectionMode);
                        return;
                    }
                    }
                }
            }
        }

        if (storageEndpoint != null && recipe instanceof CraftingRecipe && !isPhysicalMachineRecipe(recipe, modType)
                && shouldExecuteGenericChainAsync(
                List.of(genericTerminalStep(recipeId, repeatCount)))) {
            launchAsyncChain(player,
                    List.of(genericTerminalStep(recipeId, repeatCount)),
                    LegacyExecutionMetrics.Reason.PURE_CHAIN_OPERATION_THRESHOLD,
                    network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                    inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
            return;
        }

        // The legacy compatibility loop below is intentionally kept for a
        // single craft because it preserves a few unusual assemble/remainder
        // behaviours.  Never run a large batch synchronously: AsyncCraftChain
        // applies the same per-chain and global operation budgets even when the
        // source is only the player's inventory (no storage endpoint).
        if (repeatCount > 1) {
            ModType terminalType = modType == null ? ModType.GENERIC : modType;
            ResolutionStep terminalStep = new ResolutionStep(
                    recipeId, terminalType, recipeId, List.of(), List.of(), inferMode, repeatCount);
            launchAsyncChain(player, List.of(terminalStep),
                    LegacyExecutionMetrics.Reason.MOD_REQUIRES_FLAT_EXECUTION,
                    network, storageEndpoint, repeatCount, recipeId, forcedRecipes, dim, pos,
                    inferMode, baseItem, targetOutput, outputDestination, machineSelectionMode);
            return;
        }

        long resolvedItems = 0L;
        for (int r = 0; r < repeatCount; r++) {
            List<ItemStack> allExtracted = new ArrayList<>();
            boolean extractionIncomplete = false;

            try (ExtractionLedger ledger = new ExtractionLedger()) {
                if (storageEndpoint != null) ledger.setStorageEndpoint(storageEndpoint);
                // Plan all extractions atomically — nothing physically moved yet
                for (IngredientNeed need : grouped.values()) {
                    ItemStack reserved = storageEndpoint != null
                            ? ledger.reserveFromEndpoint(need.ingredient, need.count, storageEndpoint, player)
                            : CraftPacketUtils.ensureMaterialAvailable(
                            player, player.serverLevel().dimension(),
                            player.blockPosition(), need.ingredient, need.count, ledger, network);
                    if (reserved.isEmpty() && storageEndpoint != null) {
                        reserved = ledger.reserveFromInventory(need.ingredient, need.count, player);
                    }
                    if (reserved.isEmpty()) {
                        String missingName = CraftPacketUtils.describeIngredient(need.ingredient).getString();
                        if (FAILURE_LOG_SAMPLER.allow("extract:" + recipeId + ":" + missingName)) {
                            RSIntegrationMod.LOGGER.warn("[RSI-Generic] Grouped extraction failed for {}: missing {} (needed {}) (iteration {}/{})",
                                    recipeId, missingName, need.count, r + 1, repeatCount);
                        }
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.missing_materials",
                                missingName));
                        extractionIncomplete = true;
                        break;
                    }
                    allExtracted.add(reserved.copy());
                }

                if (extractionIncomplete) {
                    break;
                }

                // Commit all extractions atomically
                if (!ledger.commit(network, player)) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", "Extraction commit failed"));
                    break;
                }

                // Craft the final recipe — use assemble() for CraftingRecipe
                // so NBT from inputs (backpack contents, blade stats, etc.)
                // carries forward to the output. getResultItem() returns a
                // bare template that silently discards all stored data.
                ItemStack result;
                if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
                    result = SmithingRecipeHandler.assembleTransform(
                            smithingRecipe, allExtracted,
                            player.serverLevel().registryAccess());
                } else if (recipe instanceof CraftingRecipe cr) {
                    // Reconstruct the actual crafting grid layout from the
                    // extracted pool — allExtracted is grouped/deduped, not
                    // slot-aligned. Build a pool copy and split(1) from it so
                    // the 3×3 / 2×2 container matches the recipe pattern.
                    List<ItemStack> pool = new ArrayList<>();
                    for (ItemStack s : allExtracted) pool.add(s.copy());

                    List<Ingredient> ingredients = cr.getIngredients();
                    ItemStack[] consumed = new ItemStack[ingredients.size()];
                    for (int i = 0; i < ingredients.size(); i++) {
                        Ingredient ing = ingredients.get(i);
                        if (ing.isEmpty()) {
                            consumed[i] = ItemStack.EMPTY;
                            continue;
                        }
                        consumed[i] = ItemStack.EMPTY;
                        for (ItemStack p : pool) {
                            if (!p.isEmpty() && IngredientMatcher.test(ing, p)) {
                                consumed[i] = p.split(1);
                                break;
                            }
                        }
                    }
                    result = CraftPacketUtils.assembleCraftingOutput(cr, consumed, player);
                } else {
                    result = RecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
                }
                if (result.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Generic] Result unavailable for {} ({}), class={}",
                            recipeId, recipe.getClass().getSimpleName(), recipe.getClass().getName());
                }

                if (!result.isEmpty()) {
                    if (network != null) {
                        // Stamp the storage tracker BEFORE inserting so the crafted
                        // output surfaces at the top of RS's "recently modified" sort
                        // (matches RS's own extract/insert flow). Without this the new
                        // item has no timestamp and the player must hunt for it among
                        // identical stacks.
                        ItemStack leftover = result.copy();
                        if (outputDestination == OutputDestination.PLAYER_INVENTORY) {
                            leftover = PlayerUtils.insertIntoPlayerInventory(player, leftover);
                        }
                        if (outputDestination == OutputDestination.RS_NETWORK || !leftover.isEmpty()) {
                            leftover = TrackedNetworkInsertion.insert(network, player, leftover);
                        }
                        ItemStack inserted = InsertedStackDelta.between(result, leftover);
                        ExternalItemProgressBridge.enqueueCrafted(player, inserted);
                        if (!leftover.isEmpty()) {
                            safeGiveToPlayer(player, leftover);
                        }
                    } else if (storageEndpoint != null) {
                        ItemStack leftover = outputDestination == OutputDestination.PLAYER_INVENTORY
                                ? PlayerUtils.insertIntoPlayerInventory(player, result.copy()) : result.copy();
                        if (outputDestination == OutputDestination.RS_NETWORK || !leftover.isEmpty()) {
                            leftover = storageEndpoint.insert(player, leftover, false)
                                    .remainder().orElse(ItemStack.EMPTY);
                        }
                        if (!leftover.isEmpty()) safeGiveToPlayer(player, leftover);
                        ExternalItemProgressBridge.enqueueCrafted(player,
                                InsertedStackDelta.between(result, leftover));
                    } else {
                        safeGiveToPlayer(player, result);
                    }
                    // Return crafting remainders (CT .reuse()/.transformDamage(),
                    // and NBT-dependent remainders like Goety's Totem of Souls which
                    // drains charge instead of being consumed). Must feed the ACTUAL
                    // consumed stacks (with NBT) — the no-arg overload uses bare
                    // template items, so a charged totem would come back as a spent
                    // one and the player's charged totem would be silently eaten.
                    //
                    // allExtracted is grouped/deduped (e.g. [gold_block x8, totem x1]),
                    // not slot-aligned. Rebuild a per-slot array of size ingredients()
                    // so getRemainingItems() computes one remainder per real slot.
                    if (recipe instanceof CraftingRecipe cr && (network != null || storageEndpoint != null)) {
                        List<Ingredient> ings = cr.getIngredients();
                        ItemStack[] slotAligned = new ItemStack[ings.size()];
                        List<ItemStack> pool = new ArrayList<>();
                        for (ItemStack s : allExtracted) pool.add(s.copy());
                        for (int i = 0; i < ings.size(); i++) {
                            Ingredient ing = ings.get(i);
                            if (ing.isEmpty()) continue;
                            for (ItemStack p : pool) {
                                if (!p.isEmpty() && IngredientMatcher.test(ing, p)) {
                                    slotAligned[i] = p.copyWithCount(1);
                                    p.shrink(1);
                                    break;
                                }
                            }
                        }
                        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr, slotAligned)) {
                            if (!remainder.isEmpty()) {
                                ItemStack leftover = storageEndpoint != null
                                        ? storageEndpoint.insert(player, remainder, false).remainder().orElse(ItemStack.EMPTY)
                                        : TrackedNetworkInsertion.insert(network, player, remainder);
                                if (!leftover.isEmpty()) {
                                    safeGiveToPlayer(player, leftover);
                                }
                            }
                        }
                    }
                } else {
                    // Failed to get result — refund actual extracted materials
                    if (network != null || storageEndpoint != null) {
                        for (ItemStack refundStack : allExtracted) {
                            if (refundStack.isEmpty()) continue;
                            ItemStack refund = refundStack.copy();
                            ItemStack leftover = storageEndpoint != null
                                    ? storageEndpoint.insert(player, refund, false).remainder().orElse(ItemStack.EMPTY)
                                    : TrackedNetworkInsertion.insert(network, player, refund);
                            if (!leftover.isEmpty()) {
                                safeGiveToPlayer(player, leftover);
                            }
                        }
                    }
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", "Result unavailable"));
                    break;
                }
                RSIntegrationMod.debug("[RSI-Generic] Crafted {} (iteration {}/{}) for {}",
                        result.getCount(), r + 1, repeatCount, recipeId);
                resolvedItems = Math.min(Integer.MAX_VALUE, resolvedItems + result.getCount());
            }
        }
        // A large synchronous fallback batch should produce one client update,
        // not one actionbar packet per operation.
        if (resolvedItems > 0) {
            player.displayClientMessage(
                    Component.translatable("rsi.generic.info.resolved", (int) resolvedItems), true);
        }
    }

    static INetwork resolveNetworkForRecipe(ServerPlayer player,
            @Nullable ResourceLocation dim, @Nullable net.minecraft.core.BlockPos pos,
            @Nullable ModType modType) {
        // 1. Try primary machine (from packet/JEI).
        // Validate that the player has a binding to this position before
        // resolving the RS network — prevents coordinate-spoofing exploits
        // where a malicious client sends another player's machine coordinates.
        if (dim != null && pos != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dim);
            if (AltarBindingRegistry.isBound(key, pos, player)) {
                INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, key, pos);
                if (network != null) return network;
            }
        }
        // 2. Use the same storage resolver as preview/planning. This keeps
        // wireless NetworkItem, bound-network, terminal, and crafting-only
        // nearby-node resolution identical between preview and execution.
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(
                player, dim == null ? player.serverLevel().dimension()
                        : ResourceKey.create(Registries.DIMENSION, dim), pos);
        if (network != null) return network;
        // 3. For mod recipes: try all bound machines of matching type
        if (modType != null && modType != ModType.GENERIC && !modType.isVirtual()) {
            for (AltarBindingRegistry.BoundMachine m :
                    AltarBindingRegistry.getBoundMachinesForType(player, modType)) {
                if (m.dim().equals(dim) && m.pos().equals(pos)) continue;
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, m.dim());
                network = RSAltarBindingResolver.resolveNetworkForAltar(player, key, m.pos());
                if (network != null) return network;
            }
        }
        return null;
    }

    private static void logExecutionAvailability(ResourceLocation recipeId,
                                                  Map<StackKey, Integer> available) {
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

    private static final class IngredientNeed {
        final Ingredient ingredient;
        int count;
        IngredientNeed(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = count;
        }
    }

    // ── preview: build plan and send to client ───────────────────

    /**
     * Cache-discriminator for the JEI-clicked output stack. The Arcane Iterator's
     * per-level enchant recipes all share one recipeId but produce different books
     * (Curse III vs V), so the clicked output's item+NBT must enter the plan cache
     * key — otherwise previewing V then III returns the stale V tree. Item-only
     * outputs collapse to "0" so non-WR recipes keep their prior cache behavior.
     */
    private static String clickedOutputCacheToken(@Nullable ItemStack clicked) {
        if (clicked == null || clicked.isEmpty()) return "0";
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(clicked.getItem()).toString();
        CompoundTag tag = clicked.getTag();
        return tag != null ? key + "#" + tag : key;
    }

    static List<IngredientSpec> scaleIngredientSpecs(
            List<IngredientSpec> specs, int executions) {
        List<IngredientSpec> scaled = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            scaled.add(new IngredientSpec(spec.ingredient(),
                    CraftPacketUtils.requiredCount(spec, executions), spec.role()));
        }
        return List.copyOf(scaled);
    }

    static List<IngredientSpec> scaleIngredientSpecs(
            Recipe<?> recipe, List<IngredientSpec> specs, int executions) {
        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler == null) return scaleIngredientSpecs(specs, executions);
        List<IngredientSpec> scaled = new ArrayList<>(specs.size());
        int inputIndex = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            scaled.add(new IngredientSpec(spec.ingredient(),
                    handler.requiredIngredientCount(recipe, spec, inputIndex++, executions),
                    spec.role()));
        }
        return List.copyOf(scaled);
    }

    static List<IngredientSpec> scaleTerminalIngredientSpecs(
            List<IngredientSpec> specs, ItemStack output, int executions) {
        return SelfAmplifyingRecipePolicy.scaleTargetInputs(specs, output, executions);
    }

    static List<IngredientSpec> scaleTerminalIngredientSpecs(
            Recipe<?> recipe, List<IngredientSpec> specs, ItemStack output, int executions) {
        List<IngredientSpec> scaled = scaleTerminalIngredientSpecs(specs, output, executions);
        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler == null) return scaled;

        List<IngredientSpec> adjusted = new ArrayList<>(scaled.size());
        int scaledIndex = 0;
        int inputIndex = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientSpec terminalSpec = scaled.get(scaledIndex++);
            int ordinaryCount = CraftPacketUtils.requiredCount(spec, executions);
            int handlerCount = handler.requiredIngredientCount(
                    recipe, spec, inputIndex++, executions);
            adjusted.add(handlerCount == ordinaryCount
                    ? terminalSpec
                    : new IngredientSpec(terminalSpec.ingredient(), handlerCount,
                            terminalSpec.role()));
        }
        return List.copyOf(adjusted);
    }

    static List<DemandRole> nonEmptyInputRoles(List<IngredientSpec> specs) {
        return specs.stream()
                .filter(spec -> !spec.isEmpty())
                .map(IngredientSpec::role)
                .toList();
    }

    static List<DemandRole> alignInputRoles(
            List<Ingredient> displayed, List<IngredientSpec> specs) {
        if (displayed.size() == specs.size()) {
            return specs.stream().map(spec -> spec.isEmpty()
                    ? DemandRole.CONSUMED : spec.role()).toList();
        }

        List<DemandRole> nonEmpty = nonEmptyInputRoles(specs);
        List<DemandRole> aligned = new ArrayList<>(displayed.size());
        int roleIndex = 0;
        for (Ingredient ingredient : displayed) {
            if (ingredient.isEmpty()) {
                aligned.add(DemandRole.CONSUMED);
            } else {
                aligned.add(roleIndex < nonEmpty.size()
                        ? nonEmpty.get(roleIndex++) : DemandRole.CONSUMED);
            }
        }
        return List.copyOf(aligned);
    }

    private interface PlanResultSink {
        void success(PlanResponse plan, PlanningSnapshot snapshot);
        void error(Component message);
        default void notice(Component message) {}
    }

    private static final java.util.concurrent.ConcurrentMap<PlanResultKey, PlanResultGate> PLAN_RESULT_GATES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PLAN_RESULT_GATE_TTL_NANOS = 60_000_000_000L;

    private static PlanResultSink networkSink(ServerPlayer player, long requestId,
                                              long previewGeneration) {
        long now = System.nanoTime();
        PLAN_RESULT_GATES.entrySet().removeIf(entry ->
                entry.getValue().expired(now, PLAN_RESULT_GATE_TTL_NANOS));
        PlanResultKey key = new PlanResultKey(player.getUUID(), requestId, previewGeneration);
        PlanResultGate gate = PLAN_RESULT_GATES.compute(key, (ignored, existing) ->
                existing == null || existing.expired(now, PLAN_RESULT_GATE_TTL_NANOS)
                        ? new PlanResultGate(previewGeneration, now) : existing);
        return new PlanResultSink() {
            @Override
            public void success(PlanResponse plan, PlanningSnapshot snapshot) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                PLAN_RESULT_GATES.remove(key, gate);
                PlanResponsePublisher.send(player, plan, requestId);
            }

            @Override
            public void error(Component message) {
                if (!gate.tryEnterTerminal(previewGeneration)) return;
                PLAN_RESULT_GATES.remove(key, gate);
                PlanResponsePublisher.sendError(player, message, requestId);
            }

            @Override
            public void notice(Component message) {
                if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
                    player.sendSystemMessage(message);
                }
            }
        };
    }

    private record PlanResultKey(UUID playerId, long requestId, long previewGeneration) {}

    private static void clearPlanResultGates(@Nullable UUID playerId) {
        if (playerId == null) {
            PLAN_RESULT_GATES.forEach((key, gate) -> gate.cancel());
            PLAN_RESULT_GATES.clear();
            return;
        }
        PLAN_RESULT_GATES.entrySet().removeIf(entry -> {
            if (!playerId.equals(entry.getKey().playerId())) return false;
            entry.getValue().cancel();
            return true;
        });
    }

    private static boolean isSnapshotActive(ServerPlayer player, PlanningSnapshot snapshot,
                                            long previewGeneration) {
        return !player.hasDisconnected() && !player.isRemoved()
                && snapshot.requestGeneration() == previewGeneration
                && PLAN_REQUESTS.isCurrent(player.getUUID(), previewGeneration)
                && com.huanghuang.rsintegration.crafting.CraftPlanningRevision
                .isCurrent(snapshot.recipeRevision());
    }

    private static void findMaxCraftable(ServerPlayer player, GenericCraftPacket packet,
                                         long previewGeneration) {
        tryBuildPlan(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                1, packet.baseItem, packet.targetOutput, packet.requestId, previewGeneration,
                null, null, false, null, false, new PlanResultSink() {
                    @Override
                    public void success(PlanResponse prepared, PlanningSnapshot snapshot) {
                        if (!prepared.success()) {
                            networkSink(player, packet.requestId, previewGeneration).success(prepared, snapshot);
                            return;
                        }
                        boolean fullyProjected = snapshot.recipeGraph().recipesById()
                                .containsKey(packet.recipeId)
                                && prepared.steps().stream().allMatch(step ->
                                snapshot.recipeGraph().recipesById().containsKey(step.recipeId()))
                                && (prepared.graph() == null || prepared.graph().nodes().stream()
                                .allMatch(node -> snapshot.recipeGraph().recipesById()
                                        .containsKey(node.recipeId())));
                        if (snapshot.mainThreadOnly() || !fullyProjected
                                || !packet.forcedRecipes.isEmpty()
                                || !packet.materialLocks.isEmpty()) {
                            MaxCraftableSearch search = new MaxCraftableSearch(
                                    RSIntegrationConfig.REPEAT_COUNT_MAX.get());
                            continueMaxCraftableSearch(player, packet, previewGeneration,
                                    search, snapshot);
                            return;
                        }
                        PLAN_REQUESTS.submitMaxCraftable(snapshot,
                                RSIntegrationConfig.REPEAT_COUNT_MAX.get(),
                                player.getServer()::execute,
                                RSIntegrationConfig.CRAFTING_MAX_STEPS.get(),
                                result -> {
                                    if (!result.determined()) {
                                        networkSink(player, packet.requestId, previewGeneration).error(
                                                Component.translatable(
                                                        "rsi.plan.failure.max_craftable_unknown"));
                                    } else if (result.maximum() <= 0) {
                                        MaxCraftableSearch fallback = new MaxCraftableSearch(
                                                RSIntegrationConfig.REPEAT_COUNT_MAX.get());
                                        continueMaxCraftableSearch(player, packet,
                                                previewGeneration, fallback, result.snapshot());
                                    } else {
                                        finishMaxCraftableSearch(player, packet,
                                                previewGeneration, result.maximum(), result.plan(),
                                                result.snapshot());
                                    }
                                },
                                failure -> networkSink(player, packet.requestId, previewGeneration).error(
                                        buildFailureMessage(failure, packet.recipeId)));
                    }

                    @Override
                    public void error(Component message) {
                        networkSink(player, packet.requestId, previewGeneration).error(message);
                    }
                }, packet.storageReference, packet.materialLocks);
    }

    private static void finishMaxCraftableSearch(ServerPlayer player, GenericCraftPacket packet,
                                                 long previewGeneration, int maximum,
                                                 @Nullable PureRecipePlanner.Result plan,
                                                 PlanningSnapshot snapshot) {
        int displayCount = Math.max(1, maximum);
        tryBuildPlan(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                displayCount, packet.baseItem, packet.targetOutput, packet.requestId,
                previewGeneration, maximum > 0 ? plan : null, snapshot, true,
                maximum > 0 ? null : SynchronousFallbackReason.PURE_UNRESOLVABLE,
                true, networkSink(player, packet.requestId, previewGeneration), packet.storageReference,
                packet.materialLocks);
    }

    private static void continueMaxCraftableSearch(ServerPlayer player, GenericCraftPacket packet,
                                                   long previewGeneration,
                                                   MaxCraftableSearch search,
                                                   @Nullable PlanningSnapshot snapshot) {
        if (snapshot != null && !isSnapshotActive(player, snapshot, previewGeneration)) return;
        OptionalInt probe = search.nextProbe();
        if (probe.isEmpty()) {
            int maximum = search.result();
            if (maximum <= 0) {
                tryBuildPlan(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                        1, packet.baseItem, packet.targetOutput, packet.requestId,
                        previewGeneration, null, snapshot, false, null,
                        true, networkSink(player, packet.requestId, previewGeneration), packet.storageReference,
                        packet.materialLocks);
                return;
            }
            tryBuildPlan(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                    maximum, packet.baseItem, packet.targetOutput, packet.requestId,
                    previewGeneration, null, snapshot, false, null,
                    true, networkSink(player, packet.requestId, previewGeneration), packet.storageReference,
                    packet.materialLocks);
            return;
        }

        int candidate = probe.getAsInt();
        PlanResultSink probeSink = new PlanResultSink() {
            @Override
            public void success(PlanResponse plan, PlanningSnapshot usedSnapshot) {
                search.accept(candidate, plan.success());
                continueMaxCraftableSearch(player, packet, previewGeneration, search,
                        snapshot == null ? usedSnapshot : snapshot);
            }

            @Override
            public void error(Component message) {
                networkSink(player, packet.requestId, previewGeneration).error(message);
            }
        };
        tryBuildPlan(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                candidate, packet.baseItem, packet.targetOutput, packet.requestId,
                previewGeneration, null, snapshot, false, null,
                true, probeSink, packet.storageReference, packet.materialLocks);
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false, null,
                false, networkSink(player, requestId, previewGeneration), null, Map.of());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable StorageReference storageReference) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false, null,
                false, networkSink(player, requestId, previewGeneration), storageReference, Map.of());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount, @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, null, null, false, null,
                false, networkSink(player, requestId, previewGeneration), storageReference, materialLocks);
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable PureRecipePlanner.Result precomputedPlan,
                                      @Nullable PlanningSnapshot precomputedSnapshot,
                                      boolean asyncAttempted,
                                      @Nullable SynchronousFallbackReason pendingFallbackReason,
                                      boolean reuseValidatedSnapshot,
                                      PlanResultSink sink) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan,
                precomputedSnapshot, asyncAttempted, pendingFallbackReason,
                reuseValidatedSnapshot, sink, null, Map.of());
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput, long requestId,
                                      long previewGeneration,
                                      @Nullable PureRecipePlanner.Result precomputedPlan,
                                      @Nullable PlanningSnapshot precomputedSnapshot,
                                      boolean asyncAttempted,
                                      @Nullable SynchronousFallbackReason pendingFallbackReason,
                                      boolean reuseValidatedSnapshot,
                                      PlanResultSink sink,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks) {
        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount, baseItem,
                clickedOutput, requestId, previewGeneration, precomputedPlan, precomputedSnapshot,
                asyncAttempted, pendingFallbackReason, reuseValidatedSnapshot, sink,
                storageReference, materialLocks, null);
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos, int repeatCount,
                                      @Nullable ItemStack baseItem, @Nullable ItemStack clickedOutput,
                                      long requestId, long previewGeneration,
                                      @Nullable PureRecipePlanner.Result precomputedPlan,
                                      @Nullable PlanningSnapshot precomputedSnapshot,
                                      boolean asyncAttempted,
                                      @Nullable SynchronousFallbackReason pendingFallbackReason,
                                      boolean reuseValidatedSnapshot, PlanResultSink sink,
                                      @Nullable StorageReference storageReference,
                                      Map<String, ItemStack> materialLocks,
                                      @Nullable AsyncPurePlanningService.RoutedPlan routedPlan) {
        long planStartNanos = System.nanoTime();
        Recipe<?> recipe = resolveRecipe(player.serverLevel(), recipeId);
        if (recipe == null) {
            sink.error(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }
        if (recipe instanceof CraftingRecipe craftingRecipe
                && !CraftPacketUtils.isCraftingRecipeAvailable(craftingRecipe, player)) {
            sink.error(Component.translatable("rsi.generic.error.recipe_stage_missing"));
            return;
        }
        ModType previewModType = resolveExecutionModType(player, recipe, dim, pos);
        if (requiresBoundMachine(recipe, previewModType)
                && !isSmithingRecipe(recipe)
                && !hasBindingForExecutionType(player, recipe, previewModType)) {
            logBindingRejection("preview-entry", player, recipe, previewModType, dim, pos);
            sink.error(Component.translatable("rsi.plan.failure.no_bound_machine"));
            return;
        }

        // FA ApplyModifierRecipe: if JEI provides a base item, build a full
        // recursive plan so the player can see what materials are needed.
        // Without a base item (e.g. manual /craft command), redirect to the
        // smithing table GUI instead.
        boolean faRecipe = OpenBoundMachineGuiPacket.isFaApplyModifier(recipe);
        if (faRecipe && (baseItem == null || baseItem.isEmpty())) {
            sink.error(Component.translatable("rsi.generic.error.fa_open_smithing"));
            return;
        }
        boolean arsDynamic = ArsDynamicApparatusRecipe.isSupported(recipe);
        boolean goetyDynamic = GoetyDynamicRitualRecipe.isSupported(recipe);
        if ((arsDynamic || goetyDynamic) && (clickedOutput == null || clickedOutput.isEmpty())) {
            sink.error(Component.translatable(
                    "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
            return;
        }

        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            sink.error(Component.translatable("rsi.generic.error.auto_craft_disabled"));
            return;
        }

        // Determine ingredients, output, and mod type for both vanilla and mod recipes
        List<IngredientSpec> recipeSpecs;
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
                java.lang.reflect.Method getTemplate = recipe.getClass().getMethod("getTemplate");
                java.lang.reflect.Method getAddition = recipe.getClass().getMethod("getAddition");
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
                displayInputRoles = nonEmptyInputRoles(specs);
                recipeSpecs = scaleIngredientSpecs(specs, repeatCount);
                // Apply modifier to the JEI-provided base item so the plan
                // shows the actual modified output (e.g. sword + eternal),
                // not the unmodified base material.
                targetOutput = baseItem.copy();
                try {
                    java.lang.reflect.Method getModifier = recipe.getClass().getMethod("getModifier");
                    Object modifier = getModifier.invoke(recipe);
                    if (modifier != null) {
                        Class<?> helperClass = Class.forName(
                                "com.stal111.forbidden_arcanus.common.item.modifier.ModifierHelper");
                        java.lang.reflect.Method setModifier = null;
                        for (java.lang.reflect.Method m : helperClass.getMethods()) {
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
                sink.error(Component.translatable("rsi.generic.error.fa_open_smithing"));
                return;
            }
        } else if (arsDynamic) {
            ItemStack validated = ArsDynamicApparatusRecipe.validatedOutput(recipe, clickedOutput);
            List<IngredientSpec> specs = ArsDynamicApparatusRecipe.buildMaterials(recipe, clickedOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                sink.error(Component.translatable(
                        "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
                return;
            }
            displayIngredients = specs.stream()
                    .filter(spec -> !spec.isEmpty())
                    .map(IngredientSpec::ingredient)
                    .toList();
            displayInputRoles = nonEmptyInputRoles(specs);
            recipeSpecs = scaleIngredientSpecs(specs, repeatCount);
            targetOutput = validated;
            recipeModType = previewModType;
        } else if (goetyDynamic) {
            ItemStack validated = GoetyDynamicRitualRecipe.validatedOutput(recipe, clickedOutput);
            List<IngredientSpec> specs = GoetyDynamicRitualRecipe.buildMaterials(recipe, clickedOutput);
            if (validated.isEmpty() || specs.isEmpty()) {
                sink.error(Component.translatable(
                        "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
                return;
            }
            displayIngredients = specs.stream()
                    .filter(spec -> !spec.isEmpty())
                    .map(IngredientSpec::ingredient)
                    .toList();
            displayInputRoles = nonEmptyInputRoles(specs);
            recipeSpecs = scaleTerminalIngredientSpecs(
                    recipe, specs, validated, repeatCount);
            targetOutput = validated;
            recipeModType = previewModType;
        } else if (recipe instanceof CraftingRecipe cr) {
            List<Ingredient> raw = cr.getIngredients();
            List<IngredientSpec> extractedSpecs = extractPlanIngredientSpecs(cr);
            if (cr instanceof net.minecraft.world.item.crafting.ShapedRecipe) {
                // Preserve empty slots for the shaped-grid renderer.
                displayIngredients = raw;
                displayInputRoles = alignInputRoles(raw, extractedSpecs);
            } else {
                // Some mod recipes implement CraftingRecipe for JEI integration but
                // their handler supplies semantic inputs absent from getIngredients(),
                // such as Goety's activation item or Botania's reagent.
                displayIngredients = extractedSpecs.stream()
                        .filter(spec -> !spec.isEmpty())
                        .map(IngredientSpec::ingredient)
                        .toList();
                displayInputRoles = nonEmptyInputRoles(extractedSpecs);
            }
            targetOutput = ModRecipeHandlers.tryGetResultItem(
                    cr, player.serverLevel().registryAccess());
            recipeSpecs = scaleTerminalIngredientSpecs(
                    recipe, extractedSpecs, targetOutput, repeatCount);
        } else {
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
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
                net.minecraft.resources.ResourceKey<Level> cpDim = dim != null
                        ? net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, dim)
                        : player.serverLevel().dimension();
                net.minecraft.core.BlockPos cpPos = pos != null ? pos : player.blockPosition();
                CraftStorageEndpoint cpEndpoint = storageReference != null
                        ? CraftStorageEndpoints.resolve(storageReference, player).orElse(null)
                        : CraftStorageEndpoints.resolveDefault(player).orElse(null);
                INetwork cpNetwork = cpEndpoint == null
                        && (storageReference == null
                        || "refinedstorage".equals(storageReference.backendId().value()))
                        && net.minecraftforge.fml.ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                        ? CraftPacketUtils.resolveNetworkForCraft(player, cpDim, cpPos)
                        : null;
                specs = cpEndpoint != null
                        ? CrockPotBatchDelegate.buildCategoryPlanIngredients(
                                recipe, cpEndpoint, player, player.serverLevel(), cpPos)
                        : CrockPotBatchDelegate.buildCategoryPlanIngredients(
                                recipe, cpNetwork, player.serverLevel(), cpPos);
                if (specs == null || specs.isEmpty()) {
                    sink.error(Component.translatable("rsi.crockpot.error.food_values"));
                    return;
                }
            } else if (specs == null || specs.isEmpty()) {
                sink.error(Component.translatable("rsi.generic.error.no_ingredients"));
                return;
            }
            List<Ingredient> perRecipe = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                perRecipe.add(spec.ingredient());
            }
            displayIngredients = perRecipe;
            displayInputRoles = nonEmptyInputRoles(specs);
            targetOutput = ModRecipeHandlers.tryGetResultItem(recipe, player.serverLevel().registryAccess());
            targetOutput = routeRecipeOutput(recipeModType, targetOutput);
            recipeSpecs = scaleTerminalIngredientSpecs(
                    recipe, specs, targetOutput, repeatCount);
            boolean manualGoetyRitual = GoetyRecipeHandler.requiresManualConfirmation(recipe);
            RSIntegrationMod.debug("[RSI-tryBuildPlan] targetOutput: recipeId={} class={} result={}x{} isEmpty={} modType={}",
                    recipeId,
                    recipe.getClass().getSimpleName(),
                    targetOutput.isEmpty() ? "EMPTY"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetOutput.getItem()),
                    targetOutput.getCount(), targetOutput.isEmpty(),
                    recipeModType != null ? recipeModType.id() : "null");
            if (targetOutput.isEmpty() && recipeModType != null
                    && !ModIds.ID_EMBERS_ALCHEMY.equals(recipeModType.id())
                    && !ModIds.ID_AETHERWORKS_ANVIL.equals(recipeModType.id())
                    && !ModIds.TOUHOU_LITTLE_MAID.equals(recipeModType.id())
                    && !ModIds.FORBIDDEN_ARCANUS.equals(recipeModType.id())
                    && !ModIds.AETHER.equals(recipeModType.id())
                    && !recipeModType.id().startsWith(ModIds.AETHER + "_")
                    && !ModIds.ID_YHK_KETTLE.equals(recipeModType.id())
                    && !ModIds.ID_YHK_FERMENT.equals(recipeModType.id())
                    && !ModIds.ID_FR_KETTLE.equals(recipeModType.id())
                    && !manualGoetyRitual) {
                sink.error(Component.translatable(
                        "rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()));
                return;
            }
        }

        targetOutput = routeRecipeOutput(recipeModType, targetOutput);
        recipeSpecs = MaterialLocks.narrowSpecs(recipeId, recipeSpecs, materialLocks);

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

        // Convert forced recipe overrides (itemRegKey → recipeId) for the resolver
        Map<ResourceLocation, ResourceLocation> forcedOverrides = null;
        if (!forcedRecipes.isEmpty()) {
            forcedOverrides = new HashMap<>();
            for (var e : forcedRecipes.entrySet()) {
                ResourceLocation itemKey = ResourceLocation.tryParse(e.getKey());
                ResourceLocation forcedId = ResourceLocation.tryParse(e.getValue());
                if (itemKey == null || forcedId == null
                        || (!CraftingResolver.isStackPreferenceKey(itemKey)
                        && !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemKey))
                        || resolveRecipe(player.serverLevel(), forcedId) == null) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.invalid_forced_recipe",
                            String.valueOf(e.getKey()), String.valueOf(e.getValue())));
                    return;
                }
                forcedOverrides.put(itemKey, forcedId);
            }
        }

        final PlanCache.Key cacheKey = planCacheKey(player.getUUID(), recipeId,
                forcedRecipes, repeatCount, clickedOutput, recipeModType, materialLocks);
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> planDimKey = dim != null
                ? net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dim)
                : player.serverLevel().dimension();
        net.minecraft.core.BlockPos planLookupPos = pos != null ? pos : player.blockPosition();
        // Resolve an explicit backend-qualified target first. With no explicit
        // target, preserve RS terminal discovery for existing installations,
        // then fall back to the first registered optional backend (BD-only).
        CraftStorageEndpoint contextEndpoint = StorageRestockSupport.resolve(player).orElse(null);
        INetwork network = hasRefinedStorage()
                && (storageReference == null
                || "refinedstorage".equals(storageReference.backendId().value()))
                && (contextEndpoint == null
                || !"beyonddimensions".equals(contextEndpoint.session().reference().backendId().value()))
                ? CraftPacketUtils.resolveNetworkForCraft(player, planDimKey, planLookupPos)
                : null;
        CraftStorageEndpoint planningEndpoint = null;
        StorageReference selectedStorageReference = storageReference;
        if (selectedStorageReference != null) {
            var resolved = CraftStorageEndpoints.resolve(selectedStorageReference, player);
            if (resolved.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Storage] selected plan reference unavailable backend={} id={} player={}; no fallback",
                        selectedStorageReference.backendId(), selectedStorageReference.networkId(),
                        player.getGameProfile().getName());
                sink.error(Component.translatable("rsi.generic.error.network_unavailable"));
                return;
            } else {
                planningEndpoint = resolved.orElseThrow();
                // The endpoint may have refreshed a legacy/stale RS reference
                // from the player's current credential. Persist that canonical
                // reference in the snapshot and async request so the next
                // execution does not retry the invalid locator.
                selectedStorageReference = planningEndpoint.session().reference();
                if (!"refinedstorage".equals(selectedStorageReference.backendId().value())) {
                    network = null;
                }
            }
        } else if (network != null) {
            planningEndpoint = CraftStorageEndpoints.fromLegacyNetwork(network);
            selectedStorageReference = planningEndpoint.session().reference();
        } else {
            var defaults = contextEndpoint != null
                    ? java.util.List.of(contextEndpoint.session())
                    : RSIntegrationMod.STORAGE_BACKENDS.registry()
                    .resolveDefaultSessionsForPlayer(player);
            if (!defaults.isEmpty()) {
                planningEndpoint = new com.huanghuang.rsintegration.crafting.SessionCraftStorageEndpoint(defaults.get(0));
                selectedStorageReference = defaults.get(0).reference();
            } else {
                // A backend may expose accessible networks without having a
                // primary network selected (BD does this when the player has
                // membership but has not chosen a primary).  Turn the
                // discovered descriptor into the live endpoint here so the
                // plan does not discard a valid network after discovery.
                for (StorageNetworkDescriptor descriptor :
                        RSIntegrationMod.STORAGE_BACKENDS.registry().discoverNetworksForPlayer(player)) {
                    var discoveredEndpoint = CraftStorageEndpoints.resolve(descriptor.reference(), player);
                    if (discoveredEndpoint.isPresent()) {
                        planningEndpoint = discoveredEndpoint.orElseThrow();
                        selectedStorageReference = descriptor.reference();
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-Storage] selected discovered default network backend={} id={} player={}",
                                selectedStorageReference.backendId(), selectedStorageReference.networkId(),
                                player.getGameProfile().getName());
                        break;
                    }
                }
            }
        }
        final StorageReference planStorageReference = selectedStorageReference;
        List<StorageNetworkDescriptor> storageNetworks =
                RSIntegrationMod.STORAGE_BACKENDS.registry().discoverNetworksForPlayer(player);
        RSIntegrationMod.debug(
                "[RSI-Storage] plan network choices player={} selected={} choices={}",
                player.getGameProfile().getName(), planStorageReference,
                storageNetworks.stream()
                        .map(descriptor -> descriptor.reference().backendId().value()
                                + "@" + descriptor.reference().networkId())
                        .toList());
        if (RSIntegrationConfig.REQUIRE_RS_NETWORK_FOR_RECIPE_TREE.get() && planningEndpoint == null) {
            sink.error(Component.translatable("rsi.generic.error.network_unavailable"));
            return;
        }

        Map<ResourceLocation, ResourceLocation> effectiveOverrides =
                forcedOverrides == null ? Map.of() : forcedOverrides;
        Map<StackKey, Integer> available;
        PlanningSnapshot planningSnapshot;
        if (precomputedSnapshot != null) {
            if (!matchesAsyncRequest(precomputedSnapshot, player.getUUID(), recipeId,
                    previewGeneration, effectiveOverrides)
                    || (!reuseValidatedSnapshot && !PlanningStateValidator.revalidate(
                    player, precomputedSnapshot, planDimKey, planLookupPos, PLAN_REQUESTS,
                    planStorageReference))) {
                RSIntegrationMod.debug("[RSI-plan] Discarding stale async result before assembly: recipeId={}",
                        recipeId);
                return;
            }
            planningSnapshot = precomputedSnapshot;
            available = precomputedSnapshot.availableItems();
        } else {
            long snapshotStarted = System.nanoTime();
            try {
                available = planningEndpoint == null
                        ? listAvailable(player, network)
                        : MaterialSources.listAllAvailable(player, planningEndpoint);
                boolean backgroundProjectable = RecipeIndex.isBackgroundProjectable(recipe);
                BindingAwareGraph bindingGraph = recipe instanceof CraftingRecipe
                        || backgroundProjectable
                        ? bindingAwareGraph(player,
                                ImmutableRecipeGraphProjector.capture(player.serverLevel()))
                        : new BindingAwareGraph(new ImmutableRecipeGraph(Map.of()), Set.of());
                planningSnapshot = PlanningSnapshotFactory.capture(
                        player.getUUID(), previewGeneration, recipeId, available,
                        effectiveOverrides,
                        bindingGraph.graph(),
                        selectedStorageReference == null
                                ? PlanningStateValidator.networkFingerprint(network, available)
                                : PlanningStateValidator.networkFingerprint(selectedStorageReference, available),
                        PlanningStateValidator.bindingFingerprint(player, planDimKey, planLookupPos),
                        bindingGraph.blockedOutputIds(),
                        !(recipe instanceof CraftingRecipe) && !backgroundProjectable);
            } finally {
                PerformanceMonitor.recordPlanningSnapshot(System.nanoTime() - snapshotStarted);
            }
        }

        ItemStack smithingOutput = targetOutput;
        if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
            selectedSmithingBase = SmithingRecipeHandler.selectAvailableBase(
                    smithingRecipe, available, repeatCount);
            if (!selectedSmithingBase.isEmpty()) {
                recipeSpecs = SmithingRecipeHandler.requireExactBase(
                        smithingRecipe, recipeSpecs, selectedSmithingBase);
                displayIngredients = recipeSpecs.stream()
                        .filter(spec -> !spec.isEmpty())
                        .map(IngredientSpec::ingredient)
                        .toList();
                displayInputRoles = nonEmptyInputRoles(recipeSpecs);
                ItemStack assembled = SmithingRecipeHandler.assembleWithBase(
                        smithingRecipe, selectedSmithingBase,
                        player.serverLevel().registryAccess());
                if (!assembled.isEmpty()) smithingOutput = assembled;
            }
        }

        final ItemStack planTargetOutput = smithingOutput;

        PlanCache.Entry cached = PLAN_CACHE.get(cacheKey, System.nanoTime());
        if (cached != null && PlanningStateValidator.sameState(
                cached.snapshot(), planningSnapshot)) {
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
        if (recipe instanceof com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper market
                && effectiveOverrides.isEmpty()
                && tryBuildDirectMarketPlan(player, market, recipeId, repeatCount,
                planTargetOutput, dim, pos, available, planningSnapshot, cacheKey,
                planStartNanos, sink)) {
            return;
        }

        boolean hasFixedTerminalInputs = recipeSpecs.stream()
                .anyMatch(spec -> spec != null && !spec.isEmpty());
        DirectMaterialAllocator.Result directPreviewAllocation = effectiveOverrides.isEmpty()
                && hasFixedTerminalInputs
                ? DirectMaterialAllocator.allocate(recipeSpecs, available)
                : new DirectMaterialAllocator.Result(false, List.of(), -1, 0);
        boolean directTerminalPlan = directPreviewAllocation.feasible();
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
        if (!asyncAttempted && shouldSubmitRoutedPreview(directTerminalPlan,
                planningSnapshot.mainThreadOnly(), !effectiveOverrides.isEmpty(), !materialLocks.isEmpty())) {
            var routing = new AsyncPurePlanningService.RouteInputs(
                    routingAvailability(planningSnapshot.availableItems()), demandMaxNodes,
                    reusableCatalystOutputIds, reusableCatalystRecipeIds, pureIncompatibleOutputIds);
            PLAN_REQUESTS.submitRouted(planningSnapshot, routing, repeatCount, player.getServer()::execute,
                    RSIntegrationConfig.CRAFTING_MAX_STEPS.get(), completed ->
                            tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                    baseItem, clickedOutput, requestId, previewGeneration,
                                    completed.plan(), completed.snapshot(), true, null,
                                    reuseValidatedSnapshot, sink, planStorageReference, materialLocks, completed),
                    failure -> {
                        if (failure instanceof RejectedExecutionException) {
                            sink.error(Component.translatable("rsi.plan.failure.planner_busy"));
                            return;
                        }
                        var fallbackReason = SynchronousFallbackReason.fromAsyncFailure(failure);
                        if (fallbackReason.isEmpty()) return;
                        tryBuildPlan(player, recipeId, forcedRecipes, dim, pos, repeatCount,
                                baseItem, clickedOutput, requestId, previewGeneration,
                                null, null, true, fallbackReason.orElseThrow(),
                                reuseValidatedSnapshot, sink, planStorageReference, materialLocks);
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
                        null, null, false, null, false, sink, planStorageReference, materialLocks);
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
        // requests on the immutable background planner instead of falling back
        // to the 500ms typed resolver on the server thread.
        boolean pureRoute = demandTree.backgroundCompatible()
                && effectiveOverrides.isEmpty()
                && materialLocks.isEmpty()
                && !planningSnapshot.mainThreadOnly();
        // A live storage endpoint is sufficient for planning.  The legacy
        // INetwork value is intentionally null for non-RS backends, so using
        // it as the availability gate incorrectly rejects BD machine recipes
        // after BD material discovery has already succeeded.
        boolean typedResolverAvailable = RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()
                && planningEndpoint != null;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-plan] planner route recipe={} pure={} coverage={} nodes={} unresolved={} overrides={} mainThreadOnly={} catalystRoute={} targetCatalyst={}",
                recipeId, pureRoute, demandTree.status(), demandTree.visitedNodes(),
                demandTree.unresolved(), !effectiveOverrides.isEmpty(), planningSnapshot.mainThreadOnly(),
                demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst);
        var synchronousFallbackReason = pendingFallbackReason != null
                ? java.util.Optional.of(pendingFallbackReason)
                : asyncAttempted && (routedPlan == null || routedPlan.plan() != null)
                        ? java.util.Optional.<SynchronousFallbackReason>empty()
                        : SynchronousFallbackReason.whenPureRouteUnavailable(
                                planningSnapshot.mainThreadOnly(), !effectiveOverrides.isEmpty(),
                                demandTree.backgroundCompatible(), demandTree.catalystRouteAvailable());
        synchronousFallbackReason.ifPresent(reason ->
                PerformanceMonitor.recordSynchronousPlanningFallback(reason, recipeId));

        boolean terminalPureResult = directTerminalPlan
                || precomputedPlan != null && pendingFallbackReason == null;
        boolean timedOutMissingRoute = terminalPureResult
                && canOpenBoundedMissingPlan(precomputedPlan, demandTree);
        if (!directTerminalPlan && terminalPureResult
                && precomputedPlan.feasibility() == PureRecipePlanner.Feasibility.UNKNOWN
                && !timedOutMissingRoute) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-plan] bounded planner result recipe={} status={} feasibility={} missing={} states={} backtracks={} memoHits={} directReservation=false",
                    recipeId, precomputedPlan.status(), precomputedPlan.feasibility(),
                    precomputedPlan.missing(), precomputedPlan.expandedStates(),
                    precomputedPlan.backtracks(), precomputedPlan.memoHits());
            if (precomputedPlan.missing().isEmpty()) {
                sink.error(Component.translatable(
                        precomputedPlan.status() == PureRecipePlanner.Status.TIME_LIMIT
                                ? "rsi.plan.failure.planning_timeout"
                                : "rsi.plan.failure.complexity_limit"));
            } else {
                sink.error(Component.translatable("rsi.generic.error.missing_materials",
                        formatPureMissingMaterials(precomputedPlan.missing())));
            }
            return;
        }
        boolean requiresTypedCatalystRoute = requiresTypedCatalystRoute(
                demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst,
                precomputedPlan, reusableCatalystOutputIds);
        boolean requiresTypedIncompleteRoute = shouldRetryTypedPlanning(
                precomputedPlan, planningSnapshot.bindingBlockedOutputIds());
        boolean selectedPureResolver = terminalPureResult && effectiveOverrides.isEmpty()
                && !requiresTypedCatalystRoute
                && !requiresTypedIncompleteRoute;
        if (requiresTypedIncompleteRoute) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-plan] retrying typed preview after incomplete pure plan recipe={} missing={}",
                    recipeId, precomputedPlan.missing());
        }
        boolean needsTypedResolver = !selectedPureResolver;
        if (needsTypedResolver && !typedResolverAvailable) {
            sink.error(Component.translatable(planningEndpoint == null
                    ? "rsi.generic.error.network_unavailable"
                    : "rsi.generic.error.multiblock_auto_craft_disabled"));
            return;
        }
        if (needsTypedResolver
                && !TYPED_PREVIEW_REQUESTS.isAdmitted(player.getUUID(), previewGeneration)) {
            PlanningSnapshot queuedSnapshot = planningSnapshot;
            TypedPreviewAdmissionQueue queue = TYPED_PREVIEW_REQUESTS;
            TypedPreviewAdmissionQueue.OfferResult queued = queue.offer(
                    new TypedPreviewAdmissionQueue.Request(
                            player.getUUID(), previewGeneration, System.nanoTime(),
                            () -> tryBuildPlan(player, recipeId, forcedRecipes, dim, pos,
                                    repeatCount, baseItem, clickedOutput, requestId,
                                    previewGeneration, precomputedPlan, queuedSnapshot, true,
                                    pendingFallbackReason, true, sink, planStorageReference,
                                    materialLocks, routedPlan),
                            () -> {
                                PerformanceMonitor.recordTypedPreviewRejected(queue.size());
                                sink.error(Component.translatable(
                                        "rsi.plan.failure.planner_busy"));
                            }, failure -> {
                                RSIntegrationMod.LOGGER.error(
                                        "[RSI-plan] Deferred typed preview failed for {}",
                                        recipeId, failure);
                                sink.error(buildFailureMessage(failure, recipeId));
                            }));
            if (queued == TypedPreviewAdmissionQueue.OfferResult.FULL) {
                PerformanceMonitor.recordTypedPreviewRejected(queue.size());
                sink.error(Component.translatable("rsi.plan.failure.planner_busy"));
                return;
            }
            PerformanceMonitor.recordTypedPreviewQueued(
                    queued == TypedPreviewAdmissionQueue.OfferResult.REPLACED, queue.size());
            return;
        }

        // Arcane Iterator plans should start from the highest matching enchanted
        // book the player already owns, rather than always rebuilding from a plain
        // book. Use the NBT-aware availability snapshot so a different enchantment
        // (or a different level) can never satisfy this shortcut.
        if (levelBooks != null) {
            for (int lvl = levelBooks.length - 1; lvl >= 1; lvl--) {
                ItemStack wanted = levelBooks[lvl - 1];
                int matchingCount = available.entrySet().stream()
                        .filter(e -> MaterialMatcher.sameRuntimeFragment(e.getKey().toStack(), wanted))
                        .mapToInt(Map.Entry::getValue).sum();
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

        List<IngredientSpec> recursiveRecipeSpecs = recipeSpecs;
        if (FarmersDelightRecipeHandler.getCuttingBoardToolIngredient(recipe) != null) {
            recursiveRecipeSpecs = FarmersDelightRecipeHandler
                    .cuttingBoardGraphIngredients(recipeSpecs);
        }
        boolean directRecursiveTerminalPlan = !effectiveOverrides.isEmpty()
                ? false
                : DirectMaterialAllocator.allocate(recursiveRecipeSpecs, available).feasible();

        List<ResolutionStep> resolutionSteps = null;
        CraftPlanGraph planGraph = null;
        List<ResourceLocation> stepIds;
        boolean usedPurePlan = false;

        boolean selectedTypedResolver = false;
        if ((directTerminalPlan || directRecursiveTerminalPlan) && selectedPureResolver) {
            resolutionSteps = List.of();
        } else if (directRecursiveTerminalPlan) {
            resolutionSteps = List.of();
        } else if (canUsePrecomputedPlan(precomputedPlan) && selectedPureResolver) {
            resolutionSteps = PurePlanAdapter.toResolutionSteps(precomputedPlan,
                    planningSnapshot.recipeGraph());
            usedPurePlan = true;
        } else if (selectedPureResolver) {
            if (timedOutMissingRoute) {
                if (precomputedPlan.missing().isEmpty()) {
                    missing.add(demandTree.unresolved().itemId().toString());
                } else {
                    for (var unresolved : precomputedPlan.missing()) {
                        if (!unresolved.alternatives().isEmpty()) {
                            missing.add(unresolved.alternatives().get(0).itemId().toString());
                        }
                    }
                }
            } else {
                for (var unresolved : precomputedPlan.missing()) {
                    if (!unresolved.alternatives().isEmpty()) {
                        missing.add(unresolved.alternatives().get(0).itemId().toString());
                    }
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
                planGraph = usesPhysicalMachineInputSlots(recipe)
                        ? CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                                recursiveRecipeSpecs, available, player.serverLevel(),
                                player, network, missing, forcedOverrides, true, typedTimeoutMs,
                                activeRoot, materialLocks)
                        : CraftingResolver.resolveGraphForSpecsWithTypes(
                                recursiveRecipeSpecs, available, player.serverLevel(),
                                player, network, missing, forcedOverrides, true, typedTimeoutMs,
                                activeRoot, materialLocks);
            } catch (CraftingPlanningTimeoutException timeout) {
                PerformanceMonitor.recordResolveTimeout();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-plan] typed planner timeout recipe={} timeoutMs={} specs={} missing={} directReservation={} coverage={} unresolved={} catalystRoute={} targetCatalyst={} mainThreadOnly={} overrides={}",
                        recipeId, typedTimeoutMs, recursiveRecipeSpecs.size(), missing,
                        directTerminalPlan, demandTree.status(), demandTree.unresolved(),
                        demandTree.catalystRouteAvailable(), targetUsesReusableCatalyst,
                        planningSnapshot.mainThreadOnly(), !effectiveOverrides.isEmpty());
                if (missing.isEmpty()) {
                    sink.error(Component.translatable(
                            "rsi.plan.failure.dynamic_plan_unavailable"));
                } else {
                    sink.error(Component.translatable(
                            "rsi.generic.error.missing_materials",
                            CraftPacketUtils.formatMissingSummary(missing)));
                }
                return;
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-plan] Typed resolver failed for {}", recipeId, failure);
                sink.error(buildFailureMessage(failure, recipeId));
                return;
            } finally {
                PerformanceMonitor.recordTypedResolver(System.nanoTime() - typedResolverStarted);
            }
            Map<NodeId,
                    CraftNode> graphNodes = planGraph.nodesById();
            resolutionSteps = planGraph.topologicalOrder().stream()
                    .map(graphNodes::get)
                    .filter(Objects::nonNull)
                    .map(node -> new ResolutionStep(node.recipeId(), ModType.byId(node.modTypeId()),
                            node.recipeTypeId(), node.alternativeIds(), node.alternativeModTypeIds(),
                            node.inferMode(), node.executions(), node.syntheticInput(), node.syntheticOutput()))
                    .toList();
        }
        boolean usedTypedResolver = resolutionSteps != null && !resolutionSteps.isEmpty();

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
        if (selectedPureResolver || selectedTypedResolver) {
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
        if (usedPurePlan && resolutionSteps != null) {
            resolutionSteps = attachIndexedAlternatives(
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
        Map<Item, Integer> itemAvailable = new HashMap<>();
        for (var e : available.entrySet()) {
            itemAvailable.merge(e.getKey().item(), e.getValue(), Integer::sum);
        }
        // RecipeIndex for OR alternative material checks — allows alternatives
        // whose ingredients are craftable (not just directly available).
        // Display copy: decremented on each match so the same tag ingredient
        // showing up N times doesn't pick the same item N times (e.g. 1 cherry
        // + 1 acacia shown as 2 acacia when both are logs).
        Map<Item, Integer> displayAvailable = new HashMap<>(itemAvailable);

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            int totalItems = itemAvailable.values().stream().mapToInt(Integer::intValue).sum();
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

        List<ResourceLocation> mergedStepIds = new ArrayList<>();
        List<Integer> mergedBatchCounts = new ArrayList<>();
        List<ResolutionStep> mergedRepresentatives = new ArrayList<>();
        Map<String, Integer> mergeIndex = new HashMap<>();
        int mergeSrcCount = usedTypedResolver ? resolutionSteps.size() : stepIds.size();
        for (int mi = 0; mi < mergeSrcCount; mi++) {
            ResourceLocation id;
            int runs;
            if (usedTypedResolver) {
                ResolutionStep rs = resolutionSteps.get(mi);
                id = rs.recipeId();
                runs = Math.max(1, rs.executions());
            } else {
                id = stepIds.get(mi);
                runs = 1;
            }
            ResolutionStep representative = usedTypedResolver ? resolutionSteps.get(mi) : null;
            String mergeKey = id.toString();
            Recipe<?> mergeRecipe = resolveRecipe(player.serverLevel(), id);
            if (mergeRecipe != null && isSelfAmplifyingRecipe(
                    mergeRecipe, player.serverLevel().registryAccess())) {
                // Each stage consumes output from the preceding stage. Folding
                // 1, 2, 4 executions into one x7 step would require seven seed
                // items up front and destroy the producer dependency chain.
                mergeKey += "|amplification-stage:" + mi;
            }
            if (representative != null && representative.syntheticInput() != null
                    && representative.syntheticOutput() != null) {
                mergeKey += "|" + IngredientKey.of(representative.syntheticInput()).hashCode()
                        + "|" + IngredientKey.of(representative.syntheticOutput()).hashCode();
            } else if (representative != null && representative.syntheticOutput() != null) {
                mergeKey += "|state:" + representative.syntheticOutput().getTag();
            }
            Integer idx = mergeIndex.get(mergeKey);
            if (idx != null) {
                mergedBatchCounts.set(idx, mergedBatchCounts.get(idx) + runs);
            } else {
                mergeIndex.put(mergeKey, mergedStepIds.size());
                mergedStepIds.add(id);
                mergedBatchCounts.add(runs);
                mergedRepresentatives.add(representative);
            }
        }

        for (int si = 0; si < mergedStepIds.size(); si++) {
            ResourceLocation stepId = mergedStepIds.get(si);
            int batches = mergedBatchCounts.get(si);
            ResolutionStep mergedRs = si < mergedRepresentatives.size() ? mergedRepresentatives.get(si) : null;
            if (mergedRs != null && mergedRs.syntheticInput() != null && mergedRs.syntheticOutput() != null) {
                steps.add(new PlanStep(stepId, mergedRs.syntheticOutput().copy(), batches,
                        List.of(mergedRs.syntheticInput().copy()), Collections.emptyList(), mergedRs.modType(),
                        0, false, 0, 0, Collections.emptyList()));
                continue;
            }
            Recipe<?> stepRecipe = resolveRecipe(player.serverLevel(), stepId);
            if (stepRecipe == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Plan step recipe not found: {}",
                        stepId);
                continue;
            }
            ItemStack output = RecipeIndex.tryGetResultItem(
                    stepRecipe, player.serverLevel().registryAccess());
            if (stepRecipe instanceof SmithingTransformRecipe && mergedRs != null
                    && mergedRs.syntheticOutput() != null) {
                output = mergedRs.syntheticOutput().copy();
            }
            ModType stepModType = mergedRs != null ? mergedRs.modType()
                    : modTypeByRecipe.get(stepId);
            output = routeRecipeOutput(stepModType, output);
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
                List<DemandRole> craftingRoles = alignInputRoles(
                        craftingIngredients, extractPlanIngredientSpecs(scr));
                if (scr instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                    recipeW = shaped.getWidth();
                    recipeH = shaped.getHeight();
                    // Preserve grid positions — include empty slots as ItemStack.EMPTY
                    for (int inputIndex = 0; inputIndex < craftingIngredients.size(); inputIndex++) {
                        Ingredient ing = craftingIngredients.get(inputIndex);
                        if (ing.isEmpty()) {
                            inputs.add(ItemStack.EMPTY);
                        } else {
                            ItemStack matched = matchAndConsume(ing, displayAvailable);
                            inputs.add(matched != null ? matched : firstValidDisplayItem(ing));
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
                        ItemStack matched = matchAndConsume(ing, displayAvailable);
                        inputs.add(matched != null ? matched : firstValidDisplayItem(ing));
                        inputRoles.add(craftingRoles.get(inputIndex));
                    }
                }
            } else {
                // Multi-block recipe: use extractIngredientSpecs to preserve
                // per-ingredient counts (extractIngredients drops counts and
                // returns empty for wrappers like FaRitualWrapper).
                List<IngredientSpec> modSpecs = CraftPacketUtils.extractIngredientSpecs(stepRecipe);
                if (stepRecipe instanceof SmithingTransformRecipe smithing && mergedRs != null) {
                    modSpecs = SmithingRecipeHandler.requireDemandedOutputTag(
                            smithing, modSpecs, mergedRs.syntheticOutput());
                }
                modSpecs = MinersDelightCopperPotSupport.adaptIngredientSpecs(
                        stepModType, modSpecs, stepRecipe,
                        player.serverLevel().registryAccess());
                if (modSpecs != null) {
                    ModRecipeHandler stepHandler = ModRecipeHandlers.handlerFor(stepRecipe);
                    int stepInputIndex = 0;
                    for (IngredientSpec spec : modSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.role() == DemandRole.CATALYST && stepHandler != null
                                ? stepHandler.requiredIngredientCount(
                                        stepRecipe, spec, stepInputIndex, batches)
                                : spec.count();
                        stepInputIndex++;
                        ItemStack matched = matchAndConsume(spec.ingredient(), displayAvailable);
                        ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        consumeIngredientCount(spec.ingredient(), Math.max(0, cnt - 1), displayAvailable);
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
                    Recipe<?> altRecipe = resolveRecipe(player.serverLevel(), altId);
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
                    boolean hasMachine = AltarBindingRegistry.hasBindingForRecipe(player, altRecipe);
                    RSIntegrationMod.debug("[RSI-OR]   alt {}: hasMachine={}", altId, hasMachine);
                    if (isSmithingRecipe(altRecipe) || hasMachine) {
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
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.output().getItem()))
                        .append(" x").append(s.totalOutputCount())
                        .append(" [");
                for (ItemStack in : s.inputs()) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
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
        for (IngredientSpec spec : recipeSpecs) {
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
        if (timedOutMissingRoute && precomputedPlan.missing().isEmpty()) {
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
                    ItemStack matched = matchAndConsume(ing, displayAvailable, plannedOutputs);
                    ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(ing);
                    targetInputs.add(display);
                    targetInputRoles.add(displayInputRoles.get(inputIndex));
                }
            } else if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                targetW = shaped.getWidth();
                targetH = shaped.getHeight();
                for (int inputIndex = 0; inputIndex < displayIngredients.size(); inputIndex++) {
                    Ingredient ing = displayIngredients.get(inputIndex);
                    if (ing.isEmpty()) {
                        targetInputs.add(ItemStack.EMPTY);
                    } else {
                        ItemStack matched = matchAndConsume(ing, displayAvailable, plannedOutputs);
                        targetInputs.add(matched != null ? matched
                                : firstValidDisplayItem(ing));
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
                    ItemStack matched = matchAndConsume(ing, displayAvailable, plannedOutputs);
                    targetInputs.add(matched != null ? matched
                            : firstValidDisplayItem(ing));
                    targetInputRoles.add(displayInputRoles.get(inputIndex));
                }
            } else {
                // Mod recipe: linear layout — re-read specs for per-ingredient counts
                // (displayIngredients is no longer unrolled per-unit).
                List<IngredientSpec> targetSpecs = CraftPacketUtils.extractIngredientSpecs(recipe);
                if (targetSpecs != null
                        && recipe instanceof SmithingTransformRecipe smithingRecipe
                        && !selectedSmithingBase.isEmpty()) {
                    targetSpecs = SmithingRecipeHandler.requireExactBase(
                            smithingRecipe, targetSpecs, selectedSmithingBase);
                }
                if (targetSpecs != null) {
                    ModRecipeHandler targetHandler = ModRecipeHandlers.handlerFor(recipe);
                    int targetInputIndex = 0;
                    for (IngredientSpec spec : targetSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.role() == DemandRole.CATALYST && targetHandler != null
                                ? targetHandler.requiredIngredientCount(
                                        recipe, spec, targetInputIndex, repeatCount)
                                : spec.count();
                        targetInputIndex++;
                        ItemStack matched = matchAndConsume(spec.ingredient(), displayAvailable, plannedOutputs);
                        ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        consumeIngredientCount(spec.ingredient(), Math.max(0, cnt - 1),
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
                    // NBT guard: skip alternatives whose output NBT differs from target
                    if (targetHasTag) {
                        ItemStack altOut;
                        if (e.recipe() instanceof CraftingRecipe cr) {
                            altOut = cr.getResultItem(player.serverLevel().registryAccess());
                        } else {
                            altOut = ModRecipeHandlers.tryGetResultItem(e.recipe(), player.serverLevel().registryAccess());
                        }
                        if (!sameRecipeOutput(altOut, planTargetOutput)) continue;
                    }
                    // Gate by machine binding too — same rule the resolver and the
                    // intermediate-step alternatives use (hasBindingForRecipe: vanilla
                    // machines are always available, mod machines need a binding). Without
                    // this the target offered recipes for unbound machines (cooking pot,
                    // forge ritual, spirit crucible…) that the player can't actually run.
                    // Selecting this branch performs a fresh recursive plan, so
                    // current inventory must not decide whether the button exists.
                    if (isSmithingRecipe(e.recipe())
                            || AltarBindingRegistry.hasBindingForRecipe(player, e.recipe())) {
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
                        recipeModType.id(), player, recipe, dim, pos, planningEndpoint);
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
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(planTargetOutput.getItem()))
                        .append(" [");
                for (ItemStack in : targetInputs) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
                }
                sb.append("] grid=").append(targetW).append("x").append(targetH);
                RSIntegrationMod.LOGGER.debug(sb.toString());
            }
        }

        // Compute material availability for ALL items in the plan.
        // neededCounts: displayItem → how many needed (negative if produced > consumed)
        // itemSource: displayItem → original Ingredient (may be a tag, for aggregate counting)
        // Use a FRESH copy of itemAvailable — displayAvailable was already
        // mutated by the step/target display pass above, and reusing it here
        // causes matchBestAvailable fallback to return arbitrary items from
        // the ingredient array, producing bizarre material requirements.
        Map<Item, Integer> matAvailable = new HashMap<>(itemAvailable);
        Map<Item, Integer> neededCounts = new LinkedHashMap<>();
        Map<Item, Ingredient> itemSource = new HashMap<>();
        for (IngredientSpec spec : recipeSpecs) {
            if (spec.isEmpty()) continue;
            Map<Item, Integer> consumed = consumeIngredientCount(
                    spec.ingredient(), spec.count(), matAvailable);
            for (Map.Entry<Item, Integer> entry : consumed.entrySet()) {
                neededCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
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
                if (!isSelfAmplifyingRecipe(recipe, player.serverLevel().registryAccess())) {
                    neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
                }
                continue;
            }
            Recipe<?> stepRecipe = resolveRecipe(player.serverLevel(), step.recipeId());
            if (stepRecipe == null) continue;
            List<IngredientSpec> specs = stepRecipe instanceof CraftingRecipe craftingRecipe
                    ? CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe)
                    : CraftPacketUtils.extractIngredientSpecs(stepRecipe);
            if (specs != null) {
                ModRecipeHandler stepHandler = ModRecipeHandlers.handlerFor(stepRecipe);
                int stepInputIndex = 0;
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    ItemStack matched = matchAndConsume(spec.ingredient(), matAvailable);
                    if (matched != null) {
                        int required = stepHandler != null
                                ? stepHandler.requiredIngredientCount(
                                        stepRecipe, spec, stepInputIndex, step.batches())
                                : CraftPacketUtils.requiredCount(spec, step.batches());
                        neededCounts.merge(matched.getItem(),
                                required, Integer::sum);
                        itemSource.putIfAbsent(matched.getItem(), spec.ingredient());
                    }
                    stepInputIndex++;
                }
            }
        }
        // Subtract what intermediate steps produce (skip target — already done above).
        for (PlanStep step : steps) {
            if (step.recipeId().equals(recipeId)) continue;
            neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
        }

        // FA smithing: the target output shares the baseItem's Item type, so the
        // target-output subtraction above canceled out the baseItem.  Add it back
        // so the material panel shows the base item as a required material.
        if (faRecipe && baseItem != null && !baseItem.isEmpty()) {
            neededCounts.merge(baseItem.getItem(), repeatCount, Integer::sum);
        }

        // Add fuel requirement for machine recipes that consume fuel items.
        // CrockPot: any burnable item (coal, charcoal, etc.)
        // Aether: machine-specific fuel — fuel type depends on the machine BE,
        //   so we skip it here and rely on getPlanWarnings() to inform the user.
        CrockPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
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
            Recipe<?> intermediateRecipe = resolveRecipe(player.serverLevel(), intermediateRecipeId);
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
        Map<IngredientKey, Integer> leftovers = materialBill.leftovers();
        boolean feasible = directTerminalPlan || materialBill.feasible();
        boolean missingExecutionBinding = false;
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = resolveRecipe(player.serverLevel(), step.recipeId());
            if (stepRecipe == null) continue;
            ModType stepType = step.modType() != null
                    ? step.modType() : ModType.classifyRecipe(stepRecipe);
            if (requiresBoundMachine(stepRecipe, stepType)
                    && !hasBindingForExecutionType(player, stepRecipe, stepType)) {
                missingExecutionBinding = true;
                break;
            }
        }
        // A plan may still be displayed without a bound physical machine, but
        // it must never be advertised as executable in that state.
        if (missingExecutionBinding) feasible = false;

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            long shortageCount = materials.values().stream().filter(a -> !a.isEnough()).count();
            RSIntegrationMod.debug("[RSI-Generic] Plan for {}: {} steps, feasible={}, resolver={}, missing={}, shortages={}",
                    recipeId, steps.size(), feasible, usedTypedResolver ? "typed" : "fallback",
                    missing.size(), shortageCount);
        }
        // Dedup missing items — the resolver may add the same ingredient
        // name for every step that references it, producing an unreadable wall.
        List<String> dedupedMissing = missing.stream().distinct().toList();

        String targetName = com.huanghuang.rsintegration.util.ItemStackUtils.registryId(planTargetOutput);

        // ── Embers Alchemy: lookup cached codes from prior inference ──
        EmbersPlanInfo embersInfo = EmbersPlanInfo.build(
                player, recipe, network, planningEndpoint, recipeId,
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
                                        new PlanResponse.Availability(1, itemAvailable.getOrDefault(key.item(), 0)),
                                        (old, neu) -> new PlanResponse.Availability(
                                                old.needed(), Math.max(old.available(), neu.available())));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Catalyst injection into plan materials failed", e);
            }
            // Reassess feasibility after injecting catalyst items — the player
            // may be missing aspectus items that are required for execution.
            feasible = feasible && materials.values().stream().allMatch(PlanResponse.Availability::isEnough);
        }

        boolean executionMachineSupportsGui = false;
        if (dim != null && pos != null) {
            ServerLevel execLevel = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dim));
            if (execLevel != null) {
                executionMachineSupportsGui = BindingEventHandler
                        .supportsGuiAt(execLevel, pos);
            }
        }

        // v3.4 availability passport: collect modTypes the player has bound machines for.
        Set<String> boundMachineTypes = new java.util.LinkedHashSet<>();
        // Vanilla furnace/blast/smoker/stonecutter recipes (all classified as
        // vanilla_furnace, classifyRecipe → null) need no bound machine — they're
        // always usable. Seed the passport so their alternatives render as normal
        // selectable badges instead of grayed/locked in the tree dropdown.
        boundMachineTypes.add("vanilla_furnace");
        for (ModType mt : ModType.values()) {
            if (AltarBindingRegistry.hasAnyBindingForType(player, mt)) {
                boundMachineTypes.add(mt.id());
            }
        }

        boolean allExecutionMachinesLeased = false;
        if (recipeModType != null && boundMachineTypes.contains(recipeModType.id())) {
            List<AltarBindingRegistry.BoundMachine> executionMachines =
                    AltarBindingRegistry.getBoundMachinesForRecipe(
                            player, recipeModType, recipeId);
            executionMachines = LoadBalancer.filterAvailable(executionMachines, player.getServer());
            allExecutionMachinesLeased = !executionMachines.isEmpty()
                    && AsyncCraftManager.getInstance()
                    .areAllMachinesLeased(executionMachines, recipeModType.id());
            if (allExecutionMachinesLeased) feasible = false;
        }

        if (!feasible) {
            boolean nbtMismatch = hasNbtMismatch(materials, itemAvailable);
            boolean bindingBlocked = precomputedPlan != null
                    && missingTouchesBlockedOutput(precomputedPlan.missing(),
                    planningSnapshot.bindingBlockedOutputIds());
            if (allExecutionMachinesLeased) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.machines_leased"));
            } else if (bindingBlocked) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.no_bound_machine"));
            } else if (nbtMismatch) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.nbt_mismatch"));
            } else if (!dedupedMissing.isEmpty() || materials.values().stream()
                    .anyMatch(a -> !a.isEnough())) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.missing_materials"));
            } else if (missingExecutionBinding || (recipeModType != null
                    && !boundMachineTypes.contains(recipeModType.id()))) {
                modWarnings.add(Component.translatable(
                        "rsi.plan.failure.no_bound_machine"));
            }
        }

        long totalBotaniaMana = 0L;
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                    .byKey(step.recipeId()).orElse(null);
            int manaPerBatch = stepRecipe == null ? 0 : PlanWarnings.botaniaManaCost(stepRecipe);
            if (manaPerBatch > 0) {
                totalBotaniaMana = Math.min(Long.MAX_VALUE,
                        totalBotaniaMana + (long) manaPerBatch * Math.max(1, step.batches()));
            }
        }
        if (totalBotaniaMana > 0) {
            modWarnings.add(net.minecraft.network.chat.Component.translatable(
                    "rsi.botania.warn.total_mana_required", totalBotaniaMana));
        }

        long totalArsSource = 0L;
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                    .byKey(step.recipeId()).orElse(null);
            int sourcePerBatch = stepRecipe == null ? 0 : PlanWarnings.arsSourceCost(stepRecipe);
            if (sourcePerBatch > 0) {
                long stepTotal = (long) sourcePerBatch * Math.max(1, step.batches());
                totalArsSource = stepTotal > Long.MAX_VALUE - totalArsSource
                        ? Long.MAX_VALUE
                        : totalArsSource + stepTotal;
            }
        }
        if (totalArsSource > 0) {
            modWarnings.add(Component.translatable(
                    "rsi.ars_nouveau.warn.total_source_required",
                    String.format("%,d", totalArsSource)));
        }

        long totalGoetyRitualSouls = 0L;
        long totalGoetyTotemSouls = 0L;
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                    .byKey(step.recipeId()).orElse(null);
            if (stepRecipe == null) continue;
            long executions = Math.max(1, step.batches());
            long ritualCost = (long) PlanWarnings.goetyRitualSoulCost(stepRecipe) * executions;
            totalGoetyRitualSouls = ritualCost > Long.MAX_VALUE - totalGoetyRitualSouls
                    ? Long.MAX_VALUE : totalGoetyRitualSouls + ritualCost;
            long totemCost = (long) PlanWarnings.goetyTotemSoulCost(stepRecipe) * executions;
            totalGoetyTotemSouls = totemCost > Long.MAX_VALUE - totalGoetyTotemSouls
                    ? Long.MAX_VALUE : totalGoetyTotemSouls + totemCost;
        }
        if (totalGoetyRitualSouls > 0) {
            modWarnings.add(Component.translatable(
                    "rsi.goety.warn.total_ritual_souls_required",
                    String.format("%,d", totalGoetyRitualSouls)));
        }
        if (totalGoetyTotemSouls > 0) {
            int availableTotemSouls = GoetySoulTotemCrafting.maxAvailableSouls(available);
            if (availableTotemSouls < totalGoetyTotemSouls) {
                feasible = false;
                modWarnings.add(Component.translatable(
                        "rsi.goety.warn.totem_souls_insufficient",
                        String.format("%,d", totalGoetyTotemSouls),
                        String.format("%,d", availableTotemSouls)));
            } else {
                modWarnings.add(Component.translatable(
                        "rsi.goety.warn.total_totem_souls_required",
                        String.format("%,d", totalGoetyTotemSouls),
                        String.format("%,d", availableTotemSouls)));
            }
        }

        List<MachineCandidateView> machineCandidates = recipeModType != null
                && ModIds.GOETY.equals(recipeModType.id())
                ? com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate
                .getPlanMachineCandidates(player, recipe)
                : List.of();

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
                selectedStorageReference,
                storageNetworks
        );

        int responseStepCount = steps.size();
        int responseGraphNodes = planGraphView != null ? planGraphView.nodes().size() : 0;
        boolean responseFeasible = feasible;
        CraftPlanGraph resolvedGraphForCache = planGraph;
        PLAN_REQUESTS.submitResponse(planningSnapshot, responseDraft, player.getServer()::execute,
                current -> reuseValidatedSnapshot
                        ? isSnapshotActive(player, current, previewGeneration)
                        : PlanningStateValidator.revalidate(player, current,
                        planDimKey, planLookupPos, PLAN_REQUESTS, planStorageReference),
                plan -> {
                    if (previewGeneration != 0L
                            && !PLAN_REQUESTS.isCurrent(player.getUUID(), previewGeneration)) {
                        RSIntegrationMod.debug("[RSI-tryBuildPlan] Discarding stale finalized response: recipeId={}",
                                recipeId);
                        return;
                    }
                    PLAN_CACHE.put(cacheKey, plan, planningSnapshot,
                            canUsePrecomputedPlan(precomputedPlan) ? precomputedPlan : null,
                            resolvedGraphForCache, System.nanoTime());
                    RSIntegrationMod.debug("[RSI-tryBuildPlan] SENDING PlanResponsePacket: recipeId={} steps={} graphNodes={} feasible={} player={}",
                            recipeId, responseStepCount, responseGraphNodes,
                            responseFeasible, player.getGameProfile().getName());
                    sink.success(plan, planningSnapshot);
                    PerformanceMonitor.recordPlanBuild(System.nanoTime() - planStartNanos,
                            plan.graph() != null ? plan.graph().nodes().size() : responseStepCount);
                    RSIntegrationMod.debug("[RSI-tryBuildPlan] PlanResponsePacket SENT: recipeId={}", recipeId);
                }, failure -> {
                    if (failure instanceof RejectedExecutionException) {
                        sink.error(Component.translatable("rsi.plan.failure.planner_busy"));
                    } else if (!(failure instanceof CancellationException)
                            && !(failure instanceof com.huanghuang.rsintegration.crafting.planning
                            .AsyncPlanningCoordinator.StalePlanningResultException)) {
                        RSIntegrationMod.LOGGER.error("[RSI-plan] Response finalization failed for {}",
                                recipeId, failure);
                        sink.error(buildFailureMessage(failure, recipeId));
                    }
                });
    }

    static boolean shouldUseAsyncPurePreview(
            boolean pureRoute, boolean directTerminalPlan) {
        return pureRoute && !directTerminalPlan;
    }

    static boolean shouldSubmitRoutedPreview(boolean directTerminalPlan, boolean mainThreadOnly,
                                              boolean hasOverrides, boolean hasMaterialLocks) {
        return shouldUseAsyncPurePreview(!mainThreadOnly && !hasOverrides && !hasMaterialLocks,
                directTerminalPlan);
    }

    static boolean hasNbtMismatch(Map<IngredientKey, PlanResponse.Availability> materials,
                                  Map<Item, Integer> itemAvailable) {
        for (Map.Entry<IngredientKey, PlanResponse.Availability> entry : materials.entrySet()) {
            PlanResponse.Availability availability = entry.getValue();
            if (availability.isEnough() || !entry.getKey().stack(1).hasTag()) continue;
            if (itemAvailable.getOrDefault(entry.getKey().item(), 0) >= availability.needed()) {
                return true;
            }
        }
        return false;
    }

    static String purePlanningFailureKey(PureRecipePlanner.Result result,
                                         Map<StackKey, Integer> availableItems) {
        if (result.status() == PureRecipePlanner.Status.TIME_LIMIT
                || result.status() == PureRecipePlanner.Status.SEARCH_LIMIT
                || result.status() == PureRecipePlanner.Status.STEP_LIMIT) {
            return "rsi.plan.failure.complexity_limit";
        }
        return hasPureNbtMismatch(result.missing(), availableItems)
                ? "rsi.plan.failure.nbt_mismatch"
                : "rsi.plan.failure.missing_materials";
    }

    private static List<String> formatPureMissingMaterials(
            List<ImmutableRecipeGraph.IngredientRef> missing) {
        List<String> result = new ArrayList<>();
        for (ImmutableRecipeGraph.IngredientRef demand : missing) {
            if (demand == null || demand.alternatives().isEmpty()) continue;
            String item = demand.alternatives().get(0).itemId().toString();
            result.add(item + " x" + demand.count());
        }
        return result;
    }

    static boolean missingTouchesBlockedOutput(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Set<ResourceLocation> blockedOutputIds) {
        if (missing == null || missing.isEmpty() || blockedOutputIds == null
                || blockedOutputIds.isEmpty()) return false;
        return missing.stream().anyMatch(demand -> !demand.alternatives().isEmpty()
                && demand.alternatives().stream().allMatch(material ->
                blockedOutputIds.contains(material.itemId())));
    }

    static boolean shouldRetryTypedPlanning(
            PureRecipePlanner.Result result,
            Set<ResourceLocation> blockedOutputIds) {
        return result != null
                && result.feasibility() == PureRecipePlanner.Feasibility.INFEASIBLE
                && missingTouchesBlockedOutput(result.missing(), blockedOutputIds);
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
                || reusableCatalystOutputIds.isEmpty()) return true;

        // A typed retry is useful only when every remaining shortage can actually be
        // solved by catalyst-aware semantics. Independent raw shortages are already a
        // conclusive result and should be shown immediately instead of spending server
        // thread time in the legacy resolver before reporting the same failure.
        return purePlan.missing().stream().allMatch(demand ->
                !demand.alternatives().isEmpty()
                        && demand.alternatives().stream().allMatch(material ->
                        reusableCatalystOutputIds.contains(material.itemId())));
    }

    static boolean hasPureNbtMismatch(
            List<ImmutableRecipeGraph.IngredientRef> missing,
            Map<StackKey, Integer> availableItems) {
        if (missing == null || missing.isEmpty() || availableItems == null
                || availableItems.isEmpty()) return false;

        Map<ImmutableRecipeGraph.MaterialRef, Integer> projected =
                ImmutableRecipeGraphProjector.projectAvailability(availableItems);
        for (ImmutableRecipeGraph.IngredientRef demand : missing) {
            if (demand.nbtMatchMode() == ImmutableRecipeGraph.NbtMatchMode.ANY) continue;
            Set<ResourceLocation> itemIds = demand.alternatives().stream()
                    .map(ImmutableRecipeGraph.MaterialRef::itemId)
                    .collect(Collectors.toSet());
            long sameItemStock = projected.entrySet().stream()
                    .filter(entry -> itemIds.contains(entry.getKey().itemId()))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
            if (sameItemStock >= demand.count()) return true;
        }
        return false;
    }

    static MarketTradeAvailability marketTradeAvailability(
            com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper recipe,
            Map<StackKey, Integer> available, int repeatCount) {
        ItemStack cost = recipe.costItem();
        Ingredient costIngredient = Ingredient.of(cost);
        long matching = 0L;
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            if (entry.getValue() > 0 && IngredientMatcher.test(costIngredient, entry.getKey().toStack())) {
                matching = Math.min(Integer.MAX_VALUE, matching + entry.getValue());
            }
        }
        long required = (long) Math.max(1, cost.getCount()) * Math.max(1, repeatCount);
        int boundedRequired = (int) Math.min(Integer.MAX_VALUE, required);
        int boundedAvailable = (int) matching;
        return new MarketTradeAvailability(boundedRequired, boundedAvailable,
                matching >= required);
    }

    record MarketTradeAvailability(int required, int available, boolean feasible) {}

    private static boolean tryBuildDirectMarketPlan(
            ServerPlayer player,
            com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper recipe,
            ResourceLocation recipeId, int repeatCount, ItemStack targetOutput,
            @Nullable ResourceLocation dim, @Nullable net.minecraft.core.BlockPos pos,
            Map<StackKey, Integer> available, PlanningSnapshot snapshot,
            PlanCache.Key cacheKey, long planStartNanos, PlanResultSink sink) {
        MarketTradeAvailability trade = marketTradeAvailability(recipe, available, repeatCount);
        if (!trade.feasible()) return false;

        ItemStack cost = recipe.costItem();
        ModType modType = ModType.FARMINGFORBLOCKHEADS_MARKET;
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(cost),
                new PlanResponse.Availability(trade.required(), trade.available()));
        List<PlanStep> steps = List.of(new PlanStep(recipeId, targetOutput, repeatCount,
                List.of(cost), Collections.emptyList(), modType, 0, false));
        List<Component> warnings = MarketBatchDelegate.getPlanWarnings(player, recipe, dim, pos);
        boolean supportsGui = false;
        if (dim != null && pos != null) {
            ServerLevel level = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dim));
            supportsGui = level != null && BindingEventHandler.supportsGuiAt(level, pos);
        }
        Set<String> boundTypes = Set.of("vanilla_furnace", modType.id());
        PlanResponse response = new PlanResponseDraft(
                true,
                com.huanghuang.rsintegration.util.ItemStackUtils.registryId(targetOutput),
                targetOutput,
                steps,
                materials,
                List.of(),
                recipeId.toString(),
                modType.id(),
                dim != null ? dim.toString() : null,
                pos != null ? pos.getX() : 0,
                pos != null ? pos.getY() : 0,
                pos != null ? pos.getZ() : 0,
                warnings,
                repeatCount,
                null, null, null, 0L, false, false,
                supportsGui,
                null,
                boundTypes,
                Map.of(),
                null,
                null,
                false).toResponse();

        if (snapshot.requestGeneration() != 0L
                && !PLAN_REQUESTS.isCurrent(player.getUUID(), snapshot.requestGeneration())) {
            return true;
        }
        PLAN_CACHE.put(cacheKey, response, snapshot, System.nanoTime());
        sink.success(response, snapshot);
        PerformanceMonitor.recordPlanBuild(System.nanoTime() - planStartNanos, 1);
        RSIntegrationMod.debug(
                "[RSI-Market] Direct plan: recipe={} repeat={} required={} available={}",
                recipeId, repeatCount, trade.required(), trade.available());
        return true;
    }

    static List<IngredientSpec> extractPlanIngredientSpecs(CraftingRecipe recipe) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        return specs == null || specs.isEmpty()
                ? CraftPacketUtils.extractCraftingIngredientSpecs(recipe)
                : specs;
    }

    /**
     * Returns the best ItemStack from {@code ingredient.getItems()} — the one with
     * the highest available count in player inventory + RS network.
     * Preserves NBT so TACZ/Applied Armorer items display with correct attachments.
     * Strips BlockEntityTag/BlockId to avoid purple-black block entity rendering.
     */
    private static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Integer> itemAvailable) {
        return matchBestAvailable(ingredient, itemAvailable, java.util.Collections.emptySet());
    }

    /**
     * Collapse a (possibly tag) ingredient to a single representative for display.
     * Prefers the tag member with the most stock. When NO member is in stock, a
     * {@code preferred} member — one the plan actually produces — wins over the
     * arbitrary first tag entry, so a wood-tag gun slot shows the crafted oak_log
     * instead of dark_oak_log while the tree below builds oak_log.
     */
    private static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Integer> itemAvailable,
                                                java.util.Set<Item> preferred) {
        ItemStack best = null;
        int bestCount = -1;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            int count = itemAvailable.getOrDefault(stack.getItem(), 0);
            if (count > bestCount) {
                bestCount = count;
                best = stack;
            }
        }
        // Real stock wins outright.
        if (best != null && bestCount > 0) return cleanDisplayNbt(best.copy());
        // Nothing in stock: prefer a member the plan actually produces.
        if (!preferred.isEmpty()) {
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty() && preferred.contains(stack.getItem())) {
                    return cleanDisplayNbt(stack.copy());
                }
            }
        }
        if (best != null) return cleanDisplayNbt(best.copy());
        // Fallback: first non-empty item from the ingredient.
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return cleanDisplayNbt(stack.copy());
        }
        return null;
    }

    /** Strip BlockEntityTag and BlockId from NBT so items render as items,
     *  not as placed blocks (which lack item models → purple-black). */
    private static ItemStack cleanDisplayNbt(ItemStack stack) {
        if (!stack.hasTag()) return stack;
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove("BlockEntityTag");
            tag.remove("BlockId");
            if (tag.isEmpty()) stack.setTag(null);
        }
        return stack;
    }

    /**
     * Returns the first non-empty ItemStack from the ingredient, or
     * ItemStack.EMPTY if every entry is empty. Never returns a stack of air.
     */
    private static ItemStack firstValidDisplayItem(Ingredient ing) {
        for (ItemStack s : ing.getItems()) {
            if (!s.isEmpty()) return s.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Like {@link #matchBestAvailable} but also decrements the matched item
     * from the availability map so subsequent calls for the same tag-ingredient
     * spread across different valid items instead of always picking the same one.
     */
    private static ItemStack matchAndConsume(Ingredient ingredient, Map<Item, Integer> available) {
        return matchAndConsume(ingredient, available, java.util.Collections.emptySet());
    }

    private static ItemStack matchAndConsume(Ingredient ingredient, Map<Item, Integer> available,
                                             java.util.Set<Item> preferred) {
        ItemStack matched = matchBestAvailable(ingredient, available, preferred);
        if (matched != null) {
            available.merge(matched.getItem(), -1, Integer::sum);
        }
        return matched;
    }

    /**
     * Consumes up to {@code amount} matching items in bulk.  The old preview
     * path called {@link #matchAndConsume} once per required unit, which made a
     * 1024-craft request perform thousands of identical scans.  Choosing each
     * stocked ingredient once produces the same greedy result while keeping the
     * work proportional to the number of tag members.
     */
    static Map<Item, Integer> consumeIngredientCount(Ingredient ingredient, int amount,
                                                       Map<Item, Integer> available) {
        return consumeIngredientCount(ingredient, amount, available,
                java.util.Collections.emptySet());
    }

    private static Map<Item, Integer> consumeIngredientCount(Ingredient ingredient, int amount,
                                                               Map<Item, Integer> available,
                                                               java.util.Set<Item> preferred) {
        if (ingredient == null || ingredient.isEmpty() || amount <= 0) return Map.of();

        List<Item> candidates = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && seen.add(stack.getItem())) candidates.add(stack.getItem());
        }
        candidates.sort((left, right) -> Integer.compare(
                available.getOrDefault(right, 0), available.getOrDefault(left, 0)));

        Map<Item, Integer> consumed = new LinkedHashMap<>();
        int remaining = amount;
        for (Item item : candidates) {
            int stocked = Math.max(0, available.getOrDefault(item, 0));
            if (stocked <= 0) continue;
            int take = Math.min(remaining, stocked);
            available.put(item, stocked - take);
            consumed.put(item, take);
            remaining -= take;
            if (remaining == 0) break;
        }
        return consumed;
    }

    /** Give item to player only if still connected. Prevents ghost items
     *  when a player disconnects mid-batch and the item is voided. */
    private static void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
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
        PLAN_CACHE.clear();
    }

    static boolean canUsePrecomputedPlan(@Nullable PureRecipePlanner.Result result) {
        return result != null && result.feasible();
    }

    static boolean canOpenBoundedMissingPlan(
            @Nullable PureRecipePlanner.Result plan,
            PureDemandTreeInspector.Result demandTree) {
        return plan != null
                && plan.feasibility() == PureRecipePlanner.Feasibility.UNKNOWN
                && demandTree.status() == PureDemandTreeInspector.Status.MISSING_MATERIALS
                && demandTree.unresolved() != null;
    }

    /** Projects each physical stack once so broad and exact-NBT demands cannot reuse it. */
    private static Map<ImmutableRecipeGraph.MaterialRef, Integer> routingAvailability(
            Map<StackKey, Integer> available) {
        Map<ImmutableRecipeGraph.MaterialRef, Integer> projected = new HashMap<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || count <= 0) continue;
            String nbt = entry.getKey().tag() == null ? "" : entry.getKey().tag();
            var material = new ImmutableRecipeGraph.MaterialRef(itemId, nbt);
            projected.merge(material, count,
                    (left, right) -> (int) Math.min(Integer.MAX_VALUE, (long) left + right));
        }
        return Map.copyOf(projected);
    }

    static List<ResolutionStep> attachIndexedAlternatives(
            List<ResolutionStep> steps,
            Map<Item, List<RecipeIndex.Entry>> recipeIndex,
            net.minecraft.core.RegistryAccess access) {
        if (steps.isEmpty() || recipeIndex.isEmpty()) return steps;

        Map<ResourceLocation, RecipeIndex.Entry> entriesById = new LinkedHashMap<>();
        for (List<RecipeIndex.Entry> entries : recipeIndex.values()) {
            for (RecipeIndex.Entry entry : entries) {
                entriesById.putIfAbsent(entry.recipe().getId(), entry);
            }
        }

        List<ResolutionStep> enriched = new ArrayList<>(steps.size());
        for (ResolutionStep step : steps) {
            RecipeIndex.Entry primary = entriesById.get(step.recipeId());
            if (primary == null) {
                enriched.add(step);
                continue;
            }
            ItemStack output = ModRecipeHandlers.tryGetResultItem(primary.recipe(), access);
            List<RecipeIndex.Entry> sameItem = output.isEmpty()
                    ? null : recipeIndex.get(output.getItem());
            if (sameItem == null || sameItem.size() < 2) {
                enriched.add(step);
                continue;
            }

            LinkedHashMap<ResourceLocation, String> alternatives = new LinkedHashMap<>();
            for (RecipeIndex.Entry candidate : sameItem) {
                ResourceLocation candidateId = candidate.recipe().getId();
                if (candidateId.equals(step.recipeId())) continue;
                ItemStack candidateOutput = ModRecipeHandlers.tryGetResultItem(candidate.recipe(), access);
                if (!sameRecipeOutput(output, candidateOutput)) continue;
                alternatives.putIfAbsent(candidateId, candidate.modType().id());
            }
            if (alternatives.isEmpty()) {
                enriched.add(step);
                continue;
            }
            enriched.add(new ResolutionStep(step.recipeId(), step.modType(), step.recipeTypeId(),
                    List.copyOf(alternatives.keySet()), List.copyOf(alternatives.values()),
                    step.inferMode(), step.executions(), step.syntheticInput(), step.syntheticOutput()));
        }
        return List.copyOf(enriched);
    }

    private static boolean sameRecipeOutput(ItemStack left, ItemStack right) {
        return MaterialMatcher.equivalentRuntimeFragment(left, right)
                || (net.minecraftforge.fml.ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)
                && com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog
                .sameSpellScroll(left, right));
    }

    /**
     * Some machine recipes (notably Malum SpiritFocusingRecipe) implement
     * CraftingRecipe for JEI/recipe-manager compatibility.  That interface
     * does not mean they can be executed in a vanilla crafting grid.
     */
    static boolean isPhysicalMachineRecipe(Recipe<?> recipe, @Nullable ModType modType) {
        return modType != null
                && modType != ModType.GENERIC
                && !modType.isVirtual()
                && modType != ModType.byId("smithing");
    }

    static boolean usesPhysicalMachineInputSlots(Recipe<?> recipe) {
        ModType type = ModType.classifyRecipe(recipe);
        if (recipe instanceof CraftingRecipe && type == ModType.CUSTOM_GUI) return false;
        return isPhysicalMachineRecipe(recipe, type) && recipe.getType() != null;
    }

    static boolean requiresTypedTerminalExecution(Recipe<?> recipe,
                                                   @Nullable ModType modType) {
        return modType != null
                && (modType.isVirtual() || isPhysicalMachineRecipe(recipe, modType));
    }

    static boolean requiresBoundMachine(@Nullable ModType modType) {
        return modType != null && modType != ModType.GENERIC && !modType.isVirtual();
    }

    static ModType resolveExecutionModType(ServerPlayer player, Recipe<?> recipe,
                                                    @Nullable ResourceLocation dim,
                                                    @Nullable net.minecraft.core.BlockPos pos) {
        if (MinersDelightCopperPotSupport.isRequestedCopperPot(player, recipe, dim, pos)) {
            ModType copperPot = ModType.findById(ModIds.ID_MD_COPPER_POT);
            if (copperPot != null) return copperPot;
        }
        return ModType.classifyRecipe(recipe);
    }

    private static boolean hasBindingForExecutionType(ServerPlayer player, Recipe<?> recipe,
                                                       @Nullable ModType executionType) {
        if (executionType != null && ModIds.ID_MD_COPPER_POT.equals(executionType.id())) {
            return AltarBindingRegistry.hasAnyBindingForType(player, executionType);
        }
        return AltarBindingRegistry.hasBindingForRecipe(player, recipe);
    }

    private static void logBindingRejection(String phase, ServerPlayer player, Recipe<?> recipe,
                                            @Nullable ModType executionType,
                                            @Nullable ResourceLocation dim,
                                            @Nullable net.minecraft.core.BlockPos pos) {
        ResourceLocation recipeTypeId = recipe.getType() == null
                ? null : ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        ResourceLocation serializerId = recipe.getSerializer() == null
                ? null : BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Binding] rejected phase={} recipe={} class={} recipeType={} serializer={} "
                        + "modType={} craftingRecipe={} physicalMachine={} player={} dim={} pos={}",
                phase, recipe.getId(), recipe.getClass().getName(), recipeTypeId, serializerId,
                executionType == null ? "null" : executionType.id(),
                recipe instanceof CraftingRecipe,
                isPhysicalMachineRecipe(recipe, executionType),
                player.getGameProfile().getName(), dim, pos);
    }

    private static ItemStack routeRecipeOutput(@Nullable ModType executionType, ItemStack output) {
        return executionType != null && ModIds.ID_MD_COPPER_POT.equals(executionType.id())
                ? MinersDelightCopperPotSupport.convertResult(output)
                : output;
    }

    static boolean requiresBoundMachine(Recipe<?> recipe, @Nullable ModType modType) {
        // Config-classified custom GUI recipes can still be ordinary vanilla
        // CraftingRecipe instances (for example a mod's helper recipe).  They
        // must remain logical crafting; only the actual custom machine recipe
        // needs a binding context.
        if (recipe instanceof CraftingRecipe && modType == ModType.CUSTOM_GUI) return false;
        if (recipe instanceof CraftingRecipe && !isPhysicalMachineRecipe(recipe, modType)) return false;
        return recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe
                || requiresBoundMachine(modType);
    }

    private static boolean isSmithingRecipe(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe || recipe instanceof SmithingTrimRecipe;
    }

    static boolean isSelfAmplifyingRecipe(Recipe<?> recipe,
                                          net.minecraft.core.RegistryAccess access) {
        ItemStack output = RecipeIndex.tryGetResultItem(recipe, access);
        if (output.isEmpty()) return false;
        List<IngredientSpec> specs = recipe instanceof CraftingRecipe crafting
                ? CraftPacketUtils.extractCraftingIngredientSpecs(crafting)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
        return specs != null && SelfAmplifyingRecipePolicy.isSelfAmplifying(specs, output);
    }

    static boolean matchesAsyncRequest(PlanningSnapshot snapshot, UUID playerId,
                                       ResourceLocation recipeId, long requestGeneration,
                                       Map<ResourceLocation, ResourceLocation> forcedRecipes) {
        return snapshot.playerId().equals(playerId)
                && snapshot.recipeId().equals(recipeId)
                && snapshot.requestGeneration() == requestGeneration
                && snapshot.forcedRecipes().equals(forcedRecipes);
    }

    static boolean shouldFallbackAfterAsyncFailure(Throwable failure) {
        return SynchronousFallbackReason.fromAsyncFailure(failure).isPresent();
    }

    public static void onPlayerLogout(UUID playerId) {
        clearPlanResultGates(playerId);
        WARM_UP_REQUESTS.removePlayer(playerId);
        EXECUTION_REQUESTS.removePlayer(playerId);
        TYPED_PREVIEW_REQUESTS.remove(playerId);
        PLAN_REQUESTS.forget(playerId);
        PLAN_CACHE.removePlayer(playerId);
    }

    /** Cancels preview workers during server shutdown before world objects are torn down. */
    public static synchronized void cancelAllPlanning() {
        clearPlanResultGates(null);
        PlanRequestService stopped = PLAN_REQUESTS;
        PLAN_REQUESTS = newDefaultPlanRequestService();
        stopped.close();
        WARM_UP_REQUESTS.clear();
        EXECUTION_REQUESTS.clear();
        TYPED_PREVIEW_REQUESTS.clear();
        ImmutableRecipeGraphProjector.clearCache();
    }

    /** Applies server-configured planner resource limits by replacing the bounded executor. */
    public static synchronized void reloadPlanningConfig() {
        clearPlanResultGates(null);
        PlanRequestService previous = PLAN_REQUESTS;
        PLAN_REQUESTS = newPlanRequestService();
        previous.close();
        TypedPreviewAdmissionQueue previousTyped = TYPED_PREVIEW_REQUESTS;
        TYPED_PREVIEW_REQUESTS = new TypedPreviewAdmissionQueue(
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY.get());
        previousTyped.clear();
    }

}
