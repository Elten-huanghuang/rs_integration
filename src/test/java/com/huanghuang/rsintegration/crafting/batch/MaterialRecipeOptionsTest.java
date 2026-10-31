package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.RecipeCandidateRules;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.mods.farmersdelight.CuttingBoardRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;
import vectorwing.farmersdelight.common.crafting.ingredient.ToolActionIngredient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MaterialRecipeOptionsTest extends BootstrapTest {
    @Test
    void stockOnlyGraphOffersAvailableProducersWithoutAddingExecutionSteps() {
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);
        ShapelessRecipe recipe = recipe("iron_ingot_from_nuggets", ingot);
        PlanGraphView graph = stockGraph(ingot);
        var index = Map.of(Items.IRON_INGOT, List.of(new RecipeIndex.Entry(
                recipe, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"))));

        PlanGraphView enriched = CraftDisplayMaterials.attachMaterialRecipeOptions(
                graph, index, RegistryAccess.EMPTY, (candidate, type) -> true);

        assertEquals(List.of(new PlanGraphView.RecipeOption(recipe.getId(), "generic")),
                enriched.recipeOptions().get(IngredientKey.of(ingot)));
        assertTrue(enriched.nodes().isEmpty());
        assertEquals(graph.roots(), enriched.roots());
        assertTrue(CraftDisplayMaterials.attachMaterialRecipeOptions(
                graph, index, RegistryAccess.EMPTY, (candidate, type) -> false).recipeOptions().isEmpty());
    }

    @Test
    void nbtDistinctOutputsCannotBeOfferedAsEachOthersProducer() {
        ItemStack wanted = new ItemStack(Items.POTION);
        wanted.getOrCreateTag().putString("Potion", "minecraft:water");
        ItemStack other = new ItemStack(Items.POTION);
        other.getOrCreateTag().putString("Potion", "minecraft:awkward");
        ShapelessRecipe recipe = recipe("other_potion", other);
        try (MockedStatic<ModList> mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            PlanGraphView enriched = CraftDisplayMaterials.attachMaterialRecipeOptions(
                    stockGraph(wanted), Map.of(Items.POTION, List.of(new RecipeIndex.Entry(
                            recipe, ModType.GENERIC, new ResourceLocation("minecraft", "crafting")))),
                    RegistryAccess.EMPTY, (candidate, type) -> true);

            assertTrue(enriched.recipeOptions().isEmpty());
        }
    }

    @Test
    void stockedAmethystKeepsRealCuttingChoiceAndToolRoleThroughDraftAndWire() {
        ItemStack shards = new ItemStack(Items.AMETHYST_SHARD);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        List<ScaledIngredientSpec> specs = List.of(
                ScaledIngredientSpec.of(new IngredientSpec(Ingredient.of(shards), 4), 4),
                ScaledIngredientSpec.of(new IngredientSpec(
                        Ingredient.of(pickaxe), 1, DemandRole.DURABILITY), 1));
        Map<StackKey, Long> stock = Map.of(new StackKey(Items.AMETHYST_SHARD, null), 20L,
                new StackKey(Items.IRON_PICKAXE, null), 1L);
        PlanGraphView graph = PlanGraphView.from(CraftIngredientPlanMath.directTerminalInputGraph(
                specs, DirectMaterialAllocator.allocateScaled(specs, stock)));
        CuttingBoardRecipe cutting = new CuttingBoardRecipe(
                new ResourceLocation("farmersdelight", "cutting/amethyst_block"), "",
                Ingredient.of(Items.AMETHYST_BLOCK), new ToolActionIngredient(ToolActions.PICKAXE_DIG),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(shards.copyWithCount(4), 1.0F)), "");
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();
        ModType type = mock(ModType.class);
        when(type.id()).thenReturn("farmersdelight_cutting_board");
        try (MockedStatic<ModRecipeHandlers> handlers =
                     mockStatic(ModRecipeHandlers.class, CALLS_REAL_METHODS)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(cutting))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            graph = CraftDisplayMaterials.attachMaterialRecipeOptions(graph,
                    Map.of(Items.AMETHYST_SHARD, List.of(new RecipeIndex.Entry(
                            cutting, type, new ResourceLocation("farmersdelight", "cutting")))),
                    RegistryAccess.EMPTY, (candidate, machineType) -> true);
        }
        PlanStep terminal = new PlanStep(new ResourceLocation("test", "target"),
                new ItemStack(Items.DIAMOND), 1, List.of(shards.copyWithCount(4), pickaxe));
        var bill = PlanMaterialBill.summarize(Map.of(), Map.of(),
                Map.of(Items.AMETHYST_SHARD, 20L, Items.IRON_PICKAXE, 1L),
                stock, terminal.output(), List.of(terminal), 1, graph, false);
        PlanResponseDraft draft = new PlanResponseDraft(true, "", terminal.output(),
                List.of(terminal), bill.materials(), List.of(), terminal.recipeId().toString(),
                null, null, 0, 0, 0, List.of(), 1, null, null, null, 0L,
                false, false, false, null, Set.of(), Map.of(), null, graph);
        PlanResponse decoded = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(draft.toResponse(), 1)).plan();
        PlanTreeModel tree = PlanTreeModel.from(decoded);
        PlanTreeNode material = tree.findByKey(IngredientKey.of(shards));
        PlanTreeNode tool = tree.findByKey(IngredientKey.of(pickaxe));

        assertTrue(bill.feasible());
        assertEquals(new PlanResponse.Availability(4, 20),
                decoded.materials().get(IngredientKey.of(shards)));
        assertNotNull(material);
        assertNull(material.step);
        assertEquals(4, material.amount);
        assertEquals("farmersdelight_cutting_board",
                RecipeCandidateRules.optionsFor(material).get(cutting.getId()));
        assertTrue(RecipeCandidateRules.canSelect(material, cutting.getId()));
        assertEquals(DemandRole.DURABILITY, tool.demandRole);
        assertTrue(decoded.graph().nodes().isEmpty());
    }

    private static ShapelessRecipe recipe(String path, ItemStack output) {
        return new ShapelessRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC, output,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_NUGGET)));
    }

    private static PlanGraphView stockGraph(ItemStack material) {
        return new PlanGraphView(1, List.of(), List.of(),
                List.of(new PlanGraphView.RootView(material, 4, 0, List.of(
                        new PlanGraphView.RootEdgeView(
                                new PlanGraphView.SourceView(true, -1, -1), material, 4)))),
                List.of(), List.of());
    }
}
