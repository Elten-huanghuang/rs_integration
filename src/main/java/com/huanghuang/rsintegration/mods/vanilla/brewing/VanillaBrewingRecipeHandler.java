package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

public final class VanillaBrewingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId("vanilla_brewing_stand"); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof VanillaBrewingRecipeDefinition; }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ((VanillaBrewingRecipeDefinition) recipe).output();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        VanillaBrewingRecipeDefinition brewing = (VanillaBrewingRecipeDefinition) recipe;
        return List.of(new IngredientSpec(recipe.getIngredients().get(0), 3),
                new IngredientSpec(recipe.getIngredients().get(1), 1),
                new IngredientSpec(recipe.getIngredients().get(2), 1));
    }
}
