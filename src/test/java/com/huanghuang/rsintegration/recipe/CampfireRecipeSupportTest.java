package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CampfireRecipeSupportTest extends BootstrapTest {

    @Test
    void resolvesDeclaredCampfireOutputForEveryMachineExecutor() {
        CampfireCookingRecipe recipe = new CampfireCookingRecipe(
                new ResourceLocation("test", "cooked_food"), "",
                CookingBookCategory.FOOD, Ingredient.of(Items.BEEF),
                new ItemStack(Items.COOKED_BEEF), 0.35F, 600);

        ItemStack output = CampfireRecipeSupport.resolveOutput(recipe, RegistryAccess.EMPTY);

        assertTrue(ItemStack.isSameItemSameTags(new ItemStack(Items.COOKED_BEEF), output));
    }
}
