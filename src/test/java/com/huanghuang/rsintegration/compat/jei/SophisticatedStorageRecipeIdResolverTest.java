package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedstorage.compat.recipeviewers.common.TierUpgradeDisplayRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SophisticatedStorageRecipeIdResolverTest {
    @Test
    void resolvesGroupedDisplayToWrappedServerRecipeId() {
        ResourceLocation realId = new ResourceLocation("sophisticatedstorage", "iron_chest");

        assertEquals(realId, SophisticatedStorageRecipeIdResolver.resolve(
                new TierUpgradeDisplayRecipe(new StubRecipe(realId))));
    }

    @Test
    void ignoresOrdinaryRecipesAndMalformedGroupedDisplays() {
        StubRecipe recipe = new StubRecipe(new ResourceLocation("minecraft", "chest"));

        assertNull(SophisticatedStorageRecipeIdResolver.resolve(recipe));
        assertNull(SophisticatedStorageRecipeIdResolver.resolve(new TierUpgradeDisplayRecipe("not a recipe")));
    }

    private record StubRecipe(ResourceLocation id) implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, net.minecraft.core.RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }
}
