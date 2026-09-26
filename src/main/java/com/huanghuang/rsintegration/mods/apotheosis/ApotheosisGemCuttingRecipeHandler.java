package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.List;

public final class ApotheosisGemCuttingRecipeHandler implements ModRecipeHandler {
    @Override public @Nonnull ModType modType() { return ModType.byId(ApotheosisRSModule.GEM_CUTTING_TYPE); }
    @Override public boolean canHandle(@Nonnull Recipe<?> recipe) { return recipe instanceof ApotheosisGemCuttingRecipe; }
    @Override public boolean supportsBackgroundPlanning(@Nonnull Recipe<?> recipe) {
        return recipe instanceof ApotheosisGemCuttingRecipe;
    }
    @Override public @Nonnull ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        ApotheosisGemCuttingRecipe cutting = (ApotheosisGemCuttingRecipe) recipe;
        ItemStack gem = cutting.inputGem();
        return List.of(
                new IngredientSpec(PartialNBTIngredient.of(gem.getItem(), gem.getTag() == null ? new CompoundTag() : gem.getTag()), 2),
                new IngredientSpec(Ingredient.of(ForgeRegistries.ITEMS.getValue(
                        new ResourceLocation("apotheosis", "gem_dust"))), cutting.dustCost()),
                new IngredientSpec(Ingredient.of(cutting.material()), cutting.material().getCount()));
    }
}
