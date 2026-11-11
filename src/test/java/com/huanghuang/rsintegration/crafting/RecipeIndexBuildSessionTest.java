package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.carrot123.until_eternity.recipe.EndCraftingIngredient;
import com.carrot123.until_eternity.recipe.EndCraftingRecipe;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.untileternity.EndCraftingRecipeHandler;
import com.huanghuang.rsintegration.mods.untileternity.UntilEternityRSModule;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipeIndexBuildSessionTest extends BootstrapTest {
    @BeforeAll
    static void loadModTypes() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            // 初始化与现有规划测试一致的模组类型，不启动 Forge 服务端。
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @Test
    void blockingWarmUpRejectsOtherThreadsBeforeReadingWorld() {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        try (var lifecycle = mockStatic(ServerLifecycleHooks.class)) {
            lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
            when(server.isSameThread()).thenReturn(false);
            assertThrows(PlanningThreadContext.MainThreadPlanningRequiredException.class,
                    () -> RecipeIndex.warmUpBlocking(level));
            verifyNoInteractions(level);
            assertThrows(PlanningThreadContext.MainThreadPlanningRequiredException.class,
                    () -> PlanningThreadContext.runInBackground(() -> {
                        RecipeIndex.warmUpBlocking(level);
                        return null;
                    }));
            verifyNoInteractions(level);
        }
    }

    @Test
    void warmUpOnlySchedulesOneSessionAndBlockingCompatibilityNeverWaits() throws Exception {
        withServer((level, manager) -> {
            Recipe<?> recipe = mock(Recipe.class);
            doReturn(List.of(recipe)).when(manager).getRecipes();
            RecipeIndex.warmUp(level);
            RecipeIndex.warmUp(level);
            assertFalse(RecipeIndex.isReady(level));
            assertFalse(RecipeIndex.generationBuildFailed());
            assertThrows(ImmutableRecipeGraphProjector.RecipeGraphUnavailableException.class,
                    () -> ImmutableRecipeGraphProjector.capture(level));
            assertThrows(ImmutableRecipeGraphProjector.RecipeGraphUnavailableException.class,
                    () -> RecipeIndex.get(level));
            RecipeIndex.warmUpBlocking(level);
            verify(manager, times(1)).getRecipes();
            verifyNoInteractions(recipe);
        });
    }

    @Test
    void captureStopsAtDeadlineBetweenRecipesAndResumes() throws Exception {
        withServer((level, manager) -> {
            Recipe<?> first = recipe("first");
            Recipe<?> second = recipe("second");
            Recipe<?> third = recipe("third");
            doReturn(List.of(first, second, third)).when(manager).getRecipes();
            try (var handlers = mockStatic(ModRecipeHandlers.class)) {
                handlers.when(() -> ModRecipeHandlers.claimFor(first))
                        .thenReturn(new ModRecipeHandlers.Claim(null, List.of()));
                handlers.when(() -> ModRecipeHandlers.claimFor(second))
                        .thenReturn(new ModRecipeHandlers.Claim(null, List.of()));
                handlers.when(() -> ModRecipeHandlers.claimFor(third))
                        .thenReturn(new ModRecipeHandlers.Claim(null, List.of()));
                RecipeIndex.BuildSession session = newSession(level);
                AtomicLong clock = new AtomicLong();
                assertFalse(session.advance(2L, clock::getAndIncrement));
                assertEquals(2, session.seen.size());
                verify(third, never()).getId();
                assertFalse(session.advance(1L, new AtomicLong()::getAndIncrement));
                assertEquals(3, session.seen.size());
                assertEquals(RecipeIndex.BuildPhase.RECIPES, session.phase);
            }
        });
    }

    @Test
    void finalizedOldRevisionManagerAndEpochNeverReplacePublishedGeneration() throws Exception {
        withServer((level, manager) -> {
            RecipeIndex.BuildSession oldRevision = newSession(level);
            RecipeIndex.BuildResult result = emptyResult();
            CraftPlanningRevision.bump();
            RecipeIndex.BuildSession current = newSession(level);
            assertTrue(RecipeIndex.publishBuildResult(current, result));
            assertFalse(RecipeIndex.publishBuildResult(oldRevision, result));
            assertTrue(RecipeIndex.isReady(level));

            RecipeManager replacement = mock(RecipeManager.class);
            doReturn(List.of()).when(replacement).getRecipes();
            when(level.getRecipeManager()).thenReturn(replacement);
            assertFalse(RecipeIndex.publishBuildResult(current, result));
            assertFalse(RecipeIndex.isReady(level));

            RecipeIndex.BuildSession oldEpoch = newSession(level);
            RecipeIndex.invalidate();
            assertFalse(RecipeIndex.publishBuildResult(oldEpoch, result));
            assertFalse(RecipeIndex.isReady(level));
            RecipeIndex.BuildSession newest = newSession(level);
            assertTrue(RecipeIndex.publishBuildResult(newest, result));
            assertFalse(RecipeIndex.publishBuildResult(oldEpoch, result));
            assertTrue(RecipeIndex.isReady(level));
        });
    }

    @Test
    @Timeout(10)
    void incompleteBackgroundFinalizeIsPolledWithoutWaiting() throws Exception {
        withServer((level, manager) -> {
            RecipeIndex.warmUp(level);
            Field active = RecipeIndex.class.getDeclaredField("activeBuild");
            active.setAccessible(true);
            RecipeIndex.BuildSession session = (RecipeIndex.BuildSession) active.get(null);
            session.finalized = new CompletableFuture<>();
            RecipeIndex.tickWarmUp(level);
            assertSame(session, active.get(null));
            assertFalse(RecipeIndex.isReady(level));
            session.finalized.complete(emptyResult());
            RecipeIndex.tickWarmUp(level);
            assertTrue(RecipeIndex.isReady(level));
            assertNull(active.get(null));
        });
    }

    @Test
    void backgroundFinalizeOnlyCopiesOpaqueRecipeReferencesAndFreezesContainers() throws Exception {
        Recipe<?> recipe = mock(Recipe.class);
        ResourceLocation id = new ResourceLocation("test", "result");
        var output = new ImmutableRecipeGraph.MaterialRef(new ResourceLocation("minecraft", "diamond"), "");
        var node = new ImmutableRecipeGraph.RecipeNode(id, output, 1, List.of());
        RecipeIndex.Entry entry = new RecipeIndex.Entry(recipe, ModType.GENERIC, id);
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected =
                new HashMap<>(Map.of(output, List.of(node)));
        RecipeIndex.BuildInput input = new RecipeIndex.BuildInput(
                new HashMap<>(Map.of(Items.DIAMOND, List.of(entry))), projected,
                Map.of(), Set.of(), Set.of(), Map.of(), Map.of(output.itemId(), Set.of(id)));
        RecipeIndex.BuildResult result = CompletableFuture.supplyAsync(() ->
                PlanningThreadContext.runInBackground(() -> RecipeIndex.finalizeBuild(input)))
                .get(5, TimeUnit.SECONDS);
        verifyNoInteractions(recipe);
        projected.clear();
        assertEquals(1, result.graph().recipesById().size());
        assertTrue(result.incompatibleOutputs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.index().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.index().get(Items.DIAMOND).clear());
    }

    @Test
    void oneProjectedProducerDoesNotHideOtherRecipesOrUnprojectedSecondaryOutputs() {
        ResourceLocation pureId = new ResourceLocation("test:pure");
        ResourceLocation typedId = new ResourceLocation("test:typed");
        var output = new ImmutableRecipeGraph.MaterialRef(new ResourceLocation("minecraft:diamond"), "");
        ResourceLocation secondary = new ResourceLocation("minecraft:emerald");
        var node = new ImmutableRecipeGraph.RecipeNode(pureId, output, 1, List.of());
        var result = RecipeIndex.finalizeBuild(new RecipeIndex.BuildInput(
                Map.of(), Map.of(output, List.of(node)), Map.of(), Set.of(), Set.of(), Map.of(),
                Map.of(output.itemId(), Set.of(pureId, typedId), secondary, Set.of(pureId))));
        assertEquals(Set.of(output.itemId(), secondary), result.incompatibleOutputs());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(30)
    void endCraftingPartialNbtIsCapturedBeforeLongChainRunsInBackground(boolean matchingStock) throws Exception {
        withServer((level, manager) -> {
            UntilEternityRSModule.INSTANCE.registerModType();
            CompoundTag required = new CompoundTag();
            required.putInt("level", 2);
            EndCraftingRecipe recipe = spy(new EndCraftingRecipe(new ResourceLocation("test", "end_crafting"),
                    1, 1, List.of(new EndCraftingIngredient(
                            Ingredient.of(Items.DIAMOND, Items.EMERALD), required)),
                    new ItemStack(Items.NETHER_STAR, 4)));
            // 测试 JVM 没有注册第三方 RecipeType；材料、结果与配方实现均使用真实对象。
            doReturn(RecipeType.CRAFTING).when(recipe).getType();
            doReturn(RecipeSerializer.SHAPED_RECIPE).when(recipe).getSerializer();
            doReturn(List.of(recipe)).when(manager).getRecipes();
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            EndCraftingRecipeHandler handler = new EndCraftingRecipeHandler();
            try (var handlers = mockStatic(ModRecipeHandlers.class)) {
                handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                        .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
                handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                        .thenAnswer(call -> handler.getResultItem(recipe, RegistryAccess.EMPTY));
                assertTrue(RecipeIndex.isBackgroundProjectable(recipe));
                RecipeIndex.BuildSession session = newSession(level);
                session.advance(1L, new AtomicLong()::getAndIncrement);
                assertEquals(1, session.projected.size());
                RecipeNode captured = session.projected.values().iterator().next().get(0);
                assertEquals(NbtMatchMode.PARTIAL, captured.inputs().get(0).nbtMatchMode());
                assertEquals(2, captured.inputs().get(0).alternatives().size());
                assertEquals(4, captured.outputCount());

                // 唯一特殊节点位于 1024 层依赖末端；目录探针用默认有限预算，完整搜索继续后台运行。
                int chainLength = 1024;
                int repeats = 1000;
                Map<MaterialRef, List<RecipeNode>> nodes = new HashMap<>(session.projected);
                MaterialRef previous = captured.output();
                ResourceLocation targetId = captured.recipeId();
                for (int index = 0; index < chainLength; index++) {
                    targetId = new ResourceLocation("test", "long_chain_" + index);
                    MaterialRef output = new MaterialRef(targetId, "");
                    nodes.put(output, List.of(new RecipeNode(targetId, output, 1,
                            List.of(new IngredientRef(List.of(previous), 1)))));
                    previous = output;
                }
                CompoundTag actual = required.copy();
                actual.putString("owner", "test");
                if (!matchingStock) actual.putInt("level", 1);
                Map<CraftingResolver.StackKey, Long> stock = Map.of(
                        new CraftingResolver.StackKey(Items.EMERALD, actual.toString()), (long) repeats / 4);
                PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1L, 1L, targetId,
                        stock, Map.of(), new ImmutableRecipeGraph(nodes), "storage", "machines", false);
                CompletableFuture<AsyncPurePlanningService.RoutedPlan> answer = new CompletableFuture<>();
                clearInvocations(recipe, level);
                try (var coordinator = new AsyncPlanningCoordinator(1, 1)) {
                    new AsyncPurePlanningService(coordinator).submitRouted(snapshot,
                            AsyncPurePlanningService.RouteInputs.fromItems(stock, 128, Set.of(), Set.of(), Set.of()),
                            repeats, Runnable::run, chainLength + 2, 100_000, 10_000, 10_000,
                            answer::complete, answer::completeExceptionally);
                    var routed = answer.get(15, TimeUnit.SECONDS);
                    assertNotNull(routed.plan(), "不能因多候选子集 NBT 材料让整条长链回退同步解析");
                    assertEquals(matchingStock, routed.plan().feasible());
                    if (matchingStock) {
                        assertEquals(chainLength, routed.plan().steps().size());
                        assertEquals(repeats / 4, routed.plan().steps().stream()
                                .filter(step -> step.recipeId().equals(captured.recipeId())).findFirst().orElseThrow().batches());
                    }
                }
                verifyNoInteractions(recipe, level);
            }
        });
    }

    private static Recipe<?> recipe(String name) {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", name));
        return recipe;
    }

    private static RecipeIndex.BuildSession newSession(ServerLevel level) throws Exception {
        Field epoch = RecipeIndex.class.getDeclaredField("buildEpoch");
        epoch.setAccessible(true);
        return new RecipeIndex.BuildSession(level, epoch.getLong(null));
    }

    private static RecipeIndex.BuildResult emptyResult() {
        return RecipeIndex.finalizeBuild(new RecipeIndex.BuildInput(
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of()));
    }

    private static void withServer(ServerWork work) throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager manager = mock(RecipeManager.class);
        when(server.isSameThread()).thenReturn(true);
        when(level.getServer()).thenReturn(server);
        when(level.getRecipeManager()).thenReturn(manager);
        doReturn(List.of()).when(manager).getRecipes();
        ModList mods = mock(ModList.class);
        try (var lifecycle = mockStatic(ServerLifecycleHooks.class);
             var modList = mockStatic(ModList.class)) {
            lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
            modList.when(ModList::get).thenReturn(mods);
            RecipeIndex.invalidate();
            try {
                work.run(level, manager);
            } finally {
                RecipeIndex.invalidate();
            }
        }
    }

    @FunctionalInterface
    private interface ServerWork {
        void run(ServerLevel level, RecipeManager manager) throws Exception;
    }
}
