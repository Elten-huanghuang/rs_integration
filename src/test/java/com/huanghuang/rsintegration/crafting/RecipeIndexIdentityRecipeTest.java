package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIndexIdentityRecipeTest extends BootstrapTest {
    @Test
    void tagContainingOutputAndOtherItemsRemainsRecursive() {
        ShapedRecipe recipe = recipe("tag_to_oak", Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG),
                new ItemStack(Items.OAK_LOG, 2));
        assertFalse(RecipeIndex.isIdentityRecipe(recipe, recipe.getResultItem(null), null));
    }

    @Test
    void pureSelfInputRemainsIdentity() {
        ShapedRecipe recipe = recipe("oak_to_oak", Ingredient.of(Items.OAK_LOG),
                new ItemStack(Items.OAK_LOG));
        assertTrue(RecipeIndex.isIdentityRecipe(recipe, recipe.getResultItem(null), null));
    }

    private static ShapedRecipe recipe(String path, Ingredient input, ItemStack output) {
        return new ShapedRecipe(new ResourceLocation("test", path), "", CraftingBookCategory.MISC,
                1, 1, NonNullList.of(Ingredient.EMPTY, input), output);
    }
}
