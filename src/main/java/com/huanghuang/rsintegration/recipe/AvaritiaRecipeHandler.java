package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class AvaritiaRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_PKG = "committee.nova.mods.avaritia.common.crafting.recipe.";

    @Override
    public ModType modType() { return ModType.byId("avaritia_crafting"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith(RECIPE_PKG);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String name = recipe.getClass().getName();
        if (name.endsWith("ExtremeSmithingRecipe")) {
            return getSmithingIngredients(recipe);
        }
        // ShapedTableCraftingRecipe, ShapelessTableCraftingRecipe, CompressorRecipe
        // all implement vanilla getIngredients()
        List<Ingredient> list = recipe.getIngredients();
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : list) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    private List<IngredientSpec> getSmithingIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        try {
            Ingredient template = (Ingredient) field(recipe, "template");
            Ingredient base = (Ingredient) field(recipe, "base");
            Ingredient additions = (Ingredient) field(recipe, "additions");
            if (template != null && !template.isEmpty()) specs.add(new IngredientSpec(template, 1));
            if (base != null && !base.isEmpty()) specs.add(new IngredientSpec(base, 1));
            if (additions != null && !additions.isEmpty()) {
                specs.add(new IngredientSpec(additions, 1));
                specs.add(new IngredientSpec(additions, 1));
                specs.add(new IngredientSpec(additions, 1));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Avaritia] Failed to reflect smithing ingredients: {}", e.toString());
        }
        return specs.isEmpty() ? null : specs;
    }

    private static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
