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
import com.huanghuang.rsintegration.crafting.AsyncCraftChain.RetryPreparation;
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
import net.minecraft.network.chat.Component;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
            var job = mock(CraftingResolver.TypedPreview.class);
            when(job.advance(anyLong(), any())).thenReturn(false, false, true);
            var allowed = new AtomicBoolean(true);
            var budget = AsyncCraftChain.class.getDeclaredField("dispatchAllowed");
            budget.setAccessible(true);
            budget.set(chain, (BooleanSupplier) allowed::get);
            when(job.graph()).thenAnswer(call -> {
                allowed.set(false);
                return supplement;
            });
            resolver.when(() -> CraftingResolver.startSupplementalGraph(anyList(), anyMap(), any(), eq(player),
                    isNull(), anyList(), anySet(), anyMap(), anyMap(), any())).thenAnswer(call -> {
                assertTrue(call.getArgument(1, Map.class).isEmpty(), "根需求的纸不能用于补产");
                assertTrue(call.getArgument(6, Set.class).contains(node.recipeId()));
                return job;
            });
            for (int tick = 0; tick < 2; tick++) {
                assertEquals(RetryPreparation.PLANNING, chain.prepareRetryRound(id, player));
                scheduler.releaseClaim(id);
                assertEquals(List.of(id), scheduler.nodeOrder());
                assertEquals(DagScheduler.NodeState.READY, scheduler.state(id));
                verify(account, never()).ensure(anyMap(), anyList(), any());
                scheduler.claim(id);
            }
            assertEquals(RetryPreparation.PLANNING, chain.prepareRetryRound(id, player));
            verify(account, never()).ensure(anyMap(), anyList(), any());
            assertEquals(List.of(id), scheduler.nodeOrder());
            scheduler.releaseClaim(id);
            allowed.set(true);
            scheduler.claim(id);
            assertEquals(RetryPreparation.WAITING_DEPENDENCIES, chain.prepareRetryRound(id, player));
            scheduler.releaseClaim(id);
            assertEquals(DagScheduler.NodeState.BLOCKED, scheduler.state(id));
            var extra = scheduler.claimReady(1).get(0);
            assertNotEquals(id, extra);
            var produced = new MaterialSource.ProducerOutput(new OutputPortId(extra, 0));
            broker.publishActual(produced, paper, paper.toStack(1));
            scheduler.succeed(extra);
            scheduler.claim(id);
            assertEquals(RetryPreparation.READY, chain.prepareRetryRound(id, player));
            verify(job, times(1)).graph();
            verify(account, times(1)).ensure(anyMap(), anyList(), eq(player));
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

    private final class CompetingRetries {
        final ServerPlayer player = mock(ServerPlayer.class);
        final RuntimeMaterialAccount account = mock(RuntimeMaterialAccount.class);
        final MaterialKey paper = MaterialKey.of(new ItemStack(Items.PAPER));
        final MaterialSource.InitialPool pool = new MaterialSource.InitialPool(paper);
        final AsyncCraftChain chain;
        final DagScheduler scheduler;
        final MaterialBroker broker;
        final NodeId first = new NodeId(0), second = new NodeId(1);

        CompetingRetries() throws Exception {
            var server = mock(MinecraftServer.class);
            when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
            when(player.serverLevel()).thenReturn(mock(ServerLevel.class));
            when(player.getServer()).thenReturn(server);
            List<CraftNode> nodes = new ArrayList<>();
            List<RootDemand> roots = new ArrayList<>();
            List<MaterialAllocation> allocations = new ArrayList<>();
            for (var id : List.of(first, second)) {
                var egg = MaterialKey.of(new ItemStack(Items.EGG));
                var port = new OutputPortId(id, 0);
                var slot = new InputPortId(id, 0);
                var target = OutputDeclaration.item(port, egg, 2, PRIMARY, 0, 2,
                        OutputProbability.probabilistic(0.35, 16, "egg"), false, null).withTargetProducing(true);
                nodes.add(new CraftNode(id, ResourceLocation.parse("test:target_" + id.value()), ModType.GENERIC.id(),
                        null, 1, List.of(), List.of(), false, null, null, List.of(new InputDemand(slot,
                        Ingredient.of(Items.PAPER), 1, DemandRole.CONSUMED, paper.toStack(1))), List.of(target)));
                allocations.add(new MaterialAllocation(new AllocationId(id.value()), slot, pool, paper, 1));
                roots.add(new RootDemand(Ingredient.of(Items.EGG), 2, 0, egg.toStack(1),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), egg, 2))));
            }
            chain = new AsyncCraftChain(UUID.randomUUID(), server, null, new CraftPlanGraph(1, nodes, allocations,
                    roots, List.of(), List.of(first, second)));
            scheduler = (DagScheduler) field(chain, "graphScheduler");
            broker = (MaterialBroker) field(chain, "graphMaterials");
            var admissions = (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
            var accountField = AsyncCraftChain.class.getDeclaredField("materialAccount");
            accountField.setAccessible(true);
            accountField.set(chain, account);
            for (var id : List.of(first, second)) {
                scheduler.claim(id);
                assertEquals(ConcurrentNodeExecutor.CompletionStatus.RETRY, attempt(chain, player, admissions, id,
                        List.of(new MaterialBroker.Request(pool, paper, 1)), List.of()));
                scheduler.releaseClaim(id);
            }
            assertEquals(0, broker.outstanding(pool, paper));
        }

        RetryPreparation slice(NodeId nodeId, int checks) throws Exception {
            var budget = AsyncCraftChain.class.getDeclaredField("dispatchAllowed");
            budget.setAccessible(true);
            var remaining = new AtomicInteger(checks);
            budget.set(chain, (BooleanSupplier) () -> remaining.getAndDecrement() > 0);
            scheduler.claim(nodeId);
            try {
                return chain.prepareRetryRound(nodeId, player);
            } finally {
                scheduler.releaseClaim(nodeId);
            }
        }

        RetryPreparation finishSearch(NodeId nodeId) throws Exception {
            for (int tick = 0; tick < 100; tick++) {
                var result = slice(nodeId, 10000);
                if (result != RetryPreparation.PLANNING) return result;
            }
            throw new AssertionError("补料未在有限轮次内完成");
        }
    }

    @Test
    void competingSearchesRecheckTheSingleSurplusAndDoNotChargeTwice() throws Exception {
        var f = new CompetingRetries();
        f.chain.virtualInventory().add(f.paper.toStack(1));
        var held = new AtomicInteger();
        when(f.account.heldMaterials()).thenAnswer(call -> held.get() == 0 ? Map.of()
                : Map.of(CraftingResolver.StackKey.of(f.paper.toStack(1), true), (long) held.get()));
        doAnswer(call -> { held.addAndGet(call.getArgument(0, ItemStack.class).getCount()); return null; })
                .when(f.account).add(any(ItemStack.class));
        when(f.account.ensure(anyMap(), anyList(), eq(f.player))).thenAnswer(call ->
                call.<Map<MaterialKey, Long>>getArgument(0).getOrDefault(f.paper, 0L) <= held.get());
        try (var index = mockStatic(RecipeIndex.class); var sources = mockStatic(MaterialSources.class)) {
            for (int tick = 0; tick < 2; tick++) {
                for (var id : List.of(f.first, f.second)) assertEquals(RetryPreparation.PLANNING, f.slice(id, 2));
            }
            assertEquals(1, f.chain.virtualInventory().get(0).getCount());
            verify(f.account, never()).ensure(anyMap(), anyList(), any());
            assertEquals(RetryPreparation.READY, f.finishSearch(f.first));
            assertEquals(RetryPreparation.READY, f.slice(f.first, 10000));
            assertEquals(1, held.get());
            assertTrue(f.chain.virtualInventory().isEmpty());
            assertEquals(RetryPreparation.WAITING_MATERIALS, f.finishSearch(f.second));
            verify(f.account).ensure(eq(Map.of(f.paper, 0L)), anyList(), eq(f.player));
            verify(f.account).ensure(eq(Map.of(f.paper, 2L)), anyList(), eq(f.player));
            verify(f.account, times(1)).add(any(ItemStack.class));
            assertEquals(1, f.broker.outstanding(f.pool, f.paper));
            assertEquals(List.of(f.first, f.second), f.scheduler.nodeOrder());
        }
    }

    @Test
    void cancellingSuspendedSearchDropsContinuationWithoutMaterialAdmission() throws Exception {
        var f = new CompetingRetries();
        try (var index = mockStatic(RecipeIndex.class); var sources = mockStatic(MaterialSources.class)) {
            assertEquals(RetryPreparation.PLANNING, f.slice(f.first, 2));
            f.chain.cancel("test", Component.literal("test"));
            assertTrue(f.chain.isDone());
            assertEquals(RetryPreparation.FAILED, f.chain.prepareRetryRound(f.first, f.player));
            assertTrue(((Map<?, ?>) field(f.chain, "supplementalSearches")).isEmpty());
            verify(f.account, never()).ensure(anyMap(), anyList(), any());
            verify(f.account, never()).add(any(ItemStack.class));
            assertEquals(0, f.broker.outstanding(f.pool, f.paper));
            assertEquals(List.of(f.first, f.second), f.scheduler.nodeOrder());
        }
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
            assertEquals(RetryPreparation.READY, chain.prepareRetryRound(id, player));
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
            assertEquals(RetryPreparation.READY, chain.prepareRetryRound(port.nodeId(), player));
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
