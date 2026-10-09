package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.IStampingRecipe;
import com.rekindled.embers.recipe.StampingRecipe;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbersStampingRecipeHandlerTest extends BootstrapTest {
    @Test
    void stampIsOneReusableIngredientForRecursiveCrafting() {
        IStampingRecipe recipe = mock(IStampingRecipe.class);
        when(recipe.getDisplayInput()).thenReturn(Ingredient.of(Items.IRON_INGOT));
        when(recipe.getDisplayInputFluid()).thenReturn(FluidIngredient.EMPTY);
        when(recipe.getDisplayStamp()).thenReturn(Ingredient.of(Items.GOLD_NUGGET));
        EmbersStampingRecipeHandler handler = new EmbersStampingRecipeHandler();

        List<IngredientSpec> ingredients = handler.getIngredients(recipe);

        assertEquals(2, ingredients.size());
        assertEquals(DemandRole.CATALYST, ingredients.get(1).role());
        assertEquals(1, handler.requiredIngredientCount(recipe, ingredients.get(1), 1, 8));
    }

    @Test
    void nativeStampingRecipeCanEnterBackgroundGraph() {
        StampingRecipe recipe = new StampingRecipe(
                new ResourceLocation("embers", "stamping/copper_aspectus"),
                Ingredient.of(Items.GOLD_NUGGET), Ingredient.of(Items.COPPER_INGOT),
                FluidIngredient.EMPTY, new ItemStack(Items.DIAMOND));
        EmbersStampingRecipeHandler handler = new EmbersStampingRecipeHandler();

        assertTrue(handler.supportsBackgroundPlanning(recipe));
        assertNotNull(ImmutableRecipeGraphProjector.projectRecipe(recipe.id,
                new ItemStack(Items.DIAMOND), handler.getIngredients(recipe),
                "embers_stamper", new ResourceLocation("embers", "stamping")));
    }
}
