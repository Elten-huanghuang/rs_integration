package com.huanghuang.rsintegration.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/** Shared output contract for campfire recipes executed by campfires or skillets. */
public final class CampfireRecipeSupport {

    private CampfireRecipeSupport() {}

    /** Resolve the declared output, with assembly as a compatibility fallback. */
    public static ItemStack resolveOutput(Recipe<?> recipe, RegistryAccess access) {
        if (!(recipe instanceof CampfireCookingRecipe campfire)) return ItemStack.EMPTY;

        ItemStack declared = campfire.getResultItem(access);
        if (declared != null && !declared.isEmpty()) return declared.copy();

        if (campfire.getIngredients().isEmpty()) return ItemStack.EMPTY;
        Ingredient ingredient = campfire.getIngredients().get(0);
        ItemStack[] candidates = ingredient.getItems();
        if (candidates.length == 0 || candidates[0].isEmpty()) return ItemStack.EMPTY;

        ItemStack assembled = campfire.assemble(
                new SimpleContainer(candidates[0].copyWithCount(1)), access);
        return assembled == null || assembled.isEmpty() ? ItemStack.EMPTY : assembled.copy();
    }
}
