package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;

class BiomancyDigestingRecipeResolverTest extends BootstrapTest {
    @Test
    void recoversNativeFoodRecipeIdFromLogDisplayId() {
        assertEquals(new ResourceLocation("biomancy", "digesting/nutrient_paste_from_digesting_dynamic_food"),
                BiomancyDigestingRecipeResolver.sourceId(new ResourceLocation("biomancy",
                        "digesting/nutrient_paste_from_digesting_dynamic_food_jei_minecraft.beef")));
        assertNull(BiomancyDigestingRecipeResolver.sourceId(new ResourceLocation("biomancy", "digesting/native")));
        assertNull(BiomancyDigestingRecipeResolver.sourceId(new ResourceLocation("biomancy", "digesting/native_jei_")));
    }

    @Test
    void pinsFoodToDisplayIdInsteadOfSelectingAnotherIngredient() {
        ResourceLocation source = new ResourceLocation("biomancy", "digesting/food");
        Ingredient foods = Ingredient.of(Items.BEEF, Items.COOKED_BEEF);
        ItemStack food = BiomancyDigestingRecipeResolver.findFood(foods, source,
                new ResourceLocation("biomancy", "digesting/food_jei_minecraft.beef"));
        assertEquals(Items.BEEF, food.getItem());
        assertEquals(1, food.getCount());
        assertTrue(BiomancyDigestingRecipeResolver.findFood(foods, source,
                new ResourceLocation("biomancy", "digesting/food_jei_minecraft.diamond")).isEmpty());
        assertTrue(BiomancyDigestingRecipeResolver.findFood(foods, source,
                new ResourceLocation("other", "digesting/food_jei_minecraft.beef")).isEmpty());
    }

    @Test
    void keepsRegisteredRecipesAndRejectsUnknownSyntheticSources() {
        RecipeManager manager = mock(RecipeManager.class);
        Recipe<?> registered = mock(Recipe.class);
        ResourceLocation nativeId = new ResourceLocation("biomancy", "digesting/native");
        doReturn(Optional.of(registered)).when(manager).byKey(nativeId);
        assertSame(registered, BiomancyDigestingRecipeResolver.resolve(manager, RegistryAccess.EMPTY, nativeId));
        assertNull(BiomancyDigestingRecipeResolver.resolve(manager, RegistryAccess.EMPTY,
                new ResourceLocation("biomancy", "digesting/missing_jei_minecraft.beef")));
    }
}
