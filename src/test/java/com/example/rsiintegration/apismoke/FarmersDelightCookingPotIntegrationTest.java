package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmersDelightCookingPotIntegrationTest extends BootstrapTest {
    @Test
    void realCookingPotRecipeProjectsIntoPublicRecipeView() {
        CookingPotRecipe recipe = recipe();
        FarmersDelightCookingPotIntegration integration =
                new FarmersDelightCookingPotIntegration();

        RecipeInspectionResult result = integration.recipe().inspect(
                new RecipeContext(
                        new ResourceLocation("rsi_api_smoke", "test"),
                        RegistryAccess.EMPTY,
                        1L
                ),
                recipe
        );

        RecipeView view = result.view();
        assertNotNull(view);
        assertEquals(recipe.getId(), view.recipeId());
        assertEquals(3, view.materials().size());
        assertEquals(1, view.outputs().size());
        assertEquals(Items.MUSHROOM_STEW, BuiltInRegistries.ITEM.get(
                view.outputs().get(0).resource().id()));
        assertTrue(view.flags().orderedInputs());
        assertTrue(view.flags().deterministicOutput());
    }

    private static CookingPotRecipe recipe() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(Items.BEEF));
        ingredients.add(Ingredient.of(Items.CARROT));
        return new CookingPotRecipe(
                new ResourceLocation("farmersdelight", "test_stew"),
                "",
                null,
                ingredients,
                new ItemStack(Items.MUSHROOM_STEW),
                new ItemStack(Items.BOWL),
                0.35F,
                200
        );
    }
}
