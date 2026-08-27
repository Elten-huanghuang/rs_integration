package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmersDelightRecipeHandlerTest extends BootstrapTest {

    @Test
    void cookingPotContainerIsAPlannedMaterialAfterOrdinaryInputs() {
        List<IngredientSpec> inputs = List.of(
                new IngredientSpec(Ingredient.of(Items.CARROT), 1));

        List<IngredientSpec> specs = FarmersDelightRecipeHandler.appendOutputContainerSpec(
                inputs, new ItemStack(Items.BOWL));

        assertEquals(2, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.CARROT)));
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.BOWL)));
        assertEquals(1, specs.get(1).count());
    }

    @Test
    void recipeWithoutContainerKeepsOnlyOrdinaryInputs() {
        List<IngredientSpec> inputs = List.of(
                new IngredientSpec(Ingredient.of(Items.CARROT), 1));

        assertEquals(inputs, FarmersDelightRecipeHandler.appendOutputContainerSpec(
                inputs, ItemStack.EMPTY));
    }

    @Test
    void multiServingCookingRecipePlansOneContainerPerResult() {
        ItemStack container = FarmersDelightRecipeHandler.withResultCount(
                new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.HONEY_BOTTLE, 2));
        List<IngredientSpec> specs = FarmersDelightRecipeHandler.appendOutputContainerSpec(
                List.of(new IngredientSpec(Ingredient.of(Items.SUGAR), 1)), container);

        assertEquals(2, container.getCount());
        assertEquals(2, specs.get(1).count());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.GLASS_BOTTLE)));
    }

    @Test
    void cuttingBoardTreatsInputAsConsumedAndToolAsReusableCatalyst() {
        List<IngredientSpec> specs = FarmersDelightRecipeHandler.cuttingBoardIngredients(
                Ingredient.of(Items.CARROT), Ingredient.of(Items.IRON_SWORD));

        assertEquals(2, specs.size());
        assertEquals(DemandRole.CONSUMED, specs.get(0).role());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.CARROT)));
        assertEquals(DemandRole.CATALYST, specs.get(1).role());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void cuttingBoardToolNeverEntersRecursiveMaterialGraph() {
        List<IngredientSpec> specs = FarmersDelightRecipeHandler.cuttingBoardIngredients(
                Ingredient.of(Items.STONE), Ingredient.of(Items.DIAMOND_PICKAXE));

        List<IngredientSpec> graphSpecs =
                FarmersDelightRecipeHandler.cuttingBoardGraphIngredients(specs);

        assertEquals(1, graphSpecs.size());
        assertEquals(DemandRole.CONSUMED, graphSpecs.get(0).role());
        assertTrue(graphSpecs.get(0).ingredient().test(new ItemStack(Items.STONE)));
        assertFalse(graphSpecs.stream().anyMatch(spec ->
                spec.ingredient().test(new ItemStack(Items.DIAMOND_PICKAXE))));
    }
}
