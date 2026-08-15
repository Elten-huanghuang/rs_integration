package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Logical execution of deterministic Hot Spring recipes using a disk-held fluid bucket. */
public final class ImmortalersDelightHotSpringRecipeHandler extends AbstractRecipeHandler {

    public static final String RECIPE_CLASS =
            "com.renyigesai.immortalers_delight.recipe.HotSpringRecipe";

    static {
        registerRecipePrefixes(ImmortalersDelightHotSpringRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ImmortalersDelightRSModule.HOT_SPRING_TYPE_ID);
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public boolean isAvailableForPlanning(Recipe<?> recipe, @Nullable ServerPlayer player) {
        return LycheeVirtualCatalysts.hasCatalyst(
                player, LycheeVirtualCatalysts.HOT_SPRING_BUCKET);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs.isEmpty() ? null : List.copyOf(specs);
    }

    static boolean isSupportedRecipeClassName(String className) {
        return RECIPE_CLASS.equals(className);
    }
}
