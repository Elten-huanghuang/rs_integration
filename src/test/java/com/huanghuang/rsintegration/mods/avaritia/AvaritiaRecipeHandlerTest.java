package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvaritiaRecipeHandlerTest extends BootstrapTest {

    @Test
    void extremeSmithingAlternativesBecomeThreePositionalInputs() {
        Ingredient additions = Ingredient.of(
                Items.GOLDEN_CARROT, Items.DRAGON_EGG, Items.NETHERITE_INGOT);

        List<IngredientSpec> specs = AvaritiaRecipeHandler.getSmithingAdditionSpecs(additions);

        assertEquals(3, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.GOLDEN_CARROT)));
        assertFalse(specs.get(0).ingredient().test(new ItemStack(Items.DRAGON_EGG)));
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.DRAGON_EGG)));
        assertFalse(specs.get(1).ingredient().test(new ItemStack(Items.NETHERITE_INGOT)));
        assertTrue(specs.get(2).ingredient().test(new ItemStack(Items.NETHERITE_INGOT)));
        assertFalse(specs.get(2).ingredient().test(new ItemStack(Items.GOLDEN_CARROT)));
    }

    @Test
    void nonPositionalAdditionRemainsValidForEverySlot() {
        Ingredient additions = Ingredient.of(Items.DRAGON_EGG);

        List<IngredientSpec> specs = AvaritiaRecipeHandler.getSmithingAdditionSpecs(additions);

        assertEquals(3, specs.size());
        assertTrue(specs.stream().allMatch(
                spec -> spec.ingredient().test(new ItemStack(Items.DRAGON_EGG))));
    }

    @Test
    void shapedTableIngredientsPreservePhysicalEmptySlots() {
        List<Ingredient> ingredients = new ArrayList<>(
                Collections.nCopies(9 * 9, Ingredient.EMPTY));
        ingredients.set(0, Ingredient.of(Items.IRON_BLOCK));
        ingredients.set(80, Ingredient.of(Items.REDSTONE_BLOCK));

        List<IngredientSpec> specs = AvaritiaRecipeHandler.tableIngredients(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe",
                ingredients);

        assertEquals(81, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.IRON_BLOCK)));
        assertTrue(specs.get(1).isEmpty());
        assertTrue(specs.get(80).ingredient().test(new ItemStack(Items.REDSTONE_BLOCK)));
    }

    @Test
    void shapelessTableIngredientsStillOmitEmptySlots() {
        List<Ingredient> ingredients = List.of(
                Ingredient.of(Items.IRON_BLOCK),
                Ingredient.EMPTY,
                Ingredient.of(Items.REDSTONE_BLOCK));

        List<IngredientSpec> specs = AvaritiaRecipeHandler.tableIngredients(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessTableCraftingRecipe",
                ingredients);

        assertEquals(2, specs.size());
    }
}
