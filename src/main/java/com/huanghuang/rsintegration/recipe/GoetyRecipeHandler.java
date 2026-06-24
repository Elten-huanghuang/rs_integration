package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class GoetyRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.GOETY; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("com.Polarice3.Goety.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // Goety RitualRecipe has a getIngredientsList() method returning List<Ingredient>
        var listOpt = Reflect.invoke(recipe, "getIngredientsList");
        if (listOpt.isPresent() && listOpt.get() instanceof List<?> l && !l.isEmpty()) {
            List<IngredientSpec> result = new ArrayList<>();
            for (Object obj : l) {
                if (obj instanceof Ingredient ing && !ing.isEmpty()) {
                    result.add(new IngredientSpec(ing, 1));
                }
            }
            if (!result.isEmpty()) return result;
        }
        return null;
    }
}
