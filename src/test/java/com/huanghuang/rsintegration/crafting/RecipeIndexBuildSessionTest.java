package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            assertThrows(PlanningThreadContext.MainThreadPlanningFallbackException.class,
                    () -> RecipeIndex.warmUpBlocking(level));
            verifyNoInteractions(level);
            assertThrows(PlanningThreadContext.MainThreadPlanningFallbackException.class,
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
                Map.of(), Set.of(), Set.of(), Map.of(), Set.of(output.itemId()));
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
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Set.of()));
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
