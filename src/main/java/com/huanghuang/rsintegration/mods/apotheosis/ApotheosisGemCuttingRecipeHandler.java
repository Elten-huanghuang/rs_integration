package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nonnull;
import java.util.List;

public final class ApotheosisGemCuttingRecipeHandler implements ModRecipeHandler {
    @Override public @Nonnull ModType modType() { return ModType.byId(ApotheosisRSModule.GEM_CUTTING_TYPE); }
    @Override public boolean canHandle(@Nonnull Recipe<?> recipe) { return recipe instanceof ApotheosisGemCuttingRecipe; }
    @Override public @Nonnull ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        ApotheosisGemCuttingRecipe cutting = (ApotheosisGemCuttingRecipe) recipe;
        ItemStack gem = cutting.inputGem();
        return List.of(
                new IngredientSpec(StrictNBTIngredient.of(gem), 2),
                new IngredientSpec(Ingredient.of(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new net.minecraft.resources.ResourceLocation("apotheosis", "gem_dust"))), cutting.dustCost()),
                new IngredientSpec(Ingredient.of(cutting.material()), cutting.material().getCount()));
    }
}
