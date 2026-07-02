package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
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
import java.util.List;

public final class CrockPotRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_CLASS = "com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe";

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
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            List<IngredientSpec> specs = new ArrayList<>();
            int slotCount = 0;

            for (Object req : requirements) {
                slotCount += collectIngredients(req, specs);
            }

            // Pad remaining slots with filler item so the plan shows exactly what
            // the pot consumes per craft.
            int potLevel = getPotLevel(recipe);
            int remaining = potLevel - slotCount;
            if (remaining > 0) {
                Ingredient filler = resolveFillerIngredient();
                if (filler != null) {
                    specs.add(new IngredientSpec(filler, remaining));
                }
            }

            return specs.isEmpty() ? null : specs;
        } catch (Exception e) {
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
                // OR occupies 1 slot but collects both options as alternatives
                int a = collectIngredients(getFirst.invoke(requirement), specs);
                int b = collectIngredients(getSecond.invoke(requirement), specs);
                return Math.max(a, b);
            }
            // RequirementCategoryMax/Min/MaxExclusive/MinExclusive — food-value
            // constraints that don't correspond to specific item slots.
            // These are surfaced as plan warnings via CrockPotBatchDelegate.
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int getPotLevel(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getPotLevel");
            int level = (int) m.invoke(recipe);
            return level > 0 ? level : 4; // 0 means "any level" → use 4 (basic pot)
        } catch (Exception e) {
            return 4; // fallback: standard CrockPot
        }
    }

    @Nullable
    private static Ingredient resolveFillerIngredient() {
        String id = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == Items.AIR) return null;
        return Ingredient.of(new ItemStack(item));
    }

    /**
     * Returns the number of non-category requirements (slot-occupying items)
     * this recipe requires, without adding filler items.
     */
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
            return 0;
        }
    }

    /**
     * Returns true if the recipe has any food-value category constraints
     * that are not captured as concrete ingredient specs.
     */
    public static boolean hasCategoryConstraints(Recipe<?> recipe) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            for (Object req : requirements) {
                if (req.getClass().getName().contains("RequirementCategory")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
