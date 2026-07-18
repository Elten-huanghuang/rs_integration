package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.youkaishomecoming.ferment.FermentationRecipeOutputs;
import dev.xkmc.youkaishomecoming.content.block.food.PotFoodBlock;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class YoukaisHomecomingRecipeHandler implements ModRecipeHandler {

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
        // Cooking-pot soup recipes produce a pot_of_X BlockItem (PotFoodBlock).
        // In-world it's served into bowls, so the true product is food*serve.
        // Report that here so RS indexing / plan preview match what the
        // delegate actually delivers (see CookingPotBatchDelegate.collectResult).
        if (isPotCookingRecipe(recipe)) {
            ItemStack bowls = potFoodServings(recipe, access);
            if (!bowls.isEmpty()) return bowls;
        }
        // SteamingRecipe overrides the deprecated no-arg getResultItem() (SRG
        // m_8042_) to return the steamer rack ICON — must never use that. But it
        // does NOT override getResultItem(RegistryAccess) (SRG m_8043_), inherited
        // from AbstractCookingRecipe, which returns the real result field.
        // Call it directly (compile-bound → ForgeGradle remaps to m_8043_); do NOT
        // reflect the "result" field by name — that deobf name is SRG-remapped to
        // f_43751_ in production and the lookup fails, yielding EMPTY (the "无法获取"
        // / unsupported-machine bug for youkaishomecoming:*_stewed_maggot etc.).
        if (STEAMING_RECIPE.equals(recipe.getClass().getName())
                && recipe instanceof AbstractCookingRecipe cooking) {
            ItemStack out = cooking.getResultItem(access);
            return out == null ? ItemStack.EMPTY : out.copy();
        }
        // SimpleFermentationRecipe.getResultItem() returns EMPTY. Report the
        // machine's real per-execution production so resolver batch counts match
        // what the fermentation tank actually emits.
        if (FERMENT_RECIPE.equals(recipe.getClass().getName())) {
            int inputs = FermentationRecipeOutputs.effectiveIngredientCount(recipe);
            ItemStack output = FermentationRecipeOutputs.fromRecipe(recipe, inputs).primary();
            RSIntegrationMod.LOGGER.debug("[RSI-YHK] Ferment production: recipe={} simple={} inputs={} output={}x{}",
                    recipe.getId(), FermentationRecipeOutputs.isSimple(recipe), inputs,
                    output.isEmpty() ? "EMPTY" : output.getHoverName().getString(), output.getCount());
            return output;
        }
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nonnull
    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        if (!FERMENT_RECIPE.equals(recipe.getClass().getName())) return List.of();
        int inputs = FermentationRecipeOutputs.effectiveIngredientCount(recipe);
        return FermentationRecipeOutputs.fromRecipe(recipe, inputs).secondary();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String cn = recipe.getClass().getName();
        if (cn.equals(FERMENT_RECIPE)) {
            try {
                java.lang.reflect.Field f = findFieldUp(recipe.getClass(), "ingredients");
                if (f == null) return null;
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
                RSIntegrationMod.LOGGER.warn("[RSI-YHK] Ferment recipe ingredients reflection failed", e);
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
                    // Append serve-bowls so the plan preview shows them and the
                    // resolver auto-crafts them if missing (matches the delegate's
                    // required materials).
                    ItemStack bowls = serveBowls(recipe);
                    if (!bowls.isEmpty()) {
                        specs.add(new IngredientSpec(Ingredient.of(bowls), bowls.getCount()));
                    }
                    return specs.isEmpty() ? null : specs;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-YHK] Cooking recipe input reflection failed", e);
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

    @Nullable
    private static java.lang.reflect.Field findFieldUp(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                return scan.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                scan = scan.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isPotCookingRecipe(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith(
                "dev.xkmc.youkaishomecoming.content.pot.cooking");
    }

    // PotFoodBlock helpers use the public YHK API so recipe planning and runtime
    // serving share the same result/count contract.

    /** The servings a pot-cooking recipe delivers ({@code food * serve}), or
     *  EMPTY if the result is not a PotFoodBlock (plain-item recipe). */
    private static ItemStack potFoodServings(Recipe<?> recipe, RegistryAccess access) {
        ItemStack result = recipe.getResultItem(access);
        if (result == null || result.isEmpty()
                || !(result.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                || !(blockItem.getBlock() instanceof PotFoodBlock potFood)) {
            return ItemStack.EMPTY;
        }
        ItemStack servings = potFood.asBowls();
        return servings.isEmpty() ? ItemStack.EMPTY : servings.copy();
    }

    /** The bowls a pot-cooking recipe needs to serve its result
     *  ({@code food.getCraftingRemainingItem() * serve}), or EMPTY. */
    private static ItemStack serveBowls(Recipe<?> recipe) {
        ItemStack servings = potFoodServings(recipe, RegistryAccess.EMPTY);
        if (servings.isEmpty()) return ItemStack.EMPTY;
        ItemStack unit = servings.copyWithCount(1);
        if (!unit.hasCraftingRemainingItem()) return ItemStack.EMPTY;
        net.minecraft.world.item.Item bowl = unit.getCraftingRemainingItem().getItem();
        if (bowl == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(bowl, servings.getCount());
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
                RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] VTB collect failed", e);
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
                    RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] FIXED collect failed", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-RecipeHandler] base lookup failed", e);
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
            RSIntegrationMod.LOGGER.warn("[RSI-YHK] Cuisine custom ingredients reflection failed", e);
        }

        return all;
    }
}
