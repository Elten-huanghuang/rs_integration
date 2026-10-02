package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
                List.of(new OutputDeclaration(port, paper, 1, OutputKind.PRIMARY)));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(producer), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.PAPER), 1, 0, new ItemStack(Items.PAPER),
                        List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), paper, 1)))),
                List.of(), List.of(nodeId));
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class);
             MockedStatic<MaterialSources> sources = mockStatic(MaterialSources.class);
             MockedStatic<CraftingResolver> resolver = mockStatic(CraftingResolver.class)) {
            handlers.when(() -> ModRecipeHandlers.handlerFor(recipe)).thenReturn(handler);
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
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.observeCraft(any())).thenReturn(new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.DONE));
        when(delegate.validateExecutionContext(fixture.player)).thenReturn(true);
        when(delegate.collectAllResults(fixture.player)).thenReturn(outputs);
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
