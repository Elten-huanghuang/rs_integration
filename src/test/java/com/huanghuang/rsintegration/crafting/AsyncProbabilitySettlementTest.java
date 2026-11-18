package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.ProductionEvidence;
import com.huanghuang.rsintegration.crafting.batch.CraftExecutionPolicy;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
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
import static com.huanghuang.rsintegration.crafting.graph.OutputKind.SECONDARY;

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
            AsyncCraftChain adaptive = new AsyncCraftChain(UUID.randomUUID(),
                    mock(MinecraftServer.class), null, graph);
            assertTrue(adaptive.isGraphExecution());
            try {
                InitialMaterialPlan admission = (InitialMaterialPlan) field(adaptive, "initialMaterialPlan");
                assertEquals(InitialMaterialPlan.from(graph).materials(), admission.materials());
                assertFalse(admission.materials().isEmpty(), "概率节点仍须按图预取初始原料");
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
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
        NodeId nodeId = new NodeId(0);
        MaterialKey egg = MaterialKey.of(new ItemStack(Items.EGG));
        OutputDeclaration output = OutputDeclaration.item(new OutputPortId(nodeId, 0),
                egg, 3, PRIMARY, 0, 3,
                OutputProbability.probabilistic(0.35, 16, "egg"), false, null);
        CraftPlanGraph fixed = singleNodeGraph(nodeId, output);
        CraftPlanGraph targeted = singleNodeGraph(nodeId, output.withTargetProducing(true));

        assertNull(ExecutionEquivalence.projectFlatSteps(fixed).get(0).productionTarget());
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(),
                mock(MinecraftServer.class), null, targeted);
        assertTrue(chain.isGraphExecution());
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
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
        when(delegate.publishesDeclaredGraphOutputs()).thenReturn(true);
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:random", delegate, null, null);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(output)));
        runtime.targetCompletion(port, new ProbabilisticProduction(ProductionTarget.of(new ItemStack(Items.EGG), 2), 16), Map.of());

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
                List.of(new RootDemand(Ingredient.of(output.material().toStack(1)), 3L, 0L, output.material().toStack(1),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(output.id()),
                                output.material(), 3L)))), List.of(), List.of(nodeId));
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void retryConsumesItsSupplementalProducerNotAnotherInitialAllocation() throws Exception {
        NodeId id = new NodeId(0);
        OutputPortId port = new OutputPortId(id, 0);
        InputPortId input = new InputPortId(id, 0);
        MaterialKey paper = MaterialKey.of(new ItemStack(Items.PAPER));
        MaterialKey egg = MaterialKey.of(new ItemStack(Items.EGG));
        var initial = new MaterialSource.InitialPool(paper);
        var target = OutputDeclaration.item(port, egg, 2, PRIMARY, 0, 2,
                OutputProbability.probabilistic(0.35, 16, "egg"), false, null).withTargetProducing(true);
        var node = new CraftNode(id, new ResourceLocation("test", "eggs"), ModType.GENERIC.id(), null, 1,
                List.of(), List.of(), false, null, null, List.of(new InputDemand(input, Ingredient.of(Items.PAPER),
                1, DemandRole.CONSUMED, paper.toStack(1))), List.of(target));
        var graph = new CraftPlanGraph(1, List.of(node),
                List.of(new MaterialAllocation(new AllocationId(0), input, initial, paper, 1)),
                List.of(new RootDemand(Ingredient.of(Items.EGG), 2, 0, egg.toStack(1),
                                List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), egg, 2))),
                        new RootDemand(Ingredient.of(Items.PAPER), 1, 0, paper.toStack(1),
                                List.of(new RootAllocation(initial, paper, 1)))), List.of(), List.of(id));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
        var chain = new AsyncCraftChain(UUID.randomUUID(), server, null, graph);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(mock(ServerLevel.class));
        var scheduler = (DagScheduler) field(chain, "graphScheduler");
        var broker = (MaterialBroker) field(chain, "graphMaterials");
        var admissions = (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
        var account = mock(RuntimeMaterialAccount.class);
        var accountField = AsyncCraftChain.class.getDeclaredField("materialAccount");
        accountField.setAccessible(true);
        accountField.set(chain, account);
        when(account.heldMaterials()).thenReturn(Map.of(CraftingResolver.StackKey.of(paper.toStack(1), true), 1L));
        when(account.ensure(anyMap(), anyList(), eq(player))).thenReturn(true);
        scheduler.claim(id);
        assertEquals(ConcurrentNodeExecutor.CompletionStatus.RETRY, attempt(chain, player, admissions, id,
                List.of(new MaterialBroker.Request(initial, paper, 1)), List.of()));
        assertEquals(0L, broker.available(initial, paper));
        scheduler.releaseClaim(id);
        scheduler.claim(id);
        var producer = new CraftNode(id, new ResourceLocation("test", "paper"), ModType.GENERIC.id(), null,
                1, List.of(), List.of(), false, null, null, List.of(),
                List.of(OutputDeclaration.item(port, paper, 1, PRIMARY)));
        var supplement = new CraftPlanGraph(1, List.of(producer), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.PAPER), 1, 0, paper.toStack(1),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), paper, 1)))), List.of(), List.of(id));
        try (var resolver = mockStatic(CraftingResolver.class); var sources = mockStatic(MaterialSources.class)) {
            resolver.when(() -> CraftingResolver.resolveSupplementalGraph(anyList(), anyMap(), any(), eq(player),
                    isNull(), anyList(), anySet(), anyMap(), anyMap(), any())).thenAnswer(call -> {
                assertTrue(call.getArgument(1, Map.class).isEmpty(), "根需求的纸不能用于补产");
                return supplement;
            });
            assertFalse(chain.prepareRetryRound(id, player));
            scheduler.releaseClaim(id);
            assertEquals(DagScheduler.NodeState.BLOCKED, scheduler.state(id));
            var extra = scheduler.claimReady(1).get(0);
            assertNotEquals(id, extra);
            var produced = new MaterialSource.ProducerOutput(new OutputPortId(extra, 0));
            broker.publishActual(produced, paper, paper.toStack(1));
            scheduler.succeed(extra);
            scheduler.claim(id);
            assertTrue(chain.prepareRetryRound(id, player));
            @SuppressWarnings("unchecked")
            var requests = (Map<NodeId, List<MaterialBroker.Request>>) field(chain, "graphRequests");
            assertEquals(List.of(new MaterialBroker.Request(produced, paper, 1)), requests.get(id));
            assertEquals(ConcurrentNodeExecutor.CompletionStatus.SUCCEEDED,
                    attempt(chain, player, admissions, id, requests.get(id), List.of(egg.toStack(2))));
            scheduler.succeed(id);
            assertTrue(scheduler.allSucceeded());
            assertEquals(2L, chain.outputProgress().completed());
            assertEquals(0L, broker.outstanding(produced, paper));
            assertEquals(2, broker.drainAvailableProducerAssets().stream().mapToInt(ItemStack::getCount).sum());
            assertTrue(broker.drainAvailableProducerAssets().isEmpty());
        }
    }

    private ConcurrentNodeExecutor.CompletionStatus attempt(AsyncCraftChain chain, ServerPlayer player,
            NodeAdmissionCoordinator admissions, NodeId id, List<MaterialBroker.Request> requests, List<ItemStack> outputs) {
        var admission = admissions.tryAdmitClaimed(new NodeAdmissionCoordinator.Candidate(id, requests));
        assertNotNull(admission);
        admissions.commit(admission);
        var delegate = mock(IBatchDelegate.class);
        var machine = mock(MachineOperation.class);
        when(delegate.machineOperation()).thenReturn(machine);
        when(delegate.outputSource()).thenReturn(p -> OperationOutputSource.Handoff.physical(outputs, null));
        when(machine.observe(any())).thenReturn(new MachineOperation.CraftObservation(MachineOperation.CraftPhase.DONE));
        when(delegate.publishesDeclaredGraphOutputs()).thenReturn(true);
        when(delegate.validateExecutionContext(player)).thenReturn(true);
        var runtime = new CraftNodeRuntime(id, "target", delegate, null, admission, null);
        runtime.setChainContext(chain.virtualInventory(), player);
        chain.attachGraphAttempt(runtime, id).started();
        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe(), runtime::failureReason);
        return chain.completeGraphNode(id, runtime);
    }

    @Test
    void machineReceivesTheDeclaredFluidIdentityNotItsDisplayItem() {
        var water = IntegrationResourceKey.fluid(new FluidStack(Fluids.WATER, 1));
        var id = new NodeId(0);
        var declaration = new OutputDeclaration(new OutputPortId(id, 0), MaterialKey.of(new ItemStack(Items.PAPER)),
                3, PRIMARY, 0, 3, OutputProbability.probabilistic(0.5, 16, "water"), false, null, water, true);
        var chain = new AsyncCraftChain(UUID.randomUUID(), mock(MinecraftServer.class), null,
                singleNodeGraph(id, declaration));
        var delegate = mock(AbstractBatchDelegate.class);
        chain.configureNodeTarget(id, delegate);
        verify(delegate).setTargetResource(water);
    }

    @Test
    void observedAndDeliveredFluidCountTowardsTargetWithoutMintingPhysicalTokens() {
        var water = IntegrationResourceKey.fluid(new FluidStack(Fluids.WATER, 1));
        var progress = new ProbabilisticProduction(ProductionTarget.of(water, 750), 2);
        IBatchDelegate delegate = mock(IBatchDelegate.class, withSettings().extraInterfaces(ProductionEvidence.class));
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
        var evidence = ProductionEvidence.from(delegate);
        when(evidence.takeDelivered()).thenReturn(List.of(new CollectedOutput(water, 500,
                OutputKind.PRIMARY, new ResourceLocation("test", "water"))), List.of());
        when(evidence.takeObserved()).thenReturn(List.of(), List.of(new CollectedOutput(water, 250,
                OutputKind.PRIMARY, new ResourceLocation("test", "water"))));
        var runtime = new CraftNodeRuntime(new NodeId(0), "fluid", delegate, null, null);
        runtime.targetCompletion(new OutputPortId(new NodeId(0), 0), progress, Map.of());
        progress.started();
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertEquals(500, progress.produced());
        assertFalse(runtime.productionTargetComplete());
        progress.started();
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertTrue(runtime.productionTargetComplete());
        assertEquals(1, runtime.drainDeliveredReports().size());
        assertFalse(progress.hasAttemptBudget());
    }

    @Test
    void earlyHitStillOwesGuaranteedByproductsAndAMissingRoundFails() throws Exception {
        NodeId id = new NodeId(0);
        var egg = MaterialKey.of(new ItemStack(Items.EGG));
        var paper = MaterialKey.of(new ItemStack(Items.PAPER));
        var target = OutputDeclaration.item(new OutputPortId(id, 0), egg, 2, PRIMARY, 0, 2,
                OutputProbability.probabilistic(0.5, 16, "egg"), false, null).withTargetProducing(true);
        var side = OutputDeclaration.item(new OutputPortId(id, 1), paper, 2, SECONDARY);
        var node = new CraftNode(id, new ResourceLocation("test", "side"), ModType.GENERIC.id(), null,
                2, List.of(), List.of(), false, null, null, List.of(), List.of(target, side));
        var graph = new CraftPlanGraph(1, List.of(node), List.of(), List.of(new RootDemand(Ingredient.of(Items.EGG),
                2, 0, egg.toStack(1), List.of(new RootAllocation(new MaterialSource.ProducerOutput(target.id()), egg, 2)))),
                List.of(), List.of(id));
        var server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
        var player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(mock(ServerLevel.class));
        for (boolean returnsSecondByproduct : List.of(true, false)) {
            var chain = new AsyncCraftChain(UUID.randomUUID(), server, null, graph);
            var scheduler = (DagScheduler) field(chain, "graphScheduler");
            var admissions = (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
            scheduler.claim(id);
            assertEquals(ConcurrentNodeExecutor.CompletionStatus.RETRY, attempt(chain, player, admissions, id,
                    List.of(), List.of(egg.toStack(2), paper.toStack(1))));
            scheduler.releaseClaim(id);
            scheduler.claim(id);
            assertTrue(chain.prepareRetryRound(id, player));
            assertEquals(returnsSecondByproduct ? ConcurrentNodeExecutor.CompletionStatus.SUCCEEDED
                            : ConcurrentNodeExecutor.CompletionStatus.FAILED, attempt(chain, player, admissions, id,
                    List.of(), returnsSecondByproduct ? List.of(paper.toStack(1)) : List.of()));
        }
    }

    @Test
    void repeatedMissesFailAtTheAttemptLimitWithoutPublishingNominalOutput() throws Exception {
        OutputPortId port = new OutputPortId(new NodeId(0), 0);
        var target = OutputDeclaration.item(port, MaterialKey.of(new ItemStack(Items.EGG)), 3, PRIMARY,
                0, 3, OutputProbability.probabilistic(0.35, 16, "egg"), false, null).withTargetProducing(true);
        var server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
        var player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(mock(ServerLevel.class));
        var chain = new AsyncCraftChain(UUID.randomUUID(), server, null, singleNodeGraph(port.nodeId(), target));
        var scheduler = (DagScheduler) field(chain, "graphScheduler");
        var admissions = (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
        int limit = CraftExecutionPolicy.probabilityAttemptLimit(ExecutionEquivalence.projectStep(
                singleNodeGraph(port.nodeId(), target).nodes().get(0)));
        for (int n = 1; n <= limit; n++) {
            scheduler.claim(port.nodeId());
            assertTrue(chain.prepareRetryRound(port.nodeId(), player));
            var result = attempt(chain, player, admissions, port.nodeId(), List.of(),
                    n == 1 ? List.of(new ItemStack(Items.EGG)) : List.of());
            if (n < limit) {
                assertEquals(ConcurrentNodeExecutor.CompletionStatus.RETRY, result);
                scheduler.releaseClaim(port.nodeId());
            } else {
                assertEquals(ConcurrentNodeExecutor.CompletionStatus.FAILED, result);
                scheduler.fail(port.nodeId());
            }
        }
        assertTrue(scheduler.isStopping());
        assertEquals(1L, chain.outputProgress().completed());
    }

}
