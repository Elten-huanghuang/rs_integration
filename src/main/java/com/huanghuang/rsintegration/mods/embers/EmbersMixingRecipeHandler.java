package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.IMixingRecipe;
import com.rekindled.embers.recipe.MixingRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public final class EmbersMixingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_MIXER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IMixingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.contains(ModIds.ID_EMBERS_MIXER);
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return InkFluidSupport.token(((IMixingRecipe) recipe).getDisplayOutput());
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<FluidIngredient> inputs = ((IMixingRecipe) recipe).getDisplayInputFluids();
        if (inputs.isEmpty() || inputs.size() > 4) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (FluidIngredient input : inputs) {
            IngredientSpec spec = EmbersFluidIngredients.spec(input);
            if (spec == null) return null;
            specs.add(spec);
        }
        return List.copyOf(specs);
    }
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe.getClass() == MixingRecipe.class
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }
    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
