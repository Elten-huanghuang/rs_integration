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

final class EidolonRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.EIDOLON; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("elucent.eidolon.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
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
                return result.isEmpty() ? null : result;
            }
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

    @Nullable
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
