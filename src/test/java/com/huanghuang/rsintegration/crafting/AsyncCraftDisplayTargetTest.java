package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.lang.reflect.Constructor;
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
        Field catalogField = IronSpellBooksRecipeCatalog.class.getDeclaredField("catalog");
        catalogField.setAccessible(true);
        Object previous = catalogField.get(null);
        Constructor<?> constructor = catalogField.getType().getDeclaredConstructor(Map.class, Map.class, long.class);
        constructor.setAccessible(true);
        catalogField.set(null, constructor.newInstance(Map.of(step.recipeId(), recipe), Map.of(), 1L));
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                    .thenReturn(new ItemStack(Items.POTION));
            AsyncCraftChain chain = chain(server, List.of(step));
            assertTrue(chain.displayTarget().is(Items.POTION));
            assertTrue(chain.nextStatusSnapshot().nodes().get(0).displayOutput().is(Items.POTION));
            assertNoExecutionTarget(chain);
        } finally {
            catalogField.set(null, previous);
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
    void completeGraphDisplaysItsRootMaterialIncludingSecondaryOutputs() throws Exception {
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), server(), null, graph());
        assertTrue(chain.displayTarget().is(Items.GOLD_INGOT));
        assertNoExecutionTarget(chain);
    }

    @Test
    void materialsOnlyGraphDoesNotReplaceTerminalTarget() {
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), server(), null, graph(),
                step(ModType.GENERIC, new ItemStack(Items.DIAMOND), null), 1);
        assertTrue(chain.displayTarget().is(Items.DIAMOND));
    }

    @Test
    void emptyChainHasNoInventedDisplayTarget() {
        assertTrue(chain(server(), List.of()).displayTarget().isEmpty());
    }

    private static MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(server.getRecipeManager()).thenReturn(recipes);
        when(recipes.byKey(any())).thenReturn(Optional.empty());
        ServerLevel level = mock(ServerLevel.class);
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
        NodeId id = new NodeId(0);
        OutputPortId primary = new OutputPortId(id, 0);
        OutputPortId secondary = new OutputPortId(id, 1);
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        CraftNode node = new CraftNode(id, new ResourceLocation("test", "producer"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"), 1,
                List.of(), List.of(), false, null, null, List.of(), List.of(
                new OutputDeclaration(primary, MaterialKey.of(new ItemStack(Items.IRON_INGOT)), 1, OutputKind.PRIMARY),
                new OutputDeclaration(secondary, gold, 1, OutputKind.SECONDARY)));
        RootDemand root = new RootDemand(Ingredient.of(Items.GOLD_INGOT), 1, 0,
                new ItemStack(Items.GOLD_INGOT),
                List.of(new RootAllocation(new MaterialSource.ProducerOutput(secondary), gold, 1)));
        return new CraftPlanGraph(1, List.of(node), List.of(), List.of(root), List.of(), List.of(id));
    }

    private static void assertNoExecutionTarget(AsyncCraftChain chain) throws Exception {
        Field field = AsyncCraftChain.class.getDeclaredField("targetOutput");
        field.setAccessible(true);
        assertNull(field.get(chain));
    }
}
