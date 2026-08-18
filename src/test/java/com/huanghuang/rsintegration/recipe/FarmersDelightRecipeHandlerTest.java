package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
