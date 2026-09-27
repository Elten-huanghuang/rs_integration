package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.apache.logging.log4j.util.TriConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取 Summoning Rituals 的公开配方访问器。
 *
 * <p>产物仅用于直接配方页面展示，绝不加入递归配方索引。祭坛可能生成
 * 生物或把物品掉落到世界中，RSI 对两者都不声明所有权。</p>
 */
public final class SummoningRitualRecipeHandler extends AbstractRecipeHandler {
    public static final String RECIPE_CLASS =
            "com.almostreliable.summoningrituals.recipe.AltarRecipe";

    static {
        registerRecipePrefixes(SummoningRitualRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_SUMMONING_RITUALS);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return false;
    }

    @Override
    public boolean indexPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return false;
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        // getResultItem 对祭坛配方恒为空；从公开 outputs 访问器中提取第一个物品，
        // 只用于直接操作页面的图标，不允许它成为递归生产者。
        try {
            Object outputs = recipe.getClass().getMethod("getOutputs").invoke(recipe);
            if (outputs == null) return ItemStack.EMPTY;
            List<ItemStack> items = new ArrayList<>();
            TriConsumer<Object, Object, Integer> consumer = (type, output, index) -> {
                if (output == null || !"ITEM".equals(String.valueOf(type))) return;
                try {
                    Object value = output.getClass().getMethod("getOutput").invoke(output);
                    Object count = output.getClass().getMethod("getCount").invoke(output);
                    if (value instanceof ItemStack stack && !stack.isEmpty()
                            && count instanceof Number number && number.intValue() > 0) {
                        items.add(stack.copyWithCount(number.intValue()));
                    }
                } catch (ReflectiveOperationException exception) {
                    RSIntegrationMod.LOGGER.debug(
                            "[RSI-SummoningRituals] Failed reading item output", exception);
                }
            };
            Method forEach = outputs.getClass().getMethod("forEach", TriConsumer.class);
            forEach.invoke(outputs, consumer);
            return items.isEmpty() ? ItemStack.EMPTY : items.get(0);
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-SummoningRituals] Failed reading recipe outputs", exception);
            return ItemStack.EMPTY;
        }
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(@Nonnull Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        Ingredient catalyst = catalyst(recipe);
        if (!catalyst.isEmpty()) {
            result.add(new IngredientSpec(catalyst, 1, DemandRole.CATALYST));
        }
        result.addAll(inputs(recipe));
        return result.isEmpty() ? null : result;
    }

    static Ingredient catalyst(Recipe<?> recipe) {
        try {
            Object value = recipe.getClass().getMethod("getCatalyst").invoke(recipe);
            return value instanceof Ingredient ingredient ? ingredient : Ingredient.EMPTY;
        } catch (ReflectiveOperationException exception) {
            return Ingredient.EMPTY;
        }
    }

    static List<IngredientSpec> inputs(Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        try {
            Object value = recipe.getClass().getMethod("getInputs").invoke(recipe);
            if (!(value instanceof Iterable<?> inputs)) return result;
            for (Object input : inputs) {
                if (input == null) continue;
                Object ingredientValue = input.getClass().getMethod("ingredient").invoke(input);
                Object countValue = input.getClass().getMethod("count").invoke(input);
                if (ingredientValue instanceof Ingredient ingredient && !ingredient.isEmpty()
                        && countValue instanceof Number number && number.intValue() > 0) {
                    result.add(new IngredientSpec(ingredient, number.intValue()));
                }
            }
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-SummoningRituals] Failed reading recipe inputs", exception);
        }
        return result;
    }
}
