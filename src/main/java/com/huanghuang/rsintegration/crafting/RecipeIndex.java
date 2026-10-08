package com.huanghuang.rsintegration.crafting;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingCatalog;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeDefinition;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog;
import com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.function.LongSupplier;
import net.minecraft.core.Registry;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified recipe index replacing the split {@code CraftingPlanManager} +
 * {@code ModRecipeIndex} dual-index pattern.
 *
 * <p>Single build pass, delegates extraction to {@link ModRecipeHandler}
 * when available, producing {@code Map<Item, List<Entry>>} for both vanilla
 * and mod recipe lookups.</p>
 */
public final class RecipeIndex {

    public record Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId, boolean nbtSensitive) {
        public Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId) {
            this(recipe, modType, recipeTypeId, modType != ModType.GENERIC);
        }
    }

    public record ReusableCatalystRoute(ResourceLocation recipeId, ItemStack output,
                                        List<IngredientSpec> specs) {
        public ReusableCatalystRoute {
            output = output.copy();
            specs = List.copyOf(specs);
        }
    }

    private static volatile PublishedGeneration generation;
    private static volatile boolean generationBuildFailed;
    private static volatile long failedRevision;
    private static long buildEpoch;
    private static BuildSession activeBuild;
    static final long TICK_BUDGET_NANOS = 8_000_000L;
    private static final AtomicBoolean BUILD_IN_FLIGHT = new AtomicBoolean();
    private static volatile long lastDriftCheckTick = Long.MIN_VALUE;
    private static final ExecutorService WARMUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RSI-RecipeCatalog");
        thread.setDaemon(true);
        return thread;
    });

    private RecipeIndex() {}

    public static boolean isReady(Level level) {
        PublishedGeneration ready = generation;
        return ready != null
                && ready.manager == level.getRecipeManager()
                && ready.revision == CraftPlanningRevision.current()
                && ImmutableRecipeGraphProjector.isReady(level);
    }

    public static boolean generationBuildFailed() {
        return generationBuildFailed && failedRevision == CraftPlanningRevision.current();
    }

    /** Rebuilds once when late spell-config synchronization changes ink mappings. */
    public static void refreshDynamicRuntimeIfNeeded(Level level) {
        requireServerLevel(level);
        if (!ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)) return;
        // Config publication can invalidate an in-flight generation after its
        // revision was captured. Retry on subsequent ticks until it is rebuilt;
        // warmUp's in-flight guard keeps this to one worker at a time.
        if (!isReady(level)) {
            if (!generationBuildFailed()) warmUp(level);
            return;
        }
        long tick = level.getGameTime();
        if (!driftCheckDue(tick, lastDriftCheckTick)) return;
        lastDriftCheckTick = tick;
        long started = System.nanoTime();
        try {
            // 指纹读取第三方法术和注册表，必须留在服务端线程。
            if (IronSpellBooksRecipeCatalog.hasRuntimeDrift()) {
                CraftPlanningRevision.bump();
                invalidate();
                warmUp(level);
            }
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] Iron spell drift check failed; retaining current recipe generation", failure);
        } finally {
            logSlowUnit("spell runtime drift", System.nanoTime() - started);
        }
    }

    static boolean driftCheckDue(long tick, long previousTick) {
        return previousTick == Long.MIN_VALUE || tick < previousTick || tick - previousTick >= 100;
    }

    private static void requireServerLevel(Level level) {
        MinecraftServer server = PlanningThreadContext.requireServerThread("recipe catalog capture");
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() != server) {
            throw new IllegalArgumentException("Recipe catalog requires the current server level");
        }
    }

    /**
     * 在服务端线程启动采集会话，不在此处扫描配方；后续 tick 分片推进。
     * 采集完成后后台整理纯数据，主线程校验版本并发布完整快照。
     */
    public static synchronized void warmUp(Level level) {
        if (level == null) return;
        requireServerLevel(level);
        if (isReady(level) || generationBuildFailed()) return;
        if (!BUILD_IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            activeBuild = new BuildSession(level, buildEpoch);
            generationBuildFailed = false;
        } catch (RuntimeException | LinkageError failure) {
            BUILD_IN_FLIGHT.set(false);
            failedRevision = CraftPlanningRevision.current();
            generationBuildFailed = true;
            RSIntegrationMod.LOGGER.warn("[RecipeCatalog] could not start generation", failure);
        }
    }

    /**
     * 仅供明确要求同步构建的兼容调用；普通启动和合成请求必须使用分片预热。
     * 非服务端线程在访问 Level 前被拒绝，已有构建时立即返回，绝不等待后台任务。
     */
    public static void warmUpBlocking(Level level) {
        if (level == null) return;
        requireServerLevel(level);
        if (isReady(level) || !BUILD_IN_FLIGHT.compareAndSet(false, true)) return;
        long start = System.nanoTime();
        try {
            buildSynchronously(level);
        } catch (RuntimeException | LinkageError e) {
            failedRevision = CraftPlanningRevision.current();
            generationBuildFailed = true;
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] generation build failed; planning remains unavailable", e);
        } finally {
            BUILD_IN_FLIGHT.set(false);
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] explicit synchronous build used {}ms on server thread",
                    (System.nanoTime() - start) / 1_000_000L);
        }
    }

    /** 每 tick 只推进有限采集工作，后台结果就绪后才取出，绝不阻塞等待。 */
    public static synchronized void tickWarmUp(Level level) {
        requireServerLevel(level);
        BuildSession session = activeBuild;
        if (session != null && !session.isCurrent()) {
            discardBuild();
            session = null;
        }
        if (session == null) {
            warmUp(level);
            session = activeBuild;
        }
        if (session == null) return;
        try {
            if (session.finalized == null) {
                if (!session.advance(System.nanoTime() + TICK_BUDGET_NANOS)) return;
                // 数据所有权交给后台后，服务端线程不再修改这些私有容器。
                BuildInput input = session.input();
                session.finalized = CompletableFuture.supplyAsync(
                        () -> PlanningThreadContext.runInBackground(() -> finalizeBuild(input)),
                        WARMUP_EXECUTOR);
            }
            if (!session.finalized.isDone()) return;
            BuildResult result = session.finalized.getNow(null);
            publishBuildResult(session, result);
            discardBuild();
        } catch (RuntimeException | LinkageError failure) {
            if (session.isCurrent()) {
                failedRevision = session.revision;
                generationBuildFailed = true;
            }
            discardBuild();
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] generation build failed; planning remains unavailable", failure);
        }
    }

    private static void discardBuild() {
        if (activeBuild != null && activeBuild.finalized != null) {
            activeBuild.finalized.cancel(false);
        }
        activeBuild = null;
        BUILD_IN_FLIGHT.set(false);
    }

    /**
     * Returns only a complete generation published during startup or reload.
     */
    public static Map<Item, List<Entry>> get(Level level) {
        return readyGeneration(level).result.index;
    }

    private static PublishedGeneration readyGeneration(Level level) {
        PublishedGeneration ready = generation;
        if (ready != null && ready.manager == level.getRecipeManager()
                && ready.revision == CraftPlanningRevision.current()
                && ImmutableRecipeGraphProjector.isReady(level)) return ready;
        throw new ImmutableRecipeGraphProjector.RecipeGraphUnavailableException(
                "Recipe catalog is still loading; retry later");
    }

    private static Map<Item, List<Entry>> buildSynchronously(Level level) {
        requireServerLevel(level);
        BuildSession session = new BuildSession(level, buildEpoch);
        if (!session.advance(Long.MAX_VALUE)) return Map.of();
        BuildResult result = finalizeBuild(session.input());
        return publishBuildResult(session, result) ? result.index : Map.of();
    }

    private record PublishedGeneration(RecipeManager manager, long revision, BuildResult result) {}

    /** 容器由采集会话独占，交给后台后不再写入；其中的 Recipe/Item 仅作为不透明引用。 */
    record BuildInput(Map<Item, List<Entry>> index,
                      Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected,
                      Map<Item, List<ReusableCatalystRoute>> catalystRoutes,
                      Set<ResourceLocation> catalystOutputs, Set<ResourceLocation> catalystRecipes,
                      Map<IronSpellBooksRecipeCatalog.SpellScrollKey, List<Entry>> scrolls,
                      Set<ResourceLocation> outputIds) {}

    record BuildResult(Map<Item, List<Entry>> index,
                       Map<Item, List<ReusableCatalystRoute>> catalystRoutes,
                       Set<ResourceLocation> catalystOutputs, Set<ResourceLocation> catalystRecipes,
                       Map<IronSpellBooksRecipeCatalog.SpellScrollKey, List<Entry>> scrolls,
                       Set<ResourceLocation> incompatibleOutputs, ImmutableRecipeGraph graph,
                       ImmutableRecipeGraphProjector.PreparedProjection prepared,
                       long finalizeNanos) {}

    static BuildResult finalizeBuild(BuildInput input) {
        long started = System.nanoTime();
        Map<Item, List<Entry>> frozen = freezeIndex(input.index);
        Map<IronSpellBooksRecipeCatalog.SpellScrollKey, List<Entry>> scrolls = new HashMap<>();
        input.scrolls.forEach((key, entries) -> scrolls.put(key, List.copyOf(entries)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(input.projected);
        Set<ResourceLocation> projectedIds = graph.recipesByOutput().keySet().stream()
                .map(ImmutableRecipeGraph.MaterialRef::itemId).collect(Collectors.toSet());
        Set<ResourceLocation> incompatible = new HashSet<>(input.outputIds);
        incompatible.removeAll(projectedIds);
        var prepared = ImmutableRecipeGraphProjector.prepareCompiled(graph);
        return new BuildResult(frozen, freezeCatalystRoutes(input.catalystRoutes),
                Set.copyOf(input.catalystOutputs), Set.copyOf(input.catalystRecipes),
                Map.copyOf(scrolls), Set.copyOf(incompatible), graph, prepared,
                System.nanoTime() - started);
    }

    static synchronized boolean publishBuildResult(BuildSession session, BuildResult result) {
        requireServerLevel(session.level);
        if (!session.isCurrent()) return false;
        ImmutableRecipeGraphProjector.publishPrepared(session.manager, session.revision,
                result.prepared, session.timing.graphNanos + result.finalizeNanos);
        generation = new PublishedGeneration(session.manager, session.revision, result);
        generationBuildFailed = false;
        PerformanceMonitor.recordRecipeCatalogBuild(
                session.captureNanos + result.finalizeNanos,
                session.timing.graphNanos + result.finalizeNanos, session.recipeCount);
        Diagnostics.stopTimer("RecipeIndex.build", session.diagTimer);
        Diagnostics.record(Diagnostics.Category.INDEX_BUILD,
                result.index.size() + " items, " + session.seen.size() + " entries");
        RSIntegrationMod.LOGGER.info(
                "[RecipeCatalog] generation ready: {} items, {} entries, {} pure recipes, {}ms elapsed, "
                        + "{}ms capture, {}ms finalize; skipped {} unknown/{} empty/{} identity; slowest {} {}ms",
                result.index.size(), session.seen.size(), result.graph.recipesById().size(),
                (System.nanoTime() - session.started) / 1_000_000L,
                session.captureNanos / 1_000_000L, result.finalizeNanos / 1_000_000L,
                session.skippedUnknown, session.skippedEmptyResult, session.skippedIdentity,
                session.timing.slowestRecipe, session.timing.slowestRecipeNanos / 1_000_000L);
        if (!session.unknownRecipeTypes.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RecipeCatalog] unsupported native recipe types: {}",
                    summarizeUnknownRecipeTypes(session.unknownRecipeTypes));
        }
        return true;
    }

    private static void logSlowUnit(String unit, long elapsedNanos) {
        if (elapsedNanos > TICK_BUDGET_NANOS) {
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] capture unit {} took {}ms, exceeding 8ms tick budget; "
                            + "individual third-party calls cannot be preempted",
                    unit, elapsedNanos / 1_000_000L);
        }
    }

    enum BuildPhase { RECIPES, SOURCES, SPELL_SCROLLS, OUTPUT_IDS, COMPLETE }

    static final class BuildSession {
        final Level level;
        final RecipeManager manager;
        final long revision;
        final long epoch;
        final long started = System.nanoTime();
        final long diagTimer = Diagnostics.startTimer();
        final int recipeCount;
        final Iterator<Recipe<?>> recipes;
        final Map<Item, List<Entry>> index = new HashMap<>();
        final Set<ResourceLocation> seen = new HashSet<>();
        final Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected = new HashMap<>();
        final Map<Item, List<ReusableCatalystRoute>> catalystRoutes = new HashMap<>();
        final Set<ResourceLocation> catalystOutputs = new LinkedHashSet<>();
        final Set<ResourceLocation> catalystRecipes = new LinkedHashSet<>();
        final Map<IronSpellBooksRecipeCatalog.SpellScrollKey, List<Entry>> scrolls = new HashMap<>();
        final Set<ResourceLocation> outputIds = new HashSet<>();
        final Map<String, UnknownRecipeTypeStats> unknownRecipeTypes = new HashMap<>();
        final BuildTiming timing = new BuildTiming();
        BuildPhase phase = BuildPhase.RECIPES;
        int sourceStep;
        int skippedUnknown;
        int skippedEmptyResult;
        int skippedIdentity;
        long captureNanos;
        Iterator<Entry> scrollEntries = Collections.emptyIterator();
        Iterator<Item> outputItems = Collections.emptyIterator();
        CompletableFuture<BuildResult> finalized;

        BuildSession(Level level, long epoch) {
            requireServerLevel(level);
            this.level = level;
            this.manager = level.getRecipeManager();
            this.revision = CraftPlanningRevision.current();
            this.epoch = epoch;
            CraftPacketUtils.clearIngredientCache();
            Collection<Recipe<?>> captured = manager.getRecipes();
            recipeCount = captured.size();
            recipes = captured.iterator();
            captureNanos = System.nanoTime() - started;
            logSlowUnit("recipe collection", captureNanos);
        }

        boolean isCurrent() {
            return epoch == buildEpoch && revision == CraftPlanningRevision.current()
                    && manager == level.getRecipeManager();
        }

        boolean advance(long deadline) {
            return advance(deadline, System::nanoTime);
        }

        boolean advance(long deadline, LongSupplier nanoTime) {
            requireServerLevel(level);
            while (phase != BuildPhase.COMPLETE && nanoTime.getAsLong() < deadline) {
                long unitStarted = System.nanoTime();
                String unit = phase.name();
                switch (phase) {
                    case RECIPES -> {
                        if (!recipes.hasNext()) {
                            phase = BuildPhase.SOURCES;
                            continue;
                        }
                        Recipe<?> recipe = recipes.next();
                        unit = recipe.getId().toString();
                        IndexOutcome outcome = indexRecipe(level, index, seen, projected,
                                catalystOutputs, catalystRecipes, catalystRoutes, timing, recipe);
                        timing.recordRecipe(recipe, System.nanoTime() - unitStarted);
                        if (outcome == IndexOutcome.UNKNOWN) {
                            skippedUnknown++;
                            recordUnknownRecipeType(unknownRecipeTypes, recipe);
                        } else if (outcome == IndexOutcome.EMPTY_RESULT) skippedEmptyResult++;
                        else if (outcome == IndexOutcome.IDENTITY) skippedIdentity++;
                    }
                    case SOURCES -> {
                        unit = "source " + sourceStep;
                        switch (sourceStep++) {
                            case 0 -> indexFARituals(level, index, seen);
                            case 1 -> indexMarketEntries(index, seen);
                            case 2 -> indexGemCutting(level, index, seen);
                            case 3 -> indexIronSpellBooks(level, index, seen);
                            case 4 -> indexDistantWorldsFiron(index, seen);
                            case 5 -> indexPmmoSalvage(index, seen);
                            case 6 -> VanillaBrewingCatalog.index(level, index, seen, projected);
                            case 7 -> FluidContainerCatalog.index(level, index, seen, projected);
                            default -> {
                                if (ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)) {
                                    Item scroll = ForgeRegistries.ITEMS.getValue(
                                            new ResourceLocation(ModIds.IRONS_SPELLBOOKS, "scroll"));
                                    scrollEntries = index.getOrDefault(scroll, List.of()).iterator();
                                }
                                phase = BuildPhase.SPELL_SCROLLS;
                            }
                        }
                    }
                    case SPELL_SCROLLS -> {
                        if (!scrollEntries.hasNext()) {
                            outputItems = index.keySet().iterator();
                            phase = BuildPhase.OUTPUT_IDS;
                            continue;
                        }
                        Entry entry = scrollEntries.next();
                        ItemStack output = ModRecipeHandlers.tryGetResultItem(
                                entry.recipe(), level.registryAccess());
                        var key = IronSpellBooksRecipeCatalog.spellScrollKey(output);
                        if (key != null) scrolls.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
                    }
                    case OUTPUT_IDS -> {
                        if (!outputItems.hasNext()) {
                            phase = BuildPhase.COMPLETE;
                            continue;
                        }
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(outputItems.next());
                        if (id != null) outputIds.add(id);
                    }
                    case COMPLETE -> { }
                }
                long elapsed = System.nanoTime() - unitStarted;
                captureNanos += elapsed;
                logSlowUnit(unit, elapsed);
                // 第三方目录刷新可能改变 revision；下一 tick 必须重新开始采集。
                if (!isCurrent()) return false;
            }
            return phase == BuildPhase.COMPLETE;
        }

        BuildInput input() {
            if (phase != BuildPhase.COMPLETE) throw new IllegalStateException("Recipe capture incomplete");
            return new BuildInput(index, projected, catalystRoutes, catalystOutputs,
                    catalystRecipes, scrolls, outputIds);
        }
    }

    private static IndexOutcome indexRecipe(Level level, Map<Item, List<Entry>> target,
                                            Set<ResourceLocation> seen,
                                            Map<ImmutableRecipeGraph.MaterialRef,
                                                    List<ImmutableRecipeGraph.RecipeNode>> projected,
                                            Set<ResourceLocation> catalystOutputIds,
                                            Set<ResourceLocation> catalystRecipeIds,
                                            Map<Item, List<ReusableCatalystRoute>> catalystRoutes,
                                            BuildTiming timing, Recipe<?> recipe) {
        if (!seen.add(recipe.getId())) return IndexOutcome.DUPLICATE;

        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        ModType type;
        ItemStack result;
        if (handler != null) {
            type = ModType.classifyRecipe(recipe);
            if (type == null) type = handler.modType();
            if (!handler.indexPrimaryOutput(recipe)) return IndexOutcome.EMPTY_RESULT;
            result = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
        } else if (recipe instanceof CraftingRecipe
                && ModType.classifyRecipe(recipe) == null) {
            type = ModType.GENERIC;
            result = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
        } else {
            return IndexOutcome.UNKNOWN;
        }

        if (result.isEmpty()) return IndexOutcome.EMPTY_RESULT;
        if (isIdentityRecipe(recipe, result, handler)) return IndexOutcome.IDENTITY;

        ResourceLocation typeId = recipe.getType() != null
                ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) : null;
        if (typeId == null && recipe.getType() != null) {
            typeId = ResourceLocation.tryParse(recipe.getType().toString());
        }
        if (typeId == null) typeId = new ResourceLocation("minecraft:crafting");

        Entry entry = new Entry(recipe, type, typeId);
        target.computeIfAbsent(result.getItem(), key -> new ArrayList<>()).add(entry);
        if (ModList.get().isLoaded(ModIds.MINERS_DELIGHT)
                && MinersDelightCopperPotSupport.isCompatibleRecipe(recipe)) {
            ModType copperPotType = ModType.findById(ModIds.ID_MD_COPPER_POT);
            if (copperPotType != null) {
                ItemStack copperResult = MinersDelightCopperPotSupport.convertResult(result);
                if (!copperResult.isEmpty()) {
                    Entry copperEntry = new Entry(recipe, copperPotType, typeId);
                    target.computeIfAbsent(copperResult.getItem(), key -> new ArrayList<>())
                            .add(copperEntry);
                }
            }
        }
        List<IngredientSpec> craftingSpecs = null;
        if (recipe instanceof CraftingRecipe crafting) {
            craftingSpecs = CraftPacketUtils.extractCraftingIngredientSpecs(crafting);
            if (craftingSpecs.stream().anyMatch(spec -> spec.role() == DemandRole.CATALYST)) {
                ResourceLocation outputId = ForgeRegistries.ITEMS.getKey(result.getItem());
                if (outputId != null) catalystOutputIds.add(outputId);
                catalystRecipeIds.add(recipe.getId());
                catalystRoutes.computeIfAbsent(result.getItem(), ignored -> new ArrayList<>())
                        .add(new ReusableCatalystRoute(recipe.getId(), result, craftingSpecs));
            }
        }
        if (type == ModType.GENERIC && recipe instanceof CraftingRecipe crafting) {
            long graphStarted = System.nanoTime();
            ImmutableRecipeGraph.RecipeNode node =
                    ImmutableRecipeGraphProjector.projectCraftingRecipe(
                            crafting, result, craftingSpecs);
            timing.graphNanos += System.nanoTime() - graphStarted;
            if (node != null) {
                projected.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
            }
        } else if (isTypedPureProjectionCandidate(handler, type, recipe)) {
            long graphStarted = System.nanoTime();
            List<IngredientSpec> typedSpecs = handler.getRecursiveIngredients(
                    recipe, handler.getIngredients(recipe));
            boolean runtimeNbt = handler.hasRuntimeDependentPrimaryNbt(recipe);
            ImmutableRecipeGraph.RecipeNode node = typedSpecs == null ? null
                    : ImmutableRecipeGraphProjector.projectRecipe(
                    recipe.getId(), result, typedSpecs, type.id(), typeId,
                    !runtimeNbt);
            if (runtimeNbt && "malum".equals(type.id())) {
                RSIntegrationMod.LOGGER.debug(
                        "[RecipeCatalog] runtime NBT recipe={} output={} count={} projected={} outputNbt=UNKNOWN",
                        recipe.getId(), ForgeRegistries.ITEMS.getKey(result.getItem()),
                        result.getCount(), node != null);
            }
            if (recipe instanceof SmithingTransformRecipe
                    && typedSpecs != null && typedSpecs.size() == 3) {
                RSIntegrationMod.LOGGER.debug(
                        "[RecipeCatalog] smithing transform recipe={} base={} output={} projected={}",
                        recipe.getId(), Arrays.toString(typedSpecs.get(1).ingredient().getItems()),
                        ForgeRegistries.ITEMS.getKey(result.getItem()), node != null);
            }
            timing.graphNanos += System.nanoTime() - graphStarted;
            if (node != null) {
                projected.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
            }
        }
        if (handler != null) {
            for (ItemStack secondary : handler.getSecondaryOutputs(recipe, level.registryAccess())) {
                if (!secondary.isEmpty()) {
                    target.computeIfAbsent(secondary.getItem(), key -> new ArrayList<>()).add(entry);
                }
            }
        }
        return IndexOutcome.INDEXED;
    }

    static boolean isTypedPureProjectionCandidate(ModRecipeHandler handler, ModType type,
                                                   Recipe<?> recipe) {
        return handler != null && type != null && recipe != null
                && (type.graphExecutionAudit() != ModType.GraphExecutionAudit.FLAT_REQUIRED
                    || handler.supportsIntermediateProjection(recipe))
                && handler.supportsBackgroundPlanning(recipe);
    }

    /** Returns whether the immutable recipe graph can represent this recipe's inputs. */
    public static boolean isBackgroundProjectable(Recipe<?> recipe) {
        if (recipe == null) return false;
        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        ModType type = ModType.classifyRecipe(recipe);
        if (type == null && handler != null) type = handler.modType();
        return handler != null && type != null
                && (type.graphExecutionAudit() != ModType.GraphExecutionAudit.FLAT_REQUIRED
                    || handler.supportsIntermediateProjection(recipe))
                && handler.supportsBackgroundPlanning(recipe)
                && handler.hasDeterministicPrimaryOutput(recipe);
    }

    private static Map<Item, List<Entry>> freezeIndex(Map<Item, List<Entry>> mutable) {
        Map<Item, List<Entry>> frozen = new HashMap<>(mutable.size());
        mutable.forEach((item, entries) -> frozen.put(item, List.copyOf(entries)));
        // 私有快照的列表已冻结，只需包装 Map，避免再次复制大型索引。
        return Collections.unmodifiableMap(frozen);
    }

    /** Exact spell-level producers, avoiding a scan of every scroll recipe. */
    public static List<Entry> spellScrollCandidates(Level level, ItemStack requested) {
        BuildResult ready = readyGeneration(level).result;
        var key = IronSpellBooksRecipeCatalog.spellScrollKey(requested);
        return key == null ? List.of() : ready.scrolls.getOrDefault(key, List.of());
    }

    private static Map<Item, List<ReusableCatalystRoute>> freezeCatalystRoutes(
            Map<Item, List<ReusableCatalystRoute>> mutable) {
        Map<Item, List<ReusableCatalystRoute>> frozen = new HashMap<>(mutable.size());
        mutable.forEach((item, routes) -> frozen.put(item, List.copyOf(routes)));
        return Map.copyOf(frozen);
    }

    /** Outputs with at least one indexed producer that uses a reusable catalyst. */
    public static Set<ResourceLocation> reusableCatalystOutputIds(Level level) {
        return readyGeneration(level).result.catalystOutputs;
    }

    /** Indexed crafting recipes containing at least one reusable catalyst input. */
    public static Set<ResourceLocation> reusableCatalystRecipeIds(Level level) {
        return readyGeneration(level).result.catalystRecipes;
    }

    /** Outputs that have indexed producers but no producer representable in the pure graph. */
    public static Set<ResourceLocation> pureIncompatibleOutputIds(Level level) {
        return readyGeneration(level).result.incompatibleOutputs;
    }

    public static Map<Item, List<ReusableCatalystRoute>> reusableCatalystRoutes(Level level) {
        return readyGeneration(level).result.catalystRoutes;
    }

    private static final class BuildTiming {
        private long graphNanos;
        private long slowestRecipeNanos;
        private ResourceLocation slowestRecipe = new ResourceLocation("minecraft", "empty");

        private void recordRecipe(Recipe<?> recipe, long elapsedNanos) {
            if (elapsedNanos <= slowestRecipeNanos) return;
            slowestRecipeNanos = elapsedNanos;
            slowestRecipe = recipe.getId();
        }
    }

    private enum IndexOutcome {
        INDEXED, DUPLICATE, UNKNOWN, EMPTY_RESULT, IDENTITY
    }

    private static void recordUnknownRecipeType(
            Map<String, UnknownRecipeTypeStats> target, Recipe<?> recipe) {
        String typeId = "<unregistered>";
        try {
            ResourceLocation id = recipe.getType() == null
                    ? null : ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
            if (id != null) typeId = id.toString();
            else if (recipe.getType() != null) typeId = recipe.getType().toString();
        } catch (RuntimeException ignored) {
            // A broken third-party recipe type must not abort catalog creation.
        }
        target.computeIfAbsent(typeId, ignored -> new UnknownRecipeTypeStats())
                .record(recipe.getId());
    }

    static String summarizeUnknownRecipeTypes(Map<String, UnknownRecipeTypeStats> types) {
        final int limit = 32;
        List<Map.Entry<String, UnknownRecipeTypeStats>> sorted = types.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, UnknownRecipeTypeStats>>comparingInt(
                                entry -> entry.getValue().count)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        String summary = sorted.stream().limit(limit)
                .map(entry -> entry.getKey() + "=" + entry.getValue().count
                        + " samples=" + entry.getValue().samples)
                .collect(Collectors.joining("; "));
        int omitted = Math.max(0, sorted.size() - limit);
        return omitted == 0 ? summary : summary + "; ... " + omitted + " more types";
    }

    static final class UnknownRecipeTypeStats {
        private int count;
        private final List<ResourceLocation> samples = new ArrayList<>(3);

        void record(ResourceLocation recipeId) {
            count++;
            if (samples.size() < 3) samples.add(recipeId);
        }
    }

    private static int indexPmmoSalvage(Map<Item, List<Entry>> idx,
                                        Set<ResourceLocation> seen) {
        if (!ModList.get().isLoaded(ModIds.PMMO)
                || RSIntegrationConfig.ENABLE_PMMO == null
                || !RSIntegrationConfig.ENABLE_PMMO.get()) return 0;
        int count = 0;
        for (var recipe : PmmoSalvageCatalog.refresh()) {
            if (!seen.add(recipe.getId())) continue;
            ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
            if (output.isEmpty()) continue;
            idx.computeIfAbsent(output.getItem(), key -> new ArrayList<>()).add(new Entry(
                    recipe, ModType.byId(PmmoRSModule.TYPE_ID),
                    new ResourceLocation(RSIntegrationMod.MOD_ID, "pmmo_salvage"), true));
            count++;
        }
        return count;
    }

    private static int indexGemCutting(Level level, Map<Item, List<Entry>> idx, Set<ResourceLocation> seen) {
        if (!ModList.get().isLoaded("apotheosis")) return 0;
        int count = 0;
        for (var recipe : ApotheosisGemCuttingCatalog.allRecipes()) {
            if (!seen.add(recipe.getId())) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            idx.computeIfAbsent(output.getItem(), key -> new ArrayList<>()).add(new Entry(
                    recipe, ModType.byId("apotheosis_gem_cutting"),
                    new ResourceLocation("apotheosis", "gem_cutting"), true));
            count++;
        }
        return count;
    }

    private static int indexIronSpellBooks(Level level, Map<Item, List<Entry>> idx,
                                           Set<ResourceLocation> seen) {
        if (!ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)
                || !RSIntegrationConfig.ENABLE_IRONS_SPELLBOOKS.get()) return 0;
        Collection<IronSpellBooksRecipe> recipes;
        try {
            recipes = IronSpellBooksRecipeCatalog.allRecipes(level);
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeCatalog] Iron spell dynamic source unavailable; retaining other recipe sources", failure);
            return 0;
        }
        int count = 0;
        for (var recipe : recipes) {
            if (!seen.add(recipe.getId())) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            String machinePath = switch (recipe.machine()) {
                case SCROLL_FORGE -> "scroll_forge";
                case ARCANE_ANVIL -> "arcane_anvil";
                case ALCHEMIST_CAULDRON -> "alchemist_cauldron";
            };
            String typeId = "irons_spellbooks_" + machinePath;
            idx.computeIfAbsent(output.getItem(), key -> new ArrayList<>()).add(new Entry(
                    recipe, ModType.byId(typeId), new ResourceLocation("irons_spellbooks",
                    machinePath), true));
            count++;
        }
        return count;
    }

    // ── FA ritual indexing ──────────────────────────────────────

    private static volatile boolean faClassesProbed;
    private static volatile boolean faAvailable;
    private static volatile ResourceKey<?> faRitualKey;
    private static volatile Class<?> faCreateItemResultClass;
    private static volatile Class<?> faUpgradeTierResultClass;
    private static volatile Method faSetTierOnStack;
    private static volatile Item faForgeBlockItem;

    private static void probeFaClasses() {
        if (faClassesProbed) return;
        faClassesProbed = true;
        try {
            Class<?> faRegistries = Class.forName(
                    "com.stal111.forbidden_arcanus.core.registry.FARegistries");
            Field f = faRegistries.getField("RITUAL");
            f.setAccessible(true);
            faRitualKey = (ResourceKey<?>) f.get(null);
            faCreateItemResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult");
            faUpgradeTierResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.UpgradeTierResult");
            faAvailable = true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RecipeIndex] FA classes not available", e);
            faAvailable = false;
        }
    }

    /** Create a HephaestusForgeBlock ItemStack with {@code upgradedTier}
     *  applied via {@code setTierOnStack}, matching FA's own JEI display. */
    private static ItemStack rsi$makeFaUpgradeOutput(int upgradedTier) {
        try {
            if (faForgeBlockItem == null) {
                Block block = ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
                if (block == null) return ItemStack.EMPTY;
                faForgeBlockItem = block.asItem();
            }
            if (faSetTierOnStack == null) {
                Class<?> hfbClass = Class.forName(
                        "com.stal111.forbidden_arcanus.common.block.HephaestusForgeBlock");
                faSetTierOnStack = Reflect.findMethod(hfbClass, "setTierOnStack",
                        new Class<?>[]{ItemStack.class, int.class});
            }
            if (faSetTierOnStack == null) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(faForgeBlockItem);
            return (ItemStack) faSetTierOnStack.invoke(null, stack, upgradedTier);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA upgrade output failed", e);
            return ItemStack.EMPTY;
        }
    }

    /** Fallback output for FA rituals whose {@code result()} is null or
     *  has an unrecognized type (e.g. {@code apply_eternal_modifier}). */
    private static ItemStack rsi$faFallbackOutput(Object ritual, ResourceLocation id) {
        try {
            var m = Reflect.findMethod(ritual.getClass(), "mainIngredient", new Class<?>[0]);
            if (m == null) return ItemStack.EMPTY;
            Object main = m.invoke(ritual);
            if (main instanceof Ingredient ing && !ing.isEmpty()) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] FA fallback output for {}: {}",
                            id, ItemStackUtils.registryId(items[0]));
                    return items[0].copy();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA fallback output failed for {}", id, e);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Scan {@code FARegistries.RITUAL} and add item-producing rituals
     * to the index.  Returns the number of rituals indexed.
     */
    @SuppressWarnings("unchecked")
    private static int indexFARituals(Level level, Map<Item, List<Entry>> idx,
                                      Set<ResourceLocation> seen) {
        probeFaClasses();
        if (!faAvailable || faRitualKey == null) return 0;
        int count = 0;
        try {
            Registry<Object> faRegistry =
                    (Registry<Object>)
                    level.registryAccess().registryOrThrow(
                            (ResourceKey<? extends Registry<Object>>)
                            (Object) faRitualKey);

            for (var entry : faRegistry.entrySet()) {
                ResourceLocation id = entry.getKey().location();
                Object ritual = entry.getValue();
                if (ritual == null || !seen.add(id)) continue;
                try {
                    Method getResult = Reflect.findMethod(ritual.getClass(),
                            "result", new Class<?>[0]);
                    Object result = getResult != null ? getResult.invoke(ritual) : null;

                    ItemStack output = ItemStack.EMPTY;
                    if (result != null && faCreateItemResultClass.isInstance(result)) {
                        Method getStack = Reflect.findMethod(result.getClass(),
                                "getResult", new Class<?>[0]);
                        if (getStack != null) {
                            Object s = getStack.invoke(result);
                            if (s instanceof ItemStack st && !st.isEmpty())
                                output = st;
                        }
                    } else if (result != null && faUpgradeTierResultClass != null
                            && faUpgradeTierResultClass.isInstance(result)) {
                        int from = 0, to = 0;
                        try {
                            Method getFrom = Reflect.findMethod(result.getClass(), "getRequiredTier", new Class<?>[0]);
                            Method getTo = Reflect.findMethod(result.getClass(), "getUpgradedTier", new Class<?>[0]);
                            if (getFrom != null) from = (int) getFrom.invoke(result);
                            if (getTo != null) to = (int) getTo.invoke(result);
                        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RecipeIndex] tier read failed", e); }
                        output = rsi$makeFaUpgradeOutput(to);
                        if (output.isEmpty()) continue;
                        FaRitualWrapper wrapper = new FaRitualWrapper(id, ritual, output, from, to);
                        Entry entryObj = new Entry(wrapper, ModType.byId(ModIds.FORBIDDEN_ARCANUS),
                                new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
                        idx.computeIfAbsent(output.getItem(),
                                k -> new ArrayList<>()).add(entryObj);
                        count++;
                        continue;
                    }

                    // Fallback: rituals that modify items in-place (e.g.
                    // apply_eternal_modifier) may have null result.  Use
                    // mainIngredient's first matching item as the output.
                    if (output.isEmpty()) {
                        output = rsi$faFallbackOutput(ritual, id);
                    }
                    if (output.isEmpty()) continue;

                    FaRitualWrapper wrapper = new FaRitualWrapper(id, ritual, output);
                    Entry entryObj = new Entry(wrapper, ModType.byId(ModIds.FORBIDDEN_ARCANUS),
                            new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
                    idx.computeIfAbsent(output.getItem(),
                            k -> new ArrayList<>()).add(entryObj);
                    count++;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] Failed to index FA ritual {}", id, e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA ritual scan failed", e);
        }
        return count;
    }

    // ── Market entry indexing ──────────────────────────────────────

    private static volatile boolean marketClassesProbed;
    private static volatile boolean marketAvailable;
    private static volatile Object marketRegistryInst;

    private static void probeMarket() {
        if (marketClassesProbed && marketAvailable) return;
        marketClassesProbed = true;
        try {
            Class<?> registryClass = Class.forName(
                    "net.blay09.mods.farmingforblockheads.registry.MarketRegistry");
            Field instField = registryClass.getField("INSTANCE");
            marketRegistryInst = instField.get(null);
            marketAvailable = marketRegistryInst != null;
            if (!marketAvailable) {
                RSIntegrationMod.LOGGER.warn("[RecipeIndex] MarketRegistry INSTANCE is null — market entries will be skipped");
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] MarketRegistry not available — market entries will be skipped: {}", e.toString());
            marketAvailable = false;
        }
    }

    private static int indexMarketEntries(Map<Item, List<Entry>> idx,
                                           Set<ResourceLocation> seen) {
        probeMarket();
        if (!marketAvailable || marketRegistryInst == null) return 0;
        int count = 0;
        try {
            // Farming for Blockheads exposes these accessors as static methods
            // in current releases. Resolve them from the declared registry
            // class and invoke statically; older builds that used instance
            // methods are handled by the instance fallback below.
            Class<?> registryClass = Class.forName(
                    "net.blay09.mods.farmingforblockheads.registry.MarketRegistry");
            Method getEntries = Reflect.findMethod(registryClass,
                    "getEntries", new Class<?>[0]);
            if (getEntries == null) {
                RSIntegrationMod.LOGGER.warn("[RecipeIndex] MarketRegistry.getEntries() method not found");
                return 0;
            }
            @SuppressWarnings("unchecked")
            Object entriesValue = getEntries.invoke(
                    Modifier.isStatic(getEntries.getModifiers())
                            ? null : marketRegistryInst);
            Collection<Object> entries = (Collection<Object>) entriesValue;
            if (entries == null || entries.isEmpty()) {
                // MarketRegistry exists but has no entries yet — may be built
                // before MarketRegistryReloadEvent fires. Reset probe state so
                // the next index rebuild can pick up entries loaded later.
                RSIntegrationMod.LOGGER.warn("[RecipeIndex] MarketRegistry has {} entries — will retry on next build",
                        entries == null ? "null" : "0");
                marketClassesProbed = false;
                return 0;
            }

            Class<?> entryClass = null;
            Method getOutput = null;
            Method getCost = null;
            Method getEntryId = null;

            for (Object entry : entries) {
                if (entry == null) continue;
                try {
                    if (entryClass == null) {
                        entryClass = entry.getClass();
                        getOutput = Reflect.findMethod(entryClass, "getOutputItem", new Class<?>[0]);
                        getCost = Reflect.findMethod(entryClass, "getCostItem", new Class<?>[0]);
                        getEntryId = Reflect.findMethod(entryClass, "getEntryId", new Class<?>[0]);
                        if (getOutput == null || getCost == null || getEntryId == null) {
                            RSIntegrationMod.LOGGER.warn("[RecipeIndex] Market entry methods not found");
                            return 0;
                        }
                    }

                    ItemStack output = (ItemStack) getOutput.invoke(entry);
                    ItemStack cost = (ItemStack) getCost.invoke(entry);
                    UUID uuid = (UUID) getEntryId.invoke(entry);

                    if (output.isEmpty() || cost.isEmpty()) continue;

                    // Skip identity entries (cost == output) — would create circular deps
                    if (ItemStack.isSameItem(output, cost)) continue;

                    ResourceLocation rid = new ResourceLocation(ModIds.FARMINGFORBLOCKHEADS, "market/" + uuid);
                    if (!seen.add(rid)) continue;

                    MarketRecipeWrapper wrapper =
                            new MarketRecipeWrapper(uuid, output, cost);
                    ModType type = ModType.FARMINGFORBLOCKHEADS_MARKET;
                    Entry indexEntry = new Entry(wrapper, type,
                            new ResourceLocation(ModIds.FARMINGFORBLOCKHEADS, "market"));
                    idx.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(indexEntry);
                    count++;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] Failed to index market entry", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] Market entry scan failed", e);
        }
        return count;
    }

    // ── Distant Worlds Firon Lithum Altar indexing ─────────────────

    private static int indexDistantWorldsFiron(Map<Item, List<Entry>> idx,
                                                Set<ResourceLocation> seen) {
        if (!ModList.get().isLoaded(ModIds.DISTANT_WORLDS)) return 0;
        ModType type = ModType.byId(LithumAltarRecipeResolver.TYPE_ID);
        if (type == ModType.GENERIC) return 0;
        int count = 0;
        for (LithumAltarRecipeDefinition definition : LithumAltarRecipeResolver.definitions()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    LithumAltarRecipeResolver.MOD_ID, definition.currentRecipe());
            if (!seen.add(id) || !LithumAltarRecipeResolver.isComplete(definition)) continue;
            LithumAltarRecipeWrapper wrapper = new LithumAltarRecipeWrapper(id, definition);
            Entry entry = new Entry(wrapper, type,
                    ResourceLocation.fromNamespaceAndPath("rs_integration", "lithum_altar_firon"));
            idx.computeIfAbsent(definition.output().getItem(), ignored -> new ArrayList<>()).add(entry);
            count++;
        }
        return count;
    }

    /**
     * Returns true if the recipe's output item matches EVERY non-empty input
     * item type.  A true identity recipe (e.g. CraftTweaker {@code .copy()})
     * creates circular auto-crafting dependencies and must be skipped.
     *
     * <p>Recipes where the output matches only SOME inputs (e.g. smithing
     * transform: template + weapon + addition → modified weapon) are
     * <em>not</em> identity — they consume a second distinct item and are
     * therefore transformative, even if the output item type happens to
     * match one of the input slots.</p>
     */
    static boolean isIdentityRecipe(Recipe<?> recipe, ItemStack result,
                                            ModRecipeHandler handler) {
        // NBT-bearing results indicate a transformation (charge, repair, clean,
        // compress-with-NBT, etc.) — not a trivial identity recipe.
        if (result.hasTag()) return false;
        Item resultItem = result.getItem();
        List<Ingredient> ingredients;
        if (handler != null) {
            var specs = handler.getIngredients(recipe);
            if (specs == null) return false;
            ingredients = new ArrayList<>();
            for (var spec : specs) {
                if (!spec.isEmpty()) ingredients.add(spec.ingredient());
            }
        } else {
            ingredients = recipe.getIngredients();
        }
        boolean anyNonEmpty = false;
        for (var ing : ingredients) {
            if (ing.isEmpty()) continue;
            anyNonEmpty = true;
            boolean hasDistinctInput = false;
            boolean hasCandidate = false;
            for (ItemStack opt : ing.getItems()) {
                if (opt.isEmpty()) continue;
                hasCandidate = true;
                if (opt.getItem() != resultItem) hasDistinctInput = true;
            }
            if (!hasCandidate || hasDistinctInput) return false;
        }
        return anyNonEmpty;
    }

    /** Invalidate the cached index (e.g. on recipe reload). */
    public static synchronized void invalidate() {
        buildEpoch++;
        generation = null;
        discardBuild();
        lastDriftCheckTick = Long.MIN_VALUE;
        generationBuildFailed = false;
        if (ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)) {
            IronSpellBooksRecipeCatalog.invalidate();
        }
        FluidContainerCatalog.invalidate();
        ImmutableRecipeGraphProjector.clearCache();
        GenericCraftPacket.clearPlanCache();
    }

    // ── result-item extraction (formerly in ModRecipeIndex) ─────

    private static final Map<Class<?>, Method> resultMethodCache = new ConcurrentHashMap<>();
    /** Caches the output ItemStack field (positive) or NEGATIVE sentinel per recipe class. */
    private static final Map<Class<?>, Field> outputFieldCache = new ConcurrentHashMap<>();
    private static final Field NO_OUTPUT_FIELD;
    static {
        try { NO_OUTPUT_FIELD = RecipeIndex.class.getDeclaredField("generation"); }
        catch (NoSuchFieldException e) { throw new RuntimeException(e); }
    }

    /**
     * Re-entry guard. When a {@code ModRecipeHandler.getResultItem()} calls back
     * into this method for the same recipe class (a latent StackOverflow trap —
     * see the Malum recursion regression), skip handler dispatch and fall through
     * to the reflection probe instead. Mirrors {@code ModRecipeHandlers.DISPATCH_GUARD}.
     */
    private static final ThreadLocal<Class<?>> DISPATCH_GUARD = new ThreadLocal<>();

    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        PlanningThreadContext.requireMainThread("third-party recipe reflection");
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe) {
            return ModRecipeHandlers.tryGetResultItem(recipe, access);
        }
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null && DISPATCH_GUARD.get() != recipe.getClass()) {
            Class<?> prev = DISPATCH_GUARD.get();
            DISPATCH_GUARD.set(recipe.getClass());
            try {
                ItemStack result = handler.getResultItem(recipe, access);
                if (!result.isEmpty()) return result.copy();
            } finally {
                if (prev != null) DISPATCH_GUARD.set(prev);
                else DISPATCH_GUARD.remove();
            }
            // Handler exists and returned EMPTY — don't fall through
            // to the reflection probe (same reason as ModRecipeHandlers).
            return ItemStack.EMPTY;
        }
        // This interface call is remapped by ForgeGradle in production. Looking
        // it up by the development name "getResultItem" fails under SRG names.
        try {
            ItemStack result = recipe.getResultItem(access);
            if (!result.isEmpty()) return result.copy();
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Recipe result lookup failed", e);
        }
        Class<?> clazz = recipe.getClass();
        Method m = resultMethodCache.get(clazz);
        if (m != null) {
            try {
                Object result;
                if (m.getParameterCount() == 1) {
                    result = m.invoke(recipe, access);
                } else {
                    result = m.invoke(recipe);
                }
                if (result instanceof ItemStack s && !s.isEmpty()) return s.copy();
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        for (String methodName : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            boolean isResultItem = "getResultItem".equals(methodName);
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterCount() == 1) {
                    try {
                        Object result = method.invoke(recipe, access);
                        if (result instanceof ItemStack s && !s.isEmpty()) {
                            resultMethodCache.put(clazz, method);
                            return s.copy();
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            // Skip no-arg getResultItem() — the deprecated overload that mods
            // abuse to return machine block icons.
            if (isResultItem) continue;
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterCount() == 0) {
                    try {
                        Object result = method.invoke(recipe);
                        if (result instanceof ItemStack s && !s.isEmpty()) {
                            resultMethodCache.put(clazz, method);
                            return s.copy();
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
        }
        ItemStack fieldResult = tryGetOutputField(recipe);
        if (!fieldResult.isEmpty()) return fieldResult;
        return ItemStack.EMPTY;
    }

    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        Field cached = outputFieldCache.get(clazz);
        if (cached != null) {
            if (cached == NO_OUTPUT_FIELD) return ItemStack.EMPTY;
            try {
                Object val = cached.get(recipe);
                if (val instanceof ItemStack s && !s.isEmpty()) return s.copy();
            } catch (Exception e) { return ItemStack.EMPTY; }
            // Field was cached but now returns empty/null — still better
            // than re-scanning. Keep the cache entry.
            return ItemStack.EMPTY;
        }
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field field : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(field.getType())) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("output") || name.contains("result") || name.contains("assembled")) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(recipe);
                        if (val instanceof ItemStack s && !s.isEmpty()) {
                            outputFieldCache.put(clazz, field);
                            return s.copy();
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            scan = scan.getSuperclass();
        }
        outputFieldCache.put(clazz, NO_OUTPUT_FIELD);
        return ItemStack.EMPTY;
    }

    public static List<ItemStack> tryGetSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof FluidContainerRecipe conversion) return conversion.secondaryOutputs();
        List<ItemStack> results = new ArrayList<>();
        Set<Object> seenOutputContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        if (recipe == null) return results;
        // CraftingRecipe#getRemainingItems requires the actual crafting grid.
        // Probing a non-existent zero-argument overload both loses KubeJS actions
        // and produces one warning for every shaped/shapeless implementation.
        if (!(recipe instanceof CraftingRecipe)) {
            try {
                Method m = Reflect.findMethod(recipe.getClass(), "getRemainingItems", new Class<?>[0]);
                if (m != null) {
                    Object obj = m.invoke(recipe);
                    if (seenOutputContainers.add(obj) && obj instanceof List<?> list) {
                        for (Object e : list) {
                            if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                        }
                    } else if (obj instanceof ItemStack[] arr) {
                        for (ItemStack s : arr) {
                            if (!s.isEmpty()) results.add(s.copy());
                        }
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        try {
            Method m = Reflect.findMethod(recipe.getClass(), "getByproducts", new Class<?>[0]);
            if (m != null) {
                Object obj = m.invoke(recipe);
                if (seenOutputContainers.add(obj) && obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        try {
            Method m = Reflect.findMethod(recipe.getClass(), "getRollResults", new Class<?>[0]);
            if (m != null) {
                Object obj = m.invoke(recipe);
                if (seenOutputContainers.add(obj) && obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        try {
            Method m = Reflect.findMethod(recipe.getClass(), "getOutputs", new Class<?>[0]);
            if (m == null) {
                // Method not found in class hierarchy, skip this handler
            } else {
                Object obj = m.invoke(recipe);
                // Unlike getRemainingItems/getByproducts/getRollResults (byproduct-
                // specific), many mods' getOutputs() returns the FULL output list
                // including the primary result. Since callers add the primary
                // separately (tryGetResultItem), drop the first stack equal to the
                // primary so it isn't duplicated into the RS network.
                ItemStack primary = tryGetResultItem(recipe, access);
                primary = CraftingResolver.resolveDeclaredOutput(recipe, primary);
                boolean primaryDropped = false;
                List<ItemStack> fromGetOutputs = new ArrayList<>();
                if (seenOutputContainers.add(obj) && obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) fromGetOutputs.add(s.copy());
                    }
                } else if (obj instanceof ItemStack[] arr) {
                    for (ItemStack s : arr) {
                        if (!s.isEmpty()) fromGetOutputs.add(s.copy());
                    }
                }
                for (ItemStack s : fromGetOutputs) {
                    boolean matchesPrimary = !primary.isEmpty()
                            && (primary.hasTag()
                            ? ItemStack.isSameItemSameTags(s, primary)
                            : ItemStack.isSameItem(s, primary));
                    if (!primaryDropped && matchesPrimary) {
                        primaryDropped = true; // skip exactly one copy of the primary
                        continue;
                    }
                    results.add(s);
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        // Only fall back to raw field scanning when no getter yielded anything.
        // Otherwise a recipe whose getByproducts()/getRollResults() is backed by a
        // same-named field (byproducts/rollResults/...) would have its secondaries
        // collected twice — duplicating items into the RS network.
        if (results.isEmpty()) {
            trySecondaryOutputFields(recipe, results);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static void trySecondaryOutputFields(Recipe<?> recipe, List<ItemStack> results) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (Field field : scan.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("byproduct") && !name.contains("secondary")
                        && !name.contains("extra") && !name.contains("bonus")
                        && !name.contains("roll"))
                    continue;
                field.setAccessible(true);
                try {
                    Object val = field.get(recipe);
                    if (val instanceof List<?> list) {
                        for (Object e : list) {
                            if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                        }
                    } else if (val instanceof ItemStack s && !s.isEmpty()) {
                        results.add(s.copy());
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            scan = scan.getSuperclass();
        }
    }
}
