package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CachedCraftingOutputTest extends BootstrapTest {

    @Test
    void cachedExecutionUsesSpecializedResultForRecipeWithoutStaticOutput() {
        ResourceLocation recipeId = new ResourceLocation("rsi_test", "cached_dynamic_result");
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getId()).thenReturn(recipeId);
        when(recipe.getResultItem(any())).thenReturn(ItemStack.EMPTY);

        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.canHandle(recipe)).thenReturn(true);
        when(handler.getResultItem(recipe, RegistryAccess.EMPTY))
                .thenReturn(new ItemStack(Items.DIAMOND));
        ModRecipeHandlers.register(handler);

        ItemStack result = GenericCraftPacket.resolveCachedRecipeOutput(
                recipe, RegistryAccess.EMPTY, null);

        assertEquals(Items.DIAMOND, result.getItem());
    }
}
