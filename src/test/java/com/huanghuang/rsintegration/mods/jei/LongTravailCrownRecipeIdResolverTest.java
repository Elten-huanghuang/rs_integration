package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.recipe.viewer.LongTravailCrownRecipes;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTravailCrownRecipeIdResolverTest extends BootstrapTest {

    @Test
    void followsTheFlowerCurrentlyShownByJei() {
        CraftingRecipe display = mock(CraftingRecipe.class);
        when(display.getId()).thenReturn(new ResourceLocation(
                "the_long_travail", "jei/spring_game/1048575"));
        when(display.getIngredients()).thenReturn(NonNullList.of(Ingredient.EMPTY,
                Ingredient.of(Items.WHEAT), Ingredient.of(Items.WHEAT),
                Ingredient.of(Items.WHEAT), Ingredient.of(Items.STRING),
                Ingredient.of(Items.POPPY, Items.DANDELION),
                Ingredient.of(Items.STRING), Ingredient.of(Items.WHEAT),
                Ingredient.EMPTY, Ingredient.of(Items.WHEAT)));

        IRecipeLayoutDrawable<?> layout = mock(IRecipeLayoutDrawable.class);
        IRecipeSlotsView slots = mock(IRecipeSlotsView.class);
        IRecipeSlotView flowerSlot = mock(IRecipeSlotView.class);
        when(layout.getRecipeSlotsView()).thenReturn(slots);
        when(slots.getSlotViews(RecipeIngredientRole.INPUT)).thenReturn(List.of(flowerSlot));

        when(flowerSlot.getDisplayedItemStack()).thenReturn(Optional.of(new ItemStack(Items.POPPY)));
        assertEquals(new ResourceLocation("the_long_travail", "spring_game/poppy"),
                LongTravailCrownRecipeIdResolver.resolve(display, layout));

        when(flowerSlot.getDisplayedItemStack()).thenReturn(Optional.of(new ItemStack(Items.DANDELION)));
        assertEquals(new ResourceLocation("the_long_travail", "spring_game/dandelion"),
                LongTravailCrownRecipeIdResolver.resolve(display, layout));

        when(flowerSlot.getDisplayedItemStack()).thenReturn(Optional.of(new ItemStack(Items.STRING)));
        assertNull(LongTravailCrownRecipeIdResolver.resolve(display, layout));
    }

    @Test
    void resolvesTheRenamedSpringCornerRecipes() {
        CraftingRecipe display = mock(CraftingRecipe.class);
        when(display.getId()).thenReturn(new ResourceLocation(
                "the_long_travail", "jei/spring_corner/33554431"));
        when(display.getIngredients()).thenReturn(NonNullList.of(Ingredient.EMPTY,
                Ingredient.of(Items.WHEAT), Ingredient.of(Items.WHEAT),
                Ingredient.of(Items.WHEAT), Ingredient.of(Items.STRING),
                Ingredient.of(Items.POPPY, Items.DANDELION),
                Ingredient.of(Items.STRING), Ingredient.of(Items.WHEAT),
                Ingredient.EMPTY, Ingredient.of(Items.WHEAT)));

        IRecipeLayoutDrawable<?> layout = mock(IRecipeLayoutDrawable.class);
        IRecipeSlotsView slots = mock(IRecipeSlotsView.class);
        IRecipeSlotView flowerSlot = mock(IRecipeSlotView.class);
        when(layout.getRecipeSlotsView()).thenReturn(slots);
        when(slots.getSlotViews(RecipeIngredientRole.INPUT)).thenReturn(List.of(flowerSlot));
        when(flowerSlot.getDisplayedItemStack()).thenReturn(Optional.of(new ItemStack(Items.DANDELION)));

        assertEquals(new ResourceLocation("the_long_travail", "spring_corner/dandelion"),
                LongTravailCrownRecipeIdResolver.resolve(display, layout));
    }

    @Test
    void treeSwitchUsesTheNewCrownVariantNbt() {
        ItemStack previous = new ItemStack(Items.DIAMOND);
        previous.getOrCreateTag().putString("flower", "poppy");
        ItemStack selected = new ItemStack(Items.DIAMOND);
        selected.getOrCreateTag().putString("flower", "dandelion");

        ItemStack target = LongTravailCrownRecipes.targetForSelectedRecipe(
                new ResourceLocation("the_long_travail", "spring_game/dandelion"),
                selected, previous);

        assertEquals("dandelion", target.getTag().getString("flower"));
        selected.getOrCreateTag().putString("flower", "changed");
        assertEquals("dandelion", target.getTag().getString("flower"));
    }

    @Test
    void onlyOtherCrownRecipesMayCrossOutputNbtAtTheRoot() {
        ResourceLocation poppyId = new ResourceLocation(
                "the_long_travail", "spring_game/poppy");
        ResourceLocation dandelionId = new ResourceLocation(
                "the_long_travail", "spring_game/dandelion");
        ItemStack poppy = new ItemStack(Items.DIAMOND);
        poppy.getOrCreateTag().putString("flower", "poppy");
        ItemStack dandelion = new ItemStack(Items.DIAMOND);
        dandelion.getOrCreateTag().putString("flower", "dandelion");

        assertTrue(LongTravailCrownRecipes.isVariantAlternative(
                poppyId, dandelionId, poppy, dandelion));
        assertFalse(LongTravailCrownRecipes.isVariantAlternative(
                poppyId, poppyId, poppy, dandelion));
        assertFalse(LongTravailCrownRecipes.isVariantAlternative(
                poppyId, new ResourceLocation("other", "spring_game/dandelion"),
                poppy, dandelion));
        assertFalse(LongTravailCrownRecipes.isVariantAlternative(
                poppyId, dandelionId, poppy, new ItemStack(Items.EMERALD)));
    }
}
