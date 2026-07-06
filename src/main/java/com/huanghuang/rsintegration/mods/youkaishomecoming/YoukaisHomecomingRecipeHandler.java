package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class YoukaisHomecomingRecipeHandler implements com.huanghuang.rsintegration.recipe.ModRecipeHandler {

    private static final String MOKA_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.moka.MokaRecipe";
    private static final String STEAMING_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.steamer.SteamingRecipe";
    private static final String BASE_POT_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.base.BasePotRecipe";
    private static final String FERMENT_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.ferment.SimpleFermentationRecipe";
    private static final String POT_COOKING_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.cooking.core.PotCookingRecipe";

    @Override
    public ModType modType() { return ModType.byId("youkaishomecoming"); }

    private static final String KETTLE_RECIPE =
            "dev.xkmc.youkaishomecoming.content.pot.kettle.KettleRecipe";

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        String cn = recipe.getClass().getName();
        // KettleRecipe produces FluidStack — no item result, RS can't handle it
        if (cn.equals(KETTLE_RECIPE)) return false;
        return cn.equals(MOKA_RECIPE) || cn.equals(STEAMING_RECIPE)
                || cn.equals(BASE_POT_RECIPE) || cn.equals(FERMENT_RECIPE)
                || cn.startsWith("dev.xkmc.youkaishomecoming.content.pot");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // SimpleFermentationRecipe.getResultItem() returns EMPTY — extract from results field
        if (FERMENT_RECIPE.equals(recipe.getClass().getName())) {
            try {
                java.lang.reflect.Field f = recipe.getClass().getDeclaredField("results");
                f.setAccessible(true);
                Object val = f.get(recipe);
                if (val instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof ItemStack s && !s.isEmpty()) return s.copy();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-YHK] Recipe result reflection failed: {}", e.toString());
            }
            return ItemStack.EMPTY;
        }
        return com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String cn = recipe.getClass().getName();
        if (cn.equals(FERMENT_RECIPE)) {
            try {
                java.lang.reflect.Field f = recipe.getClass().getDeclaredField("ingredients");
                f.setAccessible(true);
                Object val = f.get(recipe);
                if (val instanceof java.util.List<?> list) {
                    List<IngredientSpec> specs = new ArrayList<>();
                    for (Object obj : list) {
                        if (obj instanceof Ingredient ing && !ing.isEmpty())
                            specs.add(new IngredientSpec(ing, 1));
                    }
                    return specs.isEmpty() ? null : specs;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-YHK] Ferment recipe ingredients reflection failed: {}", e.toString());
            }
            return null;
        }
        if (isPotCookingRecipe(recipe)) {
            try {
                java.lang.reflect.Method getInput = recipe.getClass().getMethod("getInput");
                Object val = getInput.invoke(recipe);
                if (val instanceof java.util.List<?> list) {
                    List<IngredientSpec> specs = new ArrayList<>();
                    for (Object obj : list) {
                        if (obj instanceof Ingredient ing && !ing.isEmpty())
                            specs.add(new IngredientSpec(ing, 1));
                    }
                    return specs.isEmpty() ? null : specs;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-YHK] Cooking recipe input reflection failed: {}", e.toString());
            }
            return null;
        }
        if (isCuisineRecipe(recipe)) {
            List<Ingredient> all = collectCuisineIngredients(recipe);
            if (!all.isEmpty()) {
                List<IngredientSpec> specs = new ArrayList<>();
                for (Ingredient ing : all) {
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                }
                return specs.isEmpty() ? null : specs;
            }
            return null;
        }
        if (cn.equals(STEAMING_RECIPE) || recipe instanceof AbstractCookingRecipe) {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : ingredients) {
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            return specs.isEmpty() ? null : specs;
        }
        // MokaRecipe / BasePotRecipe: standard getIngredients()
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    private static boolean isPotCookingRecipe(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith(
                "dev.xkmc.youkaishomecoming.content.pot.cooking");
    }

    private static boolean isCuisineRecipe(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith(
                "dev.xkmc.youkaishomecoming.content.pot.table.recipe");
    }

    /** Collect ALL ingredients for a cuisine recipe, including base-model
     *  items (rice, kelp) that are part of the TableItem tree.
     *  TableItem.collectIngredients(List) is a default no-op, so we use
     *  VariantTableItemBase.collectIngredients(List,List) instead. */
    @SuppressWarnings("unchecked")
    private static List<Ingredient> collectCuisineIngredients(Recipe<?> recipe) {
        List<Ingredient> all = new ArrayList<>();

        // 1. Base-model ingredients from VariantTableItemBase.MAP
        try {
            java.lang.reflect.Method baseMethod = recipe.getClass().getMethod("base");
            ResourceLocation baseId = (ResourceLocation) baseMethod.invoke(recipe);

            try {
                Class<?> vtbClass = Class.forName(
                        "dev.xkmc.youkaishomecoming.content.pot.table.item.VariantTableItemBase");
                java.lang.reflect.Field mapField = vtbClass.getField("MAP");
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) mapField.get(null);
                Object vtb = map.get(baseId);
                if (vtb != null) {
                    List<Ingredient> baseList = new ArrayList<>();
                    List<Ingredient> extraList = new ArrayList<>();
                    java.lang.reflect.Method cm = vtbClass.getMethod(
                            "collectIngredients", List.class, List.class);
                    cm.invoke(vtb, baseList, extraList);
                    for (Ingredient ing : baseList) {
                        if (!ing.isEmpty()) all.add(ing);
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] VTB collect failed: {}",
                        e.toString());
            }

            // Fallback: IngredientTableItem.FIXED
            if (all.isEmpty()) {
                try {
                    Class<?> itiClass = Class.forName(
                            "dev.xkmc.youkaishomecoming.content.pot.table.item.IngredientTableItem");
                    java.lang.reflect.Field fixedField = itiClass.getField("FIXED");
                    java.util.Map<?, ?> fixedMap = (java.util.Map<?, ?>) fixedField.get(null);
                    Object fixed = fixedMap.get(baseId);
                    if (fixed != null) {
                        java.lang.reflect.Method cm = itiClass.getMethod(
                                "collectIngredients", List.class);
                        cm.invoke(fixed, all);
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] FIXED collect failed: {}",
                            e.toString());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] base lookup failed: {}",
                    e.toString());
        }

        // 2. Recipe-specific ingredients
        try {
            java.lang.reflect.Method gci = recipe.getClass().getMethod("getCustomIngredients");
            List<Ingredient> custom = (List<Ingredient>) gci.invoke(recipe);
            if (custom != null) {
                for (Ingredient ing : custom) {
                    if (!ing.isEmpty()) all.add(ing);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-YHK] Cuisine custom ingredients reflection failed: {}", e.toString());
        }

        return all;
    }
}
