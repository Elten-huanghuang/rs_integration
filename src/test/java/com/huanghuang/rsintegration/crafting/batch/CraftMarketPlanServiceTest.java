package com.huanghuang.rsintegration.crafting.batch;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlanEstimator;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanRequestService;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CraftMarketPlanServiceTest extends BootstrapTest {
    private final MarketRecipeWrapper recipe = new MarketRecipeWrapper(UUID.randomUUID(),
            new ItemStack(Items.WHEAT_SEEDS, 4), new ItemStack(Items.EMERALD, 32));
    private final StorageReference reference =
            new StorageReference(new StorageBackendId("test"), "market-network");
    private final StorageNetworkDescriptor network =
            new StorageNetworkDescriptor(reference, "Market", true);

    @BeforeAll
    static void initializeServerContext() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading =
                     mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, false", "64, false", "100000000, false", "64, true"})
    void fastExchangeCachesOneExecutableNodeWithExactPaymentAndSelectedStorage(
            int executions, boolean leased) {
        PlanCache.Entry cached = buildFastPlan(executions, leased, true);
        assertNotNull(cached.resolvedGraph());
        assertTrue(cached.terminalEmbedded());
        CraftPlanValidator.validate(cached.resolvedGraph());
        assertEquals(1, cached.resolvedGraph().nodes().size());
        CraftNode node = cached.resolvedGraph().nodes().get(0);
        assertEquals(recipe.getId(), node.recipeId());
        assertEquals(ModType.FARMINGFORBLOCKHEADS_MARKET.id(), node.modTypeId());
        assertEquals(executions, node.executions());
        assertEquals(32L * executions, node.inputs().get(0).quantity());
        assertEquals(32L * executions, cached.resolvedGraph().allocations().stream()
                .mapToLong(allocation -> allocation.quantity()).sum());
        assertEquals(4L * executions, cached.resolvedGraph().rootDemands().get(0).quantity());
        assertEquals(1, cached.plan().steps().size());
        assertNotNull(cached.plan().graph());
        assertEquals(1, cached.plan().graph().nodes().size());
        assertEquals(reference, cached.plan().storageReference());
        assertEquals(List.of(network), cached.plan().storageNetworks());
        assertEquals(Set.of(ModType.FARMINGFORBLOCKHEADS_MARKET.id()),
                cached.plan().boundMachineTypes());
        assertEquals(leased ? PlanOutcome.RUNTIME_UNAVAILABLE : PlanOutcome.READY,
                cached.plan().outcome());
        assertEquals(!leased, cached.plan().success());
        assertTrue(CraftPlanDecisionRules.canExecuteCachedPlan(
                cached.plan(), cached.resolvedGraph()));
        assertEquals(32L * executions, cached.plan().materials().values().stream()
                .mapToLong(PlanResponse.Availability::needed).sum());
    }

    @Test
    void directPlanDoesNotAdvertiseAnUnboundMarketAsExecutable() {
        PlanCache.Entry cached = buildFastPlan(64, false, false);

        assertFalse(cached.plan().success());
        assertTrue(cached.plan().executionBlocked());
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(
                cached.plan(), cached.resolvedGraph()));
    }

    @Test
    void paymentCountsAboveIntegerMaximumRemainExact() {
        long required = 32L * 100_000_000;
        CraftMarketPlanService.TradeAvailability availability =
                CraftMarketPlanService.tradeAvailability(recipe,
                        Map.of(new StackKey(Items.EMERALD, null), required), 100_000_000);

        assertTrue(availability.feasible());
        assertEquals(required, availability.required());
        assertEquals(required, availability.available());
    }

    @Test
    void missingPaymentOrLockedVariantFallsBackWithoutPublishingAFastPlan() {
        ItemStack locked = new ItemStack(Items.EMERALD);
        locked.getOrCreateTag().putString("variant", "locked");
        List<ScaledIngredientSpec> lockedSpecs = List.of(ScaledIngredientSpec.of(
                new IngredientSpec(StrictNBTIngredient.of(locked), 32), 32));
        CraftPlanResultDispatcher.Sink sink = mock(CraftPlanResultDispatcher.Sink.class);
        ServerPlayer player = mock(ServerPlayer.class);
        try (MockedStatic<CraftPlanResponseFinalizer> finalizer =
                     mockStatic(CraftPlanResponseFinalizer.class)) {
            assertFalse(CraftMarketPlanService.tryBuildDirectPlan(
                    player, recipe, recipe.getId(), 1, recipe.getResultItem(RegistryAccess.EMPTY),
                    null, lockedSpecs, Level.OVERWORLD.location(), BlockPos.ZERO,
                    Map.of(new StackKey(Items.EMERALD, null), 31L),
                    null, null, reference, List.of(network), System.nanoTime(), sink));
            assertFalse(CraftMarketPlanService.tryBuildDirectPlan(
                    player, recipe, recipe.getId(), 1, recipe.getResultItem(RegistryAccess.EMPTY),
                    null, lockedSpecs, Level.OVERWORLD.location(), BlockPos.ZERO,
                    Map.of(new StackKey(Items.EMERALD, null), 32L),
                    null, null, reference, List.of(network), System.nanoTime(), sink));
            finalizer.verifyNoInteractions();
            verifyNoInteractions(sink, player);
        }
    }

    private PlanCache.Entry buildFastPlan(int executions, boolean leased, boolean bound) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "MarketTest"));
        when(player.serverLevel()).thenReturn(level);
        when(player.getServer()).thenReturn(mock(MinecraftServer.class));
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getRecipeManager()).thenReturn(recipes);
        doReturn(Optional.of(recipe)).when(recipes).byKey(recipe.getId());
        long required = 32L * executions;
        ItemStack taggedPayment = new ItemStack(Items.EMERALD);
        taggedPayment.getOrCreateTag().putString("variant", "stored");
        Map<StackKey, Long> available = Map.of(
                new StackKey(Items.EMERALD, null), required / 2,
                new StackKey(Items.EMERALD, taggedPayment.getTag().toString()),
                required - required / 2);
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(
                playerId, 1, recipe.getId(), available, Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
        ItemStack clicked = recipe.getResultItem(RegistryAccess.EMPTY);
        PlanCache.Key key = CraftPlanCacheKeys.key(
                playerId, recipe.getId(), Map.of(), executions, clicked,
                ModType.FARMINGFORBLOCKHEADS_MARKET);
        PlanCache cache = new PlanCache(1_000_000_000L);
        PlanRequestService requests = mock(PlanRequestService.class);
        when(requests.isCurrent(playerId, 1)).thenReturn(true);
        // 在当前线程完成后台响应，保留生产代码的状态复验、缓存和发布逻辑。
        doAnswer(call -> {
            PlanningSnapshot current = call.getArgument(0);
            PlanResponseDraft draft = call.getArgument(1);
            Predicate<PlanningSnapshot> revalidator = call.getArgument(3);
            Consumer<PlanResponse> commit = call.getArgument(4);
            assertTrue(revalidator.test(current));
            commit.accept(draft.toResponse());
            return null;
        }).when(requests).submitResponse(any(), any(), any(), any(), any(), any());
        CraftPlanResultDispatcher.Sink sink = mock(CraftPlanResultDispatcher.Sink.class);
        try (MockedStatic<CraftMachineBindingSupport> machines =
                     mockStatic(CraftMachineBindingSupport.class);
             MockedStatic<ParallelPlanEstimator> parallel = mockStatic(ParallelPlanEstimator.class);
             MockedStatic<CraftPlanningRuntime> runtime = mockStatic(CraftPlanningRuntime.class);
             MockedStatic<PlanningProgressServer> progress = mockStatic(PlanningProgressServer.class);
             MockedStatic<PlanningStateValidator> validator = mockStatic(PlanningStateValidator.class)) {
            machines.when(() -> CraftMachineBindingSupport.inspectPlanAvailability(
                    eq(player), eq(recipe.getId()), eq(ModType.FARMINGFORBLOCKHEADS_MARKET),
                    anyList(), eq(Level.OVERWORLD.location()), eq(BlockPos.ZERO)))
                    .thenReturn(new CraftMachineBindingSupport.PlanAvailability(
                            !bound, false, bound ? Set.of(ModType.FARMINGFORBLOCKHEADS_MARKET.id())
                                    : Set.of(), leased));
            runtime.when(CraftPlanningRuntime::planCache).thenReturn(cache);
            runtime.when(CraftPlanningRuntime::planRequests).thenReturn(requests);
            validator.when(() -> PlanningStateValidator.revalidatePreview(
                    eq(player), eq(snapshot), any(), eq(Level.OVERWORLD),
                    eq(BlockPos.ZERO), eq(requests), eq(reference))).thenReturn(true);

            assertTrue(CraftMarketPlanService.tryBuildDirectPlan(
                    player, recipe, recipe.getId(), executions, clicked, clicked,
                    CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                            recipe.costSpecs(), clicked, executions),
                    Level.OVERWORLD.location(), BlockPos.ZERO, available, snapshot,
                    key, reference, List.of(network), System.nanoTime(), sink));

            PlanCache.Entry cached = cache.peekForExecution(key);
            assertNotNull(cached);
            verify(sink).success(cached.plan(), snapshot);
            validator.verify(() -> PlanningStateValidator.revalidatePreview(
                    eq(player), eq(snapshot), any(), eq(Level.OVERWORLD),
                    eq(BlockPos.ZERO), eq(requests), eq(reference)));
            return cached;
        }
    }
}
