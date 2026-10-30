package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import com.huanghuang.rsintegration.mods.farmersdelight.CuttingBoardRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.huanghuang.rsintegration.crafting.graph.OutputKind.PRIMARY;

class AsyncProbabilitySettlementTest extends BootstrapTest {
    @BeforeAll
    static void config() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        // 此测试执行真实链结算，只替代 Forge 运行环境提供的事件总线入口。
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void realCuttingRecipeExpandsAsAProbabilisticIntermediateInTheTypedResolver() {
        CuttingBoardRecipe cutting = new CuttingBoardRecipe(
                new ResourceLocation("test", "cutting_intermediate"), "",
                Ingredient.of(Items.PAPER), Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.EGG), 0.35F)), "");
        ShapelessRecipe terminal = new ShapelessRecipe(
                new ResourceLocation("test", "egg_terminal"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.GOLD_NUGGET),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.EGG)));
        ServerLevel level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();
        List<String> missing = new ArrayList<>();
        try (MockedStatic<ModRecipeHandlers> handlers =
                     mockStatic(ModRecipeHandlers.class, CALLS_REAL_METHODS);
             MockedStatic<RecipeIndex> index = mockStatic(RecipeIndex.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(cutting))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.EGG, List.of(new RecipeIndex.Entry(cutting, ModType.GENERIC,
                            new ResourceLocation("farmersdelight", "cutting"))),
                    Items.GOLD_NUGGET, List.of(new RecipeIndex.Entry(terminal, ModType.GENERIC,
                            new ResourceLocation("minecraft", "crafting")))));

            CraftPlanGraph graph = CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(Ingredient.of(Items.GOLD_NUGGET), 1)),
                    Map.of(new CraftingResolver.StackKey(Items.PAPER, null), 1L,
                            new CraftingResolver.StackKey(Items.IRON_SWORD, null), 1L),
                    level, null, null, missing, null, false, 10_000);

            assertTrue(missing.isEmpty(), missing.toString());
            assertEquals(2, graph.nodes().size());
            CraftNode intermediate = graph.nodes().stream()
                    .filter(node -> node.recipeId().equals(cutting.getId())).findFirst().orElseThrow();
            assertEquals(0L, intermediate.outputs().get(0).guaranteedMinimum());
            assertTrue(intermediate.outputs().get(0).targetProducing());
            assertTrue(AsyncCraftChain.requiresAdaptiveProduction(graph));
        }
    }

    @Test
    void typedIntermediateKeepsItsTargetSeparateFromGuaranteedOutput() {
        Recipe<?> recipe = mock(Recipe.class);
        ResourceLocation recipeId = new ResourceLocation("test", "random_intermediate");
        when(recipe.getId()).thenReturn(recipeId);
        ServerLevel level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.getResultItem(recipe, RegistryAccess.EMPTY))
                .thenReturn(new ItemStack(Items.EGG));
        doCallRealMethod().when(handler).requiredIngredientCount(
                eq(recipe), any(IngredientSpec.class), anyInt(), anyInt());
        when(handler.requiresTargetedProduction(eq(recipe), any(ItemStack.class)))
                .thenReturn(true);
        when(handler.getDeclaredOutputs(recipe, RegistryAccess.EMPTY))
                .thenReturn(List.of(RecipeOutputSpec.item(
                        MaterialKey.of(new ItemStack(Items.EGG)), 0, 0, 1, PRIMARY,
                        OutputProbability.probabilistic(0.35, 16, "egg"), false, null)));
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class);
             MockedStatic<RecipeIndex> index = mockStatic(RecipeIndex.class);
             MockedStatic<CraftPacketUtils> packets = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            packets.when(() -> CraftPacketUtils.extractIngredientSpecs(recipe))
                    .thenReturn(List.of(new IngredientSpec(Ingredient.of(Items.PAPER), 1)));
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            ResolutionContext context = new ResolutionContext(level, Map.of(),
                    List.of(new ItemStack(Items.PAPER, 2)), null);
            RecipeIndex.Entry entry = new RecipeIndex.Entry(recipe, ModType.GENERIC,
                    new ResourceLocation("test", "random"));

            assertTrue(StepExecutor.craftBatched(entry, context, 0, List.of(), List.of(),
                    new CraftingResolver.EdgeTracker(), 2, new ItemStack(Items.EGG)));

            OutputDeclaration output = context.graphNodes.get(0).outputs().get(0);
            assertEquals(0L, output.guaranteedMinimum());
            assertEquals(2L, output.quantity());
            assertTrue(output.targetProducing());
            assertEquals(2L, ExecutionEquivalence.projectStep(context.graphNodes.get(0))
                    .productionTarget().quantity());
        }
    }

    @Test
    void probabilityGraphUsesAdaptiveInputsAndFixedAttemptsKeepTheirOwnCompletionRule() throws Exception {
        Fixture fixture = fixture(null, 1);
        NodeId nodeId = new NodeId(0);
        MaterialKey egg = MaterialKey.of(new ItemStack(Items.EGG));
        OutputDeclaration output = OutputDeclaration.item(new OutputPortId(nodeId, 0),
                egg, 3, PRIMARY, 0, 3,
                OutputProbability.probabilistic(0.35, 16, "egg"), false, null);
        CraftPlanGraph fixed = singleNodeGraph(nodeId, output);
        CraftPlanGraph targeted = singleNodeGraph(nodeId, output.withTargetProducing(true));

        assertNull(ExecutionEquivalence.projectFlatSteps(fixed).get(0).productionTarget());
        assertFalse(AsyncCraftChain.requiresAdaptiveProduction(fixed));
        assertTrue(AsyncCraftChain.requiresAdaptiveProduction(targeted));
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(),
                fixture.player.getServer(), null, targeted);
        assertFalse(chain.isGraphExecution());
        assertEquals(3L, ExecutionEquivalence.projectFlatSteps(targeted).get(0)
                .productionTarget().quantity());
    }

    @Test
    void randomMissSettlesOperationWhileTheIntermediateTargetStillWaits() {
        NodeId node = new NodeId(0);
        OutputPortId port = new OutputPortId(node, 0);
        OutputDeclaration output = OutputDeclaration.item(port,
                MaterialKey.of(new ItemStack(Items.EGG)), 2, PRIMARY, 0, 2,
                OutputProbability.probabilistic(0.35, 16, "egg"), false, null);
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.publishesDeclaredGraphOutputs()).thenReturn(true);
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:random", delegate, null, null);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(output)));
        runtime.targetCompletion(port, 2L);

        assertTrue(runtime.operationOutputsComplete());
        assertFalse(runtime.productionTargetComplete());
        assertFalse(runtime.outputsComplete());
        assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                runtime.completeOperation(runtime::operationOutputsComplete, () -> {}));
    }

    private CraftPlanGraph singleNodeGraph(NodeId nodeId, OutputDeclaration output) {
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "random"),
                ModType.GENERIC.id(), new ResourceLocation("test", "random"), 3,
                List.of(), List.of(), false, null, null, List.of(), List.of(output));
        return new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.EGG), 3L, 0L, new ItemStack(Items.EGG),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(output.id()),
                                output.material(), 3L)))), List.of(), List.of(nodeId));
    }

    @Test
    void flatChainWaitsThroughMissesAndKeepsRealOutputsUntilTargetIsMet() throws Exception {
        Fixture fixture = fixture(ProductionTarget.of(new ItemStack(Items.EGG), 3), 1);
        ProbabilisticProduction progress = new ProbabilisticProduction(fixture.step.productionTarget(), 16);
        targets(fixture.chain).put(fixture.step, progress);
        progress.started();
        settle(fixture, List.of());
        assertEquals(0, field(fixture.chain, "currentStepIdx"));
        assertEquals(1, field(fixture.chain, "stepRemaining"));
        progress.started();
        settle(fixture, List.of(new ItemStack(Items.EGG)));
        assertEquals(0, field(fixture.chain, "currentStepIdx"));
        assertEquals(1, inventory(fixture.chain).get(0).getCount());
        progress.started();
        settle(fixture, List.of(new ItemStack(Items.EGG, 2)));
        assertEquals(1, field(fixture.chain, "currentStepIdx"));
        assertEquals(3, inventory(fixture.chain).get(0).getCount());
        assertTrue(progress.complete());
    }

    @Test
    void directAttemptWithoutRecursiveTargetStillCompletesOnAMiss() throws Exception {
        Fixture fixture = fixture(null, 1);
        settle(fixture, List.of());
        assertEquals(1, field(fixture.chain, "currentStepIdx"));
        assertTrue(inventory(fixture.chain).isEmpty());
    }

    @Test
    void fluidProductionTargetUsesResourceContractInsteadOfMaterial()
            throws Exception {
        IntegrationResourceKey water =
                IntegrationResourceKey.fluid(
                        new FluidStack(Fluids.WATER, 1));
        Fixture fixture = fixture(
                ProductionTarget.of(water, 750L), 1);
        Recipe<?> recipe = mock(Recipe.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(fixture.player.getServer().getRecipeManager())
                .thenReturn(recipes);
        doReturn(Optional.of(recipe))
                .when(recipes)
                .byKey(fixture.step.recipeId());
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.requiresTargetedProduction(recipe, water))
                .thenReturn(true);

        try (MockedStatic<ModRecipeHandlers> handlers =
                     mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(
                            handler, List.of(handler)));
            Method requires = AsyncCraftChain.class
                    .getDeclaredMethod(
                            "requiresTargetedProduction",
                            CraftingResolver.ResolutionStep.class
                    );
            requires.setAccessible(true);

            assertEquals(true,
                    requires.invoke(fixture.chain, fixture.step));
        }
    }

    @Test
    void flatChainAdvancesFluidProgressFromReportedResources()
            throws Exception {
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        IntegrationResourceKey resource =
                IntegrationResourceKey.fluid(water);
        Fixture fixture = fixture(
                ProductionTarget.of(resource, 750L), 1);
        ProbabilisticProduction progress =
                new ProbabilisticProduction(
                        fixture.step.productionTarget(), 16);
        targets(fixture.chain).put(fixture.step, progress);

        progress.started();
        settle(fixture, List.of(), List.of(
                new CollectedOutput(
                        resource,
                        500L,
                        OutputKind.PRIMARY,
                        new ResourceLocation("test", "water")
                )
        ));
        assertEquals(0, field(fixture.chain, "currentStepIdx"));
        assertEquals(500L, progress.produced());

        progress.started();
        settle(fixture, List.of(), List.of(
                new CollectedOutput(
                        resource,
                        250L,
                        OutputKind.PRIMARY,
                        new ResourceLocation("test", "water")
                )
        ));
        assertEquals(1, field(fixture.chain, "currentStepIdx"));
        assertTrue(progress.complete());
    }

    @Test
    void flatChainAdvancesFluidProgressFromObservedProduction()
            throws Exception {
        IntegrationResourceKey water =
                IntegrationResourceKey.fluid(
                        new FluidStack(Fluids.WATER, 1));
        Fixture fixture = fixture(
                ProductionTarget.of(water, 250L), 1);
        ProbabilisticProduction progress =
                new ProbabilisticProduction(
                        fixture.step.productionTarget(), 16);
        targets(fixture.chain).put(fixture.step, progress);

        progress.started();
        settle(
                fixture,
                List.of(),
                List.of(),
                List.of(new CollectedOutput(
                        water,
                        250L,
                        OutputKind.PRIMARY,
                        new ResourceLocation("test", "water")
                ))
        );

        assertEquals(1, field(fixture.chain, "currentStepIdx"));
        assertEquals(250L, progress.produced());
        assertTrue(progress.complete());
    }

    @Test
    void supplementalPlanIsInsertedBeforeTheWaitingStepWithoutLosingProductionProgress() throws Exception {
        Fixture fixture = fixture(ProductionTarget.of(new ItemStack(Items.EGG), 3), 1);
        ProbabilisticProduction progress = new ProbabilisticProduction(fixture.step.productionTarget(), 16);
        progress.started();
        progress.settle(List.of(new ItemStack(Items.EGG)));
        inventory(fixture.chain).add(new ItemStack(Items.EGG));
        targets(fixture.chain).put(fixture.step, progress);
        Recipe<?> recipe = mock(Recipe.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(fixture.player.getServer().getRecipeManager()).thenReturn(recipes);
        doReturn(Optional.of(recipe)).when(recipes).byKey(fixture.step.recipeId());
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        List<IngredientSpec> needed = List.of(new IngredientSpec(Ingredient.of(Items.PAPER), 1));
        when(handler.getIngredients(recipe)).thenReturn(needed);
        NodeId nodeId = new NodeId(0);
        OutputPortId port = new OutputPortId(nodeId, 0);
        MaterialKey paper = MaterialKey.of(new ItemStack(Items.PAPER));
        CraftNode producer = new CraftNode(nodeId, new ResourceLocation("test", "paper"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"), 1,
                List.of(), List.of(), false, null, null, List.of(),
                List.of(OutputDeclaration.item(port, paper, 1, PRIMARY)));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(producer), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.PAPER), 1, 0, new ItemStack(Items.PAPER),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), paper, 1)))),
                List.of(), List.of(nodeId));
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class);
             MockedStatic<MaterialSources> sources = mockStatic(MaterialSources.class);
             MockedStatic<CraftingResolver> resolver = mockStatic(CraftingResolver.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            sources.when(() -> MaterialSources.listAllAvailable(fixture.player, (CraftStorageEndpoint) null))
                    .thenReturn(Map.of());
            resolver.when(() -> CraftingResolver.resolveSupplementalGraph(anyList(), anyMap(), any(),
                    eq(fixture.player), isNull(), anyList(), anySet(), anyMap(), anyMap(), any()))
                    .thenReturn(graph);
            Method prepare = AsyncCraftChain.class.getDeclaredMethod("prepareProbabilityInputs",
                    CraftingResolver.ResolutionStep.class, ProbabilisticProduction.class, ServerPlayer.class);
            prepare.setAccessible(true);
            assertEquals(false, prepare.invoke(fixture.chain, fixture.step, progress, fixture.player));
        }
        @SuppressWarnings("unchecked")
        List<CraftingResolver.ResolutionStep> steps = (List<CraftingResolver.ResolutionStep>) field(fixture.chain, "steps");
        assertEquals(producer.recipeId(), steps.get(0).recipeId());
        assertSame(fixture.step, steps.get(1));
        assertSame(progress, targets(fixture.chain).get(steps.get(1)));
        assertEquals(1, progress.produced());
        assertEquals(1, inventory(fixture.chain).get(0).getCount());
        assertEquals(0, field(fixture.chain, "stepRemaining"));
    }

    private Fixture fixture(ProductionTarget target, int executions) throws Exception {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.serverLevel()).thenReturn(mock(ServerLevel.class));
        when(player.getServer()).thenReturn(server);
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "random"), ModType.GENERIC,
                new ResourceLocation("test", "random"), List.of(), List.of(), false,
                executions, null, null, target);
        AsyncCraftChain chain = new AsyncCraftChain(owner, server, null, List.of(step));
        set(chain, "state", AsyncCraftChain.State.EXECUTING);
        set(chain, "stepRemaining", executions);
        return new Fixture(chain, step, player);
    }

    private void settle(Fixture fixture, List<ItemStack> outputs) throws Exception {
        settle(fixture, outputs, List.of(), List.of());
    }

    private void settle(
            Fixture fixture,
            List<ItemStack> outputs,
            List<CollectedOutput> reported
    ) throws Exception {
        settle(fixture, outputs, reported, List.of());
    }

    private void settle(
            Fixture fixture,
            List<ItemStack> outputs,
            List<CollectedOutput> reported,
            List<CollectedOutput> observed
    ) throws Exception {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.observeCraft(any())).thenReturn(new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.DONE));
        when(delegate.validateExecutionContext(fixture.player)).thenReturn(true);
        when(delegate.collectAllResults(fixture.player)).thenReturn(outputs);
        when(delegate.drainReportedResources()).thenReturn(reported);
        when(delegate.drainObservedProduction()).thenReturn(observed);
        when(delegate.collectsPhysicalSecondaryOutputs()).thenReturn(true);
        set(fixture.chain, "currentDelegate", delegate);
        assertFalse(fixture.chain.tick());
        assertEquals(AsyncCraftChain.State.EXECUTING, fixture.chain.state());
    }

    @SuppressWarnings("unchecked")
    private Map<CraftingResolver.ResolutionStep, ProbabilisticProduction> targets(AsyncCraftChain chain) throws Exception {
        return (Map<CraftingResolver.ResolutionStep, ProbabilisticProduction>) field(chain, "probabilityTargets");
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> inventory(AsyncCraftChain chain) throws Exception {
        return (List<ItemStack>) field(chain, "virtualInventory");
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(AsyncCraftChain chain, CraftingResolver.ResolutionStep step, ServerPlayer player) {}
}
