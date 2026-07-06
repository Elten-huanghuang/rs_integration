package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CrockPotRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_CLASS = "com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe";
    public static final int CAT_COUNT = 10;

    @Override
    public ModType modType() { return ModType.byId("crockpot"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(RECIPE_CLASS);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // For recipes with category constraints, don't pad with the config
        // filler — the plan shows only real ingredients (with food-value
        // requirements as warnings) and execution fills remaining slots via
        // food-value-aware selection at runtime.
        return getSpecificIngredients(recipe, !hasCategoryConstraints(recipe));
    }

    /**
     * Return ingredient specs for display or extraction.
     * @param padWithFiller if true, pad remaining slots with the config filler
     *                      for correct plan display; if false, return only the
     *                      actual recipe requirements (used by batch delegate
     *                      Phase 1 extraction before food-value slot filling).
     */
    @Nullable
    public static List<IngredientSpec> getSpecificIngredients(Recipe<?> recipe, boolean padWithFiller) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            List<IngredientSpec> specs = new ArrayList<>();
            int slotCount = 0;

            for (Object req : requirements) {
                slotCount += collectIngredients(req, specs);
            }

            if (padWithFiller) {
                int potLevel = getPotLevel(recipe);
                int remaining = potLevel - slotCount;
                if (remaining > 0) {
                    Ingredient filler = resolveFillerIngredient();
                    if (filler != null) {
                        specs.add(new IngredientSpec(filler, remaining));
                    }
                }
            }

            return specs.isEmpty() ? null : specs;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            return null;
        }
    }

    /** @return number of slots this requirement occupies */
    private static int collectIngredients(Object requirement, List<IngredientSpec> specs) {
        String className = requirement.getClass().getName();
        try {
            if (className.endsWith("RequirementMustContainIngredient")
                    || className.endsWith("RequirementMustContainIngredientLessThan")) {
                Method getIngredient = requirement.getClass().getMethod("getIngredient");
                Method getQuantity = requirement.getClass().getMethod("getQuantity");
                Ingredient ing = (Ingredient) getIngredient.invoke(requirement);
                int qty = (int) getQuantity.invoke(requirement);
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, qty));
                return 1;
            } else if (className.endsWith("RequirementCombinationAnd")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return collectIngredients(getFirst.invoke(requirement), specs)
                        + collectIngredients(getSecond.invoke(requirement), specs);
            } else if (className.endsWith("RequirementCombinationOr")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                int a = collectIngredients(getFirst.invoke(requirement), specs);
                int b = collectIngredients(getSecond.invoke(requirement), specs);
                return Math.max(a, b);
            }
            return 0;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            return 0;
        }
    }

    public static int getPotLevel(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getPotLevel");
            int level = (int) m.invoke(recipe);
            return level > 0 ? level : 4;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            return 4;
        }
    }

    @Nullable
    public static Ingredient resolveFillerIngredient() {
        String id = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == Items.AIR) return null;
        return Ingredient.of(new ItemStack(item));
    }

    public static int countSlotRequirements(Recipe<?> recipe) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            int count = 0;
            for (Object req : requirements) {
                count += countSlots(req);
            }
            return count;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            return 0;
        }
    }

    private static int countSlots(Object requirement) {
        String className = requirement.getClass().getName();
        try {
            if (className.endsWith("RequirementMustContainIngredient")
                    || className.endsWith("RequirementMustContainIngredientLessThan")) {
                return 1;
            } else if (className.endsWith("RequirementCombinationAnd")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return countSlots(getFirst.invoke(requirement))
                        + countSlots(getSecond.invoke(requirement));
            } else if (className.endsWith("RequirementCombinationOr")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return Math.max(countSlots(getFirst.invoke(requirement)),
                        countSlots(getSecond.invoke(requirement)));
            }
            return 0;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            return 0;
        }
    }

    public static boolean hasCategoryConstraints(Recipe<?> recipe) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            for (Object req : requirements) {
                if (hasCategoryConstraint(req)) return true;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
        }
        return false;
    }

    private static boolean hasCategoryConstraint(Object req) {
        String name = req.getClass().getName();
        if (name.contains("RequirementCategory")) return true;
        if (name.endsWith("RequirementCombinationAnd")
                || name.endsWith("RequirementCombinationOr")) {
            try {
                Method getFirst = req.getClass().getMethod("getFirst");
                Method getSecond = req.getClass().getMethod("getSecond");
                if (hasCategoryConstraint(getFirst.invoke(req))) return true;
                if (hasCategoryConstraint(getSecond.invoke(req))) return true;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
            }
        }
        return false;
    }

    /**
     * Parse category constraints from a CrockPotCookingRecipe.
     * @return float[2][10] — [0]=mins, [1]=maxs.
     *         mins[c] = minimum required value (0 = no min).
     *         maxs[c] = maximum allowed value (Float.MAX_VALUE = no max).
     */
    public static float[][] parseCategoryConstraints(Recipe<?> recipe) {
        float[] mins = new float[CAT_COUNT];
        float[] maxs = new float[CAT_COUNT];
        Arrays.fill(maxs, Float.MAX_VALUE);

        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            for (Object req : requirements) {
                collectCategoryConstraints(req, mins, maxs);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
        }

        return new float[][]{mins, maxs};
    }

    private static void collectCategoryConstraints(Object req, float[] mins, float[] maxs) {
        try {
            String name = req.getClass().getSimpleName();
            if (name.equals("RequirementCategoryMin")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float min = (float) req.getClass().getMethod("getMin").invoke(req);
                mins[cat] = Math.max(mins[cat], min);
            } else if (name.equals("RequirementCategoryMinExclusive")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float min = (float) req.getClass().getMethod("getMin").invoke(req);
                mins[cat] = Math.max(mins[cat], min + 0.01f);
            } else if (name.equals("RequirementCategoryMax")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float max = (float) req.getClass().getMethod("getMax").invoke(req);
                maxs[cat] = Math.min(maxs[cat], max);
            } else if (name.equals("RequirementCategoryMaxExclusive")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float max = (float) req.getClass().getMethod("getMax").invoke(req);
                maxs[cat] = Math.min(maxs[cat], max - 0.01f);
            } else if (name.equals("RequirementCombinationAnd")
                    || name.equals("RequirementCombinationOr")) {
                Method getFirst = req.getClass().getMethod("getFirst");
                Method getSecond = req.getClass().getMethod("getSecond");
                collectCategoryConstraints(getFirst.invoke(req), mins, maxs);
                collectCategoryConstraints(getSecond.invoke(req), mins, maxs);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed: {}", e.toString());
        }
    }
}
