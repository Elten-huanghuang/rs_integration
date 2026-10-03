package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

public final class FluidContainerRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.FLUID_CONTAINER; }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof FluidContainerRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) { return canHandle(recipe); }
    @Override public boolean isAvailableForPlanning(Recipe<?> recipe, @Nullable ServerPlayer player) {
        return player == null || FluidContainerCatalog.isValid((FluidContainerRecipe) recipe);
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ((FluidContainerRecipe) recipe).output();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        return ((FluidContainerRecipe) recipe).specs();
    }
    @Override public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return ((FluidContainerRecipe) recipe).secondaryOutputs();
    }
}
