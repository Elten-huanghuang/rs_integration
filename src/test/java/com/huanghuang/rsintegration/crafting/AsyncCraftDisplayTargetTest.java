package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronAlchemistRecipeCatalog;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncCraftDisplayTargetTest extends BootstrapTest {
    @BeforeAll
    static void configureEnvironment() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void missingClickedTargetFallsBackToTerminalRecipeResult() throws Exception {
        MinecraftServer server = server();
        CraftingResolver.ResolutionStep step = step(ModType.GENERIC, null, null);
        Recipe<?> recipe = mock(Recipe.class);
        RecipeManager recipes = server.getRecipeManager();
        doReturn(Optional.of(recipe)).when(recipes).byKey(step.recipeId());
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                    .thenReturn(new ItemStack(Items.DIAMOND));
            AsyncCraftChain chain = chain(server, List.of(step));
            assertTrue(chain.displayTarget().is(Items.DIAMOND));
            assertTrue(chain.nextStatusSnapshot().nodes().get(0).displayOutput().is(Items.DIAMOND));
            assertNoExecutionTarget(chain);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void cauldronDisplayResolvesRuntimeCatalogOutsideRecipeManager() throws Exception {
        MinecraftServer server = server();
        ModType type = mock(ModType.class);
        when(type.id()).thenReturn("irons_spellbooks_alchemist_cauldron");
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("rs_integration", "irons_spellbooks/alchemist/test"),
                type, new ResourceLocation("test", "machine"));
        IronSpellBooksRecipe recipe = mock(IronSpellBooksRecipe.class);
        // 直接安装运行时目录快照，测试真实 byId 查询；无需启动 Iron 法术注册环境。
        Field catalogField = IronAlchemistRecipeCatalog.class.getDeclaredField("CATALOGS");
        catalogField.setAccessible(true);
        Map<RecipeManager, List<IronSpellBooksRecipe>> catalogs =
                (Map<RecipeManager, List<IronSpellBooksRecipe>>) catalogField.get(null);
        Map<RecipeManager, List<IronSpellBooksRecipe>> previousCatalogs = new LinkedHashMap<>(catalogs);
        Field idsField = IronAlchemistRecipeCatalog.class.getDeclaredField("BY_ID");
        idsField.setAccessible(true);
        Map<ResourceLocation, IronSpellBooksRecipe> ids = (Map<ResourceLocation, IronSpellBooksRecipe>) idsField.get(null);
        Map<ResourceLocation, IronSpellBooksRecipe> previousIds = new LinkedHashMap<>(ids);
        catalogs.put(server.getRecipeManager(), List.of(recipe));
        ids.put(step.recipeId(), recipe);
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                    .thenReturn(new ItemStack(Items.POTION));
            AsyncCraftChain chain = chain(server, List.of(step));
            assertTrue(chain.displayTarget().is(Items.POTION));
            assertTrue(chain.nextStatusSnapshot().nodes().get(0).displayOutput().is(Items.POTION));
            assertNoExecutionTarget(chain);
        } finally {
            catalogs.clear();
            catalogs.putAll(previousCatalogs);
            ids.clear();
            ids.putAll(previousIds);
        }
    }

    @Test
    void productionTargetPreservesRequestedMaterialInsteadOfDefaultOutput() {
        CraftingResolver.ResolutionStep step = step(ModType.GENERIC,
                new ItemStack(Items.IRON_INGOT), ProductionTarget.of(new ItemStack(Items.EGG), 250));
        AsyncCraftChain chain = chain(server(), List.of(step));
        assertTrue(chain.displayTarget().is(Items.EGG));
        assertEquals(1, chain.displayTarget().getCount());
    }

    @Test
    void explicitTargetWinsAndReturnedStackCannotMutateIt() {
        AsyncCraftChain chain = chain(server(), List.of(step(ModType.GENERIC,
                new ItemStack(Items.IRON_INGOT), null)));
        ItemStack clicked = new ItemStack(Items.DIAMOND, 4);
        clicked.getOrCreateTag().putString("variant", "requested");
        chain.setTargetOutput(clicked);
        ItemStack displayed = chain.displayTarget();
        assertTrue(ItemStack.matches(clicked, displayed));
        displayed.setCount(1);
        displayed.getTag().putString("variant", "changed");
        assertTrue(ItemStack.matches(clicked, chain.displayTarget()));
    }

    @Test
    void fluidTargetRemainsAResourceInsteadOfBecomingAFakeItem()
            throws Exception {
        AsyncCraftChain chain = chain(server(), List.of(step(
                ModType.GENERIC,
                new ItemStack(Items.IRON_INGOT),
                null
        )));
        IntegrationResourceKey water = IntegrationResourceKey.fluid(
                new FluidStack(Fluids.WATER, 1));

        chain.setTargetResource(water);

        Field itemTarget = AsyncCraftChain.class
                .getDeclaredField("targetOutput");
        itemTarget.setAccessible(true);
        assertNull(itemTarget.get(chain));
        Field resourceTarget = AsyncCraftChain.class
                .getDeclaredField("targetResource");
        resourceTarget.setAccessible(true);
        assertEquals(water, resourceTarget.get(chain));
    }

    @Test
    void completeGraphDisplaysItsRootMaterialIncludingSecondaryOutputs() throws Exception {
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), server(), null, graph());
        assertTrue(chain.displayTarget().is(Items.GOLD_INGOT));
        assertNoExecutionTarget(chain);
    }

    @Test
    void emptyChainHasNoInventedDisplayTarget() {
        assertTrue(chain(server(), List.of()).displayTarget().isEmpty());
    }

    @Test
    void incrementalGraphPublicationsUpdateTheSecondaryTargetWithoutCountingThePrimaryOutput() throws Exception {
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), server(), null, graph(64, 16));
        assertEquals(new CraftOutputProgress(0L, 64L), chain.nextStatusSnapshot().outputProgress());
        NodeId node = new NodeId(0);
        OutputPortId secondary = new OutputPortId(node, 1);
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CraftNodeRuntime runtime = mock(CraftNodeRuntime.class);
        when(runtime.drainIncrementalOutputs()).thenReturn(
                List.of(new NodeOutputAccumulator.Publication(secondary, gold,
                        new ItemStack(Items.GOLD_INGOT, 4), IntegrationResourceKey.item(new ItemStack(Items.GOLD_INGOT)))),
                List.of(new NodeOutputAccumulator.Publication(new OutputPortId(node, 0), iron,
                        new ItemStack(Items.IRON_INGOT, 16), IntegrationResourceKey.item(new ItemStack(Items.IRON_INGOT)))),
                List.of(new NodeOutputAccumulator.Publication(secondary, gold,
                        new ItemStack(Items.GOLD_INGOT, 60), IntegrationResourceKey.item(new ItemStack(Items.GOLD_INGOT)))));
        Method publish = AsyncCraftChain.class.getDeclaredMethod("publishIncrementalGraphOutputs",
                NodeId.class, ConcurrentNodeExecutor.Worker.class);
        publish.setAccessible(true);
        publish.invoke(chain, node, runtime);
        assertEquals(new CraftOutputProgress(4L, 64L), chain.nextStatusSnapshot().outputProgress());
        publish.invoke(chain, node, runtime);
        assertEquals(new CraftOutputProgress(4L, 64L), chain.outputProgress());
        publish.invoke(chain, node, runtime);
        assertEquals(new CraftOutputProgress(64L, 64L), chain.outputProgress());
    }

    @Test
    void flatRecipeWithFourOutputsPerOperationShowsEightOfSixtyFourAfterTwoOperations() throws Exception {
        var step = new CraftingResolver.ResolutionStep(new ResourceLocation("test", "batch"), ModType.GENERIC,
                new ResourceLocation("test", "machine"), List.of(), List.of(), false, 16, null,
                new ItemStack(Items.DIAMOND, 4), null);
        MinecraftServer server = server();
        Recipe<?> recipe = mock(Recipe.class);
        RecipeManager recipes = server.getRecipeManager();
        doReturn(Optional.of(recipe)).when(recipes).byKey(step.recipeId());
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                    .thenReturn(new ItemStack(Items.DIAMOND, 4));
            AsyncCraftChain chain = chain(server, List.of(step));
            assertEquals(new CraftOutputProgress(0L, 64L), chain.outputProgress());
            Field remaining = AsyncCraftChain.class.getDeclaredField("stepRemaining");
            remaining.setAccessible(true);
            remaining.setInt(chain, 14);
            assertEquals(new CraftOutputProgress(8L, 64L), chain.nextStatusSnapshot().outputProgress());
        }
    }

    @Test
    void probabilisticOutputProgressCountsRealHitsAndSurvivesCancellation() throws Exception {
        ProductionTarget target = ProductionTarget.of(new ItemStack(Items.EGG), 64L);
        var step = step(ModType.GENERIC, new ItemStack(Items.EGG), target);
        AsyncCraftChain chain = chain(server(), List.of(step));
        ProbabilisticProduction production = new ProbabilisticProduction(target, 100);
        Field progress = AsyncCraftChain.class.getDeclaredField("probabilityTargets");
        progress.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<CraftingResolver.ResolutionStep, ProbabilisticProduction> targets =
                (Map<CraftingResolver.ResolutionStep, ProbabilisticProduction>) progress.get(chain);
        targets.put(step, production);
        production.started();
        production.settle(List.of());
        assertEquals(new CraftOutputProgress(0L, 64L), chain.outputProgress());
        production.settle(List.of(new ItemStack(Items.EGG, 2)));
        assertEquals(new CraftOutputProgress(2L, 64L), chain.outputProgress());
        Field state = AsyncCraftChain.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(chain, AsyncCraftChain.State.ABORTED);
        assertEquals(new CraftOutputProgress(2L, 64L), chain.nextStatusSnapshot().outputProgress());
    }

    private static MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
        RecipeManager recipes = mock(RecipeManager.class);
        when(server.getRecipeManager()).thenReturn(recipes);
        when(recipes.byKey(any())).thenReturn(Optional.empty());
        ServerLevel level = mock(ServerLevel.class);
        when(level.getRecipeManager()).thenReturn(recipes);
        when(server.overworld()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        return server;
    }

    private static AsyncCraftChain chain(MinecraftServer server, List<CraftingResolver.ResolutionStep> steps) {
        return new AsyncCraftChain(UUID.randomUUID(), server, null, steps);
    }

    private static CraftingResolver.ResolutionStep step(ModType type, ItemStack syntheticOutput,
                                                       ProductionTarget target) {
        return new CraftingResolver.ResolutionStep(new ResourceLocation("test", "terminal"), type,
                new ResourceLocation("test", "machine"), List.of(), List.of(), false,
                1, null, syntheticOutput, target);
    }

    private static CraftPlanGraph graph() {
        return graph(1, 1);
    }

    private static CraftPlanGraph graph(int quantity, int executions) {
        NodeId id = new NodeId(0);
        OutputPortId primary = new OutputPortId(id, 0);
        OutputPortId secondary = new OutputPortId(id, 1);
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        CraftNode node = new CraftNode(id, new ResourceLocation("test", "producer"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"), executions,
                List.of(), List.of(), false, null, null, List.of(), List.of(
                OutputDeclaration.item(primary, MaterialKey.of(new ItemStack(Items.IRON_INGOT)), executions, OutputKind.PRIMARY),
                OutputDeclaration.item(secondary, gold, quantity, OutputKind.SECONDARY)));
        RootDemand root = new RootDemand(Ingredient.of(Items.GOLD_INGOT), quantity, 0,
                new ItemStack(Items.GOLD_INGOT),
                List.of(new RootAllocation(new MaterialSource.ProducerOutput(secondary), gold, quantity)));
        return new CraftPlanGraph(1, List.of(node), List.of(), List.of(root), List.of(), List.of(id));
    }

    private static void assertNoExecutionTarget(AsyncCraftChain chain) throws Exception {
        Field field = AsyncCraftChain.class.getDeclaredField("targetOutput");
        field.setAccessible(true);
        assertNull(field.get(chain));
    }
}
