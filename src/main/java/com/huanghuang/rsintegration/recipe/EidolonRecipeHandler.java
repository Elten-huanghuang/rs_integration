package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class EidolonRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(EidolonRecipeHandler.class, "elucent.eidolon.");
    }

    @Override
    public ModType modType() { return ModType.byId("eidolon"); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // RitualRecipe (ItemRitualRecipe) has getResultItem() returning the actual result
        return recipe.getResultItem(access);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // CrucibleRecipe: getSteps() → Step.matches
        var stepsOpt = Reflect.invoke(recipe, "getSteps");
        if (stepsOpt.isPresent()) {
            List<?> steps = (List<?>) stepsOpt.get();
            if (steps != null && !steps.isEmpty()) {
                List<IngredientSpec> result = new ArrayList<>();
                for (Object step : steps) {
                    try {
                        Field matchesField = step.getClass().getField("matches");
                        List<Ingredient> matches = (List<Ingredient>) matchesField.get(step);
                        if (matches != null) {
                            for (Ingredient ing : matches) {
                                if (!ing.isEmpty()) result.add(new IngredientSpec(ing, 1));
                            }
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
                if (!result.isEmpty()) return result;
                // Fall through — step matches may have been empty or unreadable
            }
        }

        // RitualRecipe: reagent, pedestal items and focus items are all consumed by
        // Eidolon as ritual requirements. Invariant items deliberately remain out of
        // the network plan because they are persistent structure requirements.
        try {
            Class<?> ritualClass = Class.forName("elucent.eidolon.recipe.RitualRecipe");
            if (ritualClass.isInstance(recipe)) {
                try {
                    Ingredient reagent = (Ingredient) ritualClass.getField("reagent").get(recipe);
                    List<Ingredient> pedestalItems = readRitualItems(ritualClass, recipe, "pedestalItems");
                    List<Ingredient> focusItems = readRitualItems(ritualClass, recipe, "focusItems");
                    return ritualNetworkIngredients(reagent, pedestalItems, focusItems);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                }
                return null;
            }
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }

        // WorktableRecipe: Ingredient[] core + Ingredient[] extras
        // (getIngredients/m_7527_ is not reliably accessible in SRG)
        List<IngredientSpec> result = readWorktableArrays(recipe);
        if (result != null) return result;

        // Other Eidolon recipe types: use low-level extractIngredients
        // (NOT extractIngredientSpecs — it dispatches back to handlers → infinite loop)
        List<Ingredient> raw = CraftPacketUtils.extractIngredients(recipe);
        if (raw == null || raw.isEmpty()) return null;
        result = new ArrayList<>();
        for (Ingredient ing : raw) {
            if (!ing.isEmpty()) result.add(new IngredientSpec(ing, 1));
        }
        return result.isEmpty() ? null : result;
    }

    static List<IngredientSpec> ritualNetworkIngredients(Ingredient reagent,
                                                           List<Ingredient> pedestalItems,
                                                           List<Ingredient> focusItems) {
        List<IngredientSpec> result = new ArrayList<>();
        addRitualIngredient(result, reagent);
        addRitualIngredients(result, pedestalItems);
        addRitualIngredients(result, focusItems);
        return result.isEmpty() ? null : result;
    }

    @SuppressWarnings("unchecked")
    private static List<Ingredient> readRitualItems(Class<?> ritualClass, Recipe<?> recipe,
                                                     String fieldName) throws IllegalAccessException, NoSuchFieldException {
        Object value = ritualClass.getField(fieldName).get(recipe);
        return value instanceof List<?> items ? (List<Ingredient>) items : List.of();
    }

    private static void addRitualIngredients(List<IngredientSpec> result, List<Ingredient> ingredients) {
        if (ingredients == null) return;
        for (Ingredient ingredient : ingredients) addRitualIngredient(result, ingredient);
    }

    private static void addRitualIngredient(List<IngredientSpec> result, Ingredient ingredient) {
        if (ingredient != null && !ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
    }

    private static List<IngredientSpec> readWorktableArrays(Recipe<?> recipe) {
        // Try getCore() / getOuter() methods first
        var coreOpt = Reflect.invoke(recipe, "getCore");
        var outerOpt = Reflect.invoke(recipe, "getOuter");
        if (coreOpt.isEmpty() && outerOpt.isEmpty()) {
            // Fallback: read core / extras fields directly
            coreOpt = Reflect.getField(recipe, "core");
            outerOpt = Reflect.getField(recipe, "extras");
        }
        if (coreOpt.isEmpty() && outerOpt.isEmpty()) return null;

        List<IngredientSpec> specs = new ArrayList<>();
        addArraySpecs(coreOpt, specs);
        addArraySpecs(outerOpt, specs);
        return specs.isEmpty() ? null : specs;
    }

    private static void addArraySpecs(java.util.Optional<Object> opt, List<IngredientSpec> out) {
        if (opt.isEmpty()) return;
        if (opt.get() instanceof Ingredient[] arr) {
            for (Ingredient ing : arr) {
                if (ing != null && !ing.isEmpty()) out.add(new IngredientSpec(ing, 1));
            }
        }
    }
}
