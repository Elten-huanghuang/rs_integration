package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.mods.dungeonsdelight.DungeonsDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.CampfireRecipeSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
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
                || recipe instanceof CampfireCookingRecipe;
    }

    static boolean isCuttingBoardRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CUTTING_BOARD_CLASS);
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
        if (isCookingPotRecipe(recipe)) {
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
        if (isCookingPotRecipe(recipe)) {
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

    private static boolean isCookingPotRecipe(Recipe<?> recipe) {
        if (recipe == null) return false;
        String name = recipe.getClass().getName();
        return COOKING_POT_CLASS.equals(name)
                || DungeonsDelightRecipeHandler.RECIPE_CLASS.equals(name);
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        if (!recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) return List.of();
        List<ItemStack> results = getCuttingBoardResults(recipe);
        if (results.size() <= 1) return List.of();
        List<ItemStack> probabilistic = getProbabilisticCuttingBoardResults(recipe);
        List<ItemStack> secondary = new ArrayList<>(results.size() - 1);
        for (ItemStack result : results.subList(1, results.size())) {
            // 概率产物（例如 CraftTweaker 添加的 % 概率输出）不是每次都会出现，
            // 不能写进「必需次产物」的输出契约，否则没滚到就会被判 OUTPUT_MISSING。
            if (probabilistic.stream().anyMatch(rolled -> ItemStack.isSameItemSameTags(rolled, result))) {
                continue;
            }
            secondary.add(result);
        }
        return List.copyOf(secondary);
    }

    /** 概率小于 1 的切板产物，运行时由 rollResults 决定是否出现。 */
    static List<ItemStack> getProbabilisticCuttingBoardResults(Recipe<?> recipe) {
        try {
            Object values = recipe.getClass().getMethod("getRollableResults").invoke(recipe);
            if (!(values instanceof List<?> list) || list.isEmpty()) return List.of();
            List<ItemStack> stacks = new ArrayList<>();
            for (Object value : list) {
                if (value == null) continue;
                Object chance = value.getClass().getMethod("getChance").invoke(value);
                if (!(chance instanceof Number number) || number.floatValue() >= 1.0F) continue;
                Object stack = value.getClass().getMethod("getStack").invoke(value);
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    stacks.add(itemStack.copy());
                }
            }
            return List.copyOf(stacks);
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] cutting board rollable probe failed", e);
            return List.of();
        }
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

    /** 切板配方是否带真正的小于 1 的概率结算结果。 */
    static boolean hasProbabilisticCuttingBoardResults(Recipe<?> recipe) {
        if (recipe == null || !recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) return false;
        return !getProbabilisticCuttingBoardResults(recipe).isEmpty();
    }

    public static List<IngredientSpec> appendOutputContainerSpec(
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
                new IngredientSpec(tool, 1, InputConsumption.durability(1L)));
    }

    @Nonnull
    @Override
    public List<ScaledIngredientSpec> getRecursiveScaledIngredients(
            Recipe<?> recipe, @Nullable List<ScaledIngredientSpec> ingredients) {
        if (recipe == null || !CUTTING_BOARD_CLASS.equals(recipe.getClass().getName())) {
            return ingredients == null ? List.of() : ingredients;
        }
        if (ingredients == null || ingredients.isEmpty()) return List.of();
        return ingredients.stream()
                .filter(spec -> spec != null && !spec.isEmpty())
                .toList();
    }

    /** 砧板输入和耐久工具都进入递归图；执行阶段仍按实际耐久消耗结算。 */
    public static List<IngredientSpec> cuttingBoardGraphIngredients(
            List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .filter(spec -> spec != null && !spec.isEmpty())
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
