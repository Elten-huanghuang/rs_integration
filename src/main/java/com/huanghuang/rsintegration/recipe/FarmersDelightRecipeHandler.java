package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class FarmersDelightRecipeHandler extends AbstractRecipeHandler {

    private static final String COOKING_POT_CLASS =
            "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";
    private static final String CUTTING_BOARD_CLASS =
            "vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe";

    @Override
    public ModType modType() { return ModType.byId("farmersdelight"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(COOKING_POT_CLASS)
                || recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)
                || recipe instanceof CampfireCookingRecipe;
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CampfireCookingRecipe) {
            return CampfireRecipeSupport.resolveOutput(recipe, access);
        }
        if (recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) {
            List<ItemStack> results = getCuttingBoardResults(recipe);
            return results.isEmpty() ? ItemStack.EMPTY : results.get(0).copy();
        }
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (recipe instanceof CampfireCookingRecipe) {
            Ingredient ing = recipe.getIngredients().get(0);
            if (ing.isEmpty()) return null;
            return List.of(new IngredientSpec(ing, 1));
        }
        if (recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty() || ingredients.get(0).isEmpty()) return null;
            Ingredient toolIngredient = getCuttingBoardToolIngredient(recipe);
            if (toolIngredient != null) {
                return cuttingBoardIngredients(ingredients.get(0), toolIngredient);
            }
            return null;
        }
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : ingredients) {
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            return specs.isEmpty()
                    ? null
                    : appendOutputContainerSpec(specs, getOutputContainer(recipe));
        }
        return null;
    }

    /** Get cook time for a CookingPotRecipe or CampfireCookingRecipe, in ticks. */
    public static int getCookTime(Recipe<?> recipe) {
        if (recipe instanceof CampfireCookingRecipe ccr) {
            return ccr.getCookingTime();
        }
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            try {
                return (int) recipe.getClass().getMethod("getCookTime").invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
            }
        }
        return 200; // fallback
    }

    /** Get the output container item for a CookingPotRecipe (e.g., bowl). */
    public static ItemStack getOutputContainer(Recipe<?> recipe) {
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            try {
                ItemStack container = (ItemStack) recipe.getClass()
                        .getMethod("getOutputContainer").invoke(recipe);
                if (container == null || container.isEmpty()) return ItemStack.EMPTY;
                ItemStack result = recipe.getResultItem(RegistryAccess.EMPTY);
                return withResultCount(container, result);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        if (!recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) return List.of();
        List<ItemStack> results = getCuttingBoardResults(recipe);
        return results.size() <= 1 ? List.of() : List.copyOf(results.subList(1, results.size()));
    }

    private static List<ItemStack> getCuttingBoardResults(Recipe<?> recipe) {
        try {
            Object values = recipe.getClass().getMethod("getResults").invoke(recipe);
            if (!(values instanceof List<?> list) || list.isEmpty()) return List.of();
            List<ItemStack> outputs = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    outputs.add(stack.copy());
                }
            }
            return List.copyOf(outputs);
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] cutting board outputs probe failed", e);
            return List.of();
        }
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        if (!recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) return true;
        try {
            Object values = recipe.getClass().getMethod("getRollableResults").invoke(recipe);
            if (!(values instanceof List<?> list) || list.isEmpty()) return false;
            Object first = list.get(0);
            Object chance = first == null ? null : first.getClass().getMethod("getChance").invoke(first);
            return chance instanceof Number number && number.floatValue() >= 1.0F;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static List<IngredientSpec> appendOutputContainerSpec(
            List<IngredientSpec> inputSpecs, ItemStack container) {
        List<IngredientSpec> specs = new ArrayList<>(inputSpecs.size() + 1);
        specs.addAll(inputSpecs);
        if (container != null && !container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)),
                    container.getCount()));
        }
        return List.copyOf(specs);
    }

    static ItemStack withResultCount(ItemStack container, ItemStack result) {
        if (container == null || container.isEmpty()) return ItemStack.EMPTY;
        int count = result == null || result.isEmpty() ? container.getCount() : result.getCount();
        return container.copyWithCount(Math.max(1, count));
    }

    static List<IngredientSpec> cuttingBoardIngredients(Ingredient input, Ingredient tool) {
        if (input == null || input.isEmpty() || tool == null || tool.isEmpty()) return List.of();
        return List.of(
                new IngredientSpec(input, 1, DemandRole.CONSUMED),
                new IngredientSpec(tool, 1, DemandRole.CATALYST));
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRecursiveIngredients(
            Recipe<?> recipe, @Nullable List<IngredientSpec> ingredients) {
        if (recipe == null || !CUTTING_BOARD_CLASS.equals(recipe.getClass().getName())) {
            return ingredients;
        }
        return cuttingBoardGraphIngredients(ingredients);
    }

    /**
     * 砧板工具只属于运行时资源，不能成为递归合成需求。批处理代理会优先从
     * 共振存储中选择工具，其次才使用当前存储后端中的工具。
     */
    public static List<IngredientSpec> cuttingBoardGraphIngredients(
            List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .filter(spec -> !spec.isEmpty() && spec.role() != DemandRole.CATALYST)
                .toList();
    }

    @Nullable
    public static Ingredient getCuttingBoardToolIngredient(Recipe<?> recipe) {
        if (recipe == null || !CUTTING_BOARD_CLASS.equals(recipe.getClass().getName())) return null;
        try {
            Object tool = recipe.getClass().getMethod("getTool").invoke(recipe);
            return tool instanceof Ingredient ingredient && !ingredient.isEmpty()
                    ? ingredient : null;
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] cutting board tool probe failed", e);
            return null;
        }
    }
}
