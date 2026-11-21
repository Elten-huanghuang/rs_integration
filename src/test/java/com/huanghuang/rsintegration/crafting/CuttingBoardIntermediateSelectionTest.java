package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.mods.farmersdelight.CuttingBoardRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;
import vectorwing.farmersdelight.common.crafting.ingredient.ToolActionIngredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CuttingBoardIntermediateSelectionTest extends BootstrapTest {
    @BeforeAll
    static void loadConfig() {
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
    @CsvSource({"0, 1", "1, 1", "24, 1", "0, 6", "24, 6"})
    void forcedAmethystCuttingKeepsTheSelectedProducerAndDurabilityTool(
            long existingShards, int requestedShards) {
        CraftNode selected = resolveCutting(existingShards, requestedShards,
                new ItemStack(Items.IRON_PICKAXE), 1L);
        assertEquals((requestedShards + 3) / 4, selected.executions());
        assertEquals(DemandRole.DURABILITY, selected.inputs().get(1).role());
    }

    @Test
    void wornToolsReserveEnoughPhysicalItemsForRepeatedCutting() {
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 1);

        CraftNode selected = resolveCutting(0, 8, worn, 2L);

        assertEquals(2L, selected.inputs().get(1).quantity());
    }

    @Test
    void unbreakableToolCanCoverMoreThanItsNormalDurability() {
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.getOrCreateTag().putBoolean("Unbreakable", true);

        CraftNode selected = resolveCutting(0, 1200, tool, 1L);

        assertEquals(300, selected.executions());
        assertEquals(1L, selected.inputs().get(1).quantity());
    }

    private static CraftNode resolveCutting(long existingShards, int requestedShards,
                                            ItemStack tool, long toolCount) {
        return resolveCutting(existingShards, requestedShards, tool, toolCount,
                new ToolActionIngredient(ToolActions.PICKAXE_DIG), false)
                .nodes().stream().filter(node -> node.modTypeId().equals("farmersdelight_cutting_board"))
                .findFirst().orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({"false,0,0,1", "false,1,0,0", "false,1,249,1",
            "true,0,0,1", "true,1,0,0", "true,1,249,1"})
    void plansSixtyFourOperationsUsingOnlyTheNeededPickaxes(
            boolean terminal, long stock, int damage, long crafted) {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(damage);
        ItemStack wornAlternative = new ItemStack(Items.WOODEN_PICKAXE);
        wornAlternative.setDamageValue(wornAlternative.getMaxDamage() - 1);
        Ingredient supportedTools = Ingredient.of(wornAlternative, new ItemStack(Items.IRON_PICKAXE));

        CraftPlanGraph graph = resolveCutting(0, 256, pickaxe, stock, supportedTools, terminal);

        assertEquals(crafted, graph.nodes().stream()
                .filter(node -> node.recipeId().equals(new ResourceLocation("test", "iron_pickaxe")))
                .mapToLong(CraftNode::executions).sum());
        if (terminal) {
            var root = graph.rootDemands().get(0);
            assertEquals(DemandRole.DURABILITY, root.role());
            assertEquals(stock + crafted, root.quantity());
            assertEquals(0, root.unresolvedQuantity());
            assertTrue(root.allocations().stream()
                    .allMatch(allocation -> allocation.material().item() == Items.IRON_PICKAXE));
            assertEquals(stock, root.allocations().stream()
                    .filter(allocation -> allocation.source() instanceof MaterialSource.InitialPool)
                    .mapToLong(allocation -> allocation.quantity()).sum());
            return;
        }
        long plannedTools = graph.allocations().stream()
                .filter(allocation -> allocation.material().item() == Items.IRON_PICKAXE)
                .mapToLong(allocation -> allocation.quantity()).sum();
        assertEquals(stock + crafted, plannedTools);
        assertEquals(stock, graph.allocations().stream()
                .filter(allocation -> allocation.material().item() == Items.IRON_PICKAXE
                        && allocation.source() instanceof MaterialSource.InitialPool)
                .mapToLong(allocation -> allocation.quantity()).sum());
    }

    private static CraftPlanGraph resolveCutting(long existingShards, int requestedShards,
                                                 ItemStack tool, long toolCount,
                                                 Ingredient tools, boolean terminal) {
        CuttingBoardRecipe cutting = new CuttingBoardRecipe(
                new ResourceLocation("farmersdelight", "cutting/amethyst_block"), "",
                Ingredient.of(Items.AMETHYST_BLOCK), tools,
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.AMETHYST_SHARD, 4), 1.0F)), "");
        var pickaxeRecipe = new ShapelessRecipe(new ResourceLocation("test", "iron_pickaxe"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.IRON_PICKAXE),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ServerLevel level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.serverLevel()).thenReturn(level);
        ModType cuttingType = mock(ModType.class);
        when(cuttingType.id()).thenReturn("farmersdelight_cutting_board");
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();
        List<String> missing = new ArrayList<>();
        try (MockedStatic<ModRecipeHandlers> handlers =
                     mockStatic(ModRecipeHandlers.class, CALLS_REAL_METHODS);
             MockedStatic<RecipeIndex> index = mockStatic(RecipeIndex.class);
             MockedStatic<AltarBindingRegistry> bindings = mockStatic(AltarBindingRegistry.class)) {
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(player, cutting, cuttingType))
                    .thenReturn(true);
            handlers.when(() -> ModRecipeHandlers.claimFor(cutting))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.AMETHYST_SHARD, List.of(new RecipeIndex.Entry(
                            cutting, cuttingType,
                            new ResourceLocation("farmersdelight", "cutting"))),
                    Items.IRON_PICKAXE, List.of(new RecipeIndex.Entry(
                            pickaxeRecipe, ModType.GENERIC, new ResourceLocation("minecraft", "crafting")))));
            var available = Map.of(CraftingResolver.StackKey.of(tool, true), toolCount,
                    new CraftingResolver.StackKey(Items.AMETHYST_BLOCK, null), 1000L,
                    new CraftingResolver.StackKey(Items.AMETHYST_SHARD, null), existingShards,
                    new CraftingResolver.StackKey(Items.IRON_INGOT, null), 1000L);
            CraftPlanGraph graph = terminal ? CraftingResolver.resolveMachineGraphForScaledSpecsWithTypes(
                    List.of(new ScaledIngredientSpec(tools, 64L, InputConsumption.durability(1))),
                    available, level, player, null, missing, Map.of(), false, 10_000,
                    new CraftingResolver.ActiveRootRecipe(cutting.getId(),
                            new ItemStack(Items.AMETHYST_SHARD, 4), 64), Map.of())
                    : CraftingResolver.resolveGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(Ingredient.of(Items.AMETHYST_SHARD), requestedShards)),
                    available,
                    level, player, null, missing,
                    Map.of(new ResourceLocation("minecraft", "amethyst_shard"), cutting.getId()),
                    false, 10_000);

            assertTrue(missing.isEmpty(), missing.toString());
            assertTrue(graph.unresolvedDemands().isEmpty(), graph.toString());
            return graph;
        }
    }
}
