package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Recipe handler for FarmingForBlockheads Market exchange entries.
 *
 * <p>Market trades are simple 1-cost-item → 1-output-item exchanges.
 * This handler extracts the cost item as a single {@link IngredientSpec}
 * so the resolver can compute recursive material requirements.</p>
 */
public final class MarketRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("farmingforblockheads"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe instanceof MarketRecipeWrapper;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ((MarketRecipeWrapper) recipe).getResultItem(access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        MarketRecipeWrapper w = (MarketRecipeWrapper) recipe;
        ItemStack cost = w.costItem();
        if (cost.isEmpty()) return null;
        return List.of(new IngredientSpec(net.minecraft.world.item.crafting.Ingredient.of(cost), cost.getCount()));
    }
}
