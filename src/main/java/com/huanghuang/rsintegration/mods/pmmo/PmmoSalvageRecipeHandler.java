package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public final class PmmoSalvageRecipeHandler implements ModRecipeHandler {
    @Nonnull
    @Override
    public ModType modType() {
        return ModType.byId(PmmoRSModule.TYPE_ID);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return recipe instanceof PmmoSalvageRecipeWrapper;
    }

    @Override
    public boolean isAvailableForPlanning(@Nonnull Recipe<?> recipe,
                                          @Nullable ServerPlayer player) {
        return recipe instanceof PmmoSalvageRecipeWrapper salvage
                && player != null && PmmoSalvageRuntime.isTargetEligible(player, salvage);
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return recipe instanceof PmmoSalvageRecipeWrapper salvage
                ? salvage.getResultItem(access) : ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof PmmoSalvageRecipeWrapper salvage)) return null;
        var ingredients = salvage.getIngredients();
        return ingredients.isEmpty() ? null : List.of(new IngredientSpec(ingredients.get(0), 1));
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return false;
    }
}
