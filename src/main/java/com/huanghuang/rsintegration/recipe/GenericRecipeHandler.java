package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

final class GenericRecipeHandler extends AbstractRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("generic"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof CraftingRecipe cr)) return null;
        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(cr);
        return specs.stream().anyMatch(spec -> !spec.isEmpty()) ? specs : null;
    }
}
