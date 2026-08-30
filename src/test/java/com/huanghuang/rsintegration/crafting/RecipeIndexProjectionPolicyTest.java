package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIndexProjectionPolicyTest extends BootstrapTest {
    @Test
    void onlyDeterministicGraphSafeTypesEnterTypedPureGraph() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "typed_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModRecipeHandler deterministic = handler(true);
        ModRecipeHandler probabilistic = handler(false);

        assertTrue(RecipeIndex.isTypedPureProjectionCandidate(deterministic,
                ModType.FARMINGFORBLOCKHEADS_MARKET, recipe));
        assertFalse(RecipeIndex.isTypedPureProjectionCandidate(probabilistic,
                ModType.FARMINGFORBLOCKHEADS_MARKET, recipe));
        assertFalse(RecipeIndex.isTypedPureProjectionCandidate(deterministic,
                ModType.CUSTOM_GUI, recipe));
    }

    private static ModRecipeHandler handler(boolean deterministic) {
        return new ModRecipeHandler() {
            @Override public ModType modType() {
                return ModType.FARMINGFORBLOCKHEADS_MARKET;
            }

            @Override public boolean canHandle(Recipe<?> recipe) {
                return true;
            }

            @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
                return new ItemStack(Items.DIAMOND);
            }

            @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
                return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }

            @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
                return deterministic;
            }
        };
    }
}
