package com.huanghuang.rsintegration.compat.jei;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;

/** 将春之冠的 JEI 合并展示映射到当前花朵的服务端配方。 */
public final class LongTravailCrownRecipeIdResolver {
    private static final int FLOWER_SLOT = 4;

    private LongTravailCrownRecipeIdResolver() {}

    public static boolean isDisplay(Object recipe, @Nullable ResourceLocation id) {
        return recipe instanceof CraftingRecipe
                && LongTravailCrownRecipes.isDisplayId(id);
    }

    public static boolean isDisplayId(@Nullable ResourceLocation id) {
        return LongTravailCrownRecipes.isDisplayId(id);
    }

    @Nullable
    public static ResourceLocation resolve(CraftingRecipe display,
                                           IRecipeLayoutDrawable<?> layout) {
        if (!isDisplay(display, display.getId())
                || display.getIngredients().size() <= FLOWER_SLOT) return null;
        Ingredient flowers = display.getIngredients().get(FLOWER_SLOT);
        if (flowers.isEmpty()) return null;

        // JEI 的 focus link 会同步中央花朵与输出；在点击时读取，避免使用过期的轮播值。
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                .getSlotViews(RecipeIngredientRole.INPUT)) {
            ItemStack shown = slot.getDisplayedItemStack().orElse(ItemStack.EMPTY);
            if (shown.isEmpty() || !flowers.test(shown)) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(shown.getItem());
            ResourceLocation recipeId = LongTravailCrownRecipes.forFlowerItem(itemId);
            if (recipeId != null) return recipeId;
        }
        return null;
    }
}
