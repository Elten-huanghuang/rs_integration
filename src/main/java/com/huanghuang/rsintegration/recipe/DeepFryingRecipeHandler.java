package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/** 随意乐事炸锅的精确配方处理器。 */
public final class DeepFryingRecipeHandler extends AbstractRecipeHandler {

    public static final String RECIPE_CLASS =
            "com.va11halla.casualness_delight.recipe.DeepFryingRecipe";

    static {
        // 只注册具体类名，避免抢走 casualness_delight 其它配方。
        registerRecipePrefixes(DeepFryingRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_CD_DEEP_FRYING_PAN);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // DeepFryingRecipe 只覆写了无参 getResultItem()。通用结果探针会跳过
        // 这个重载，直接调用它才能拿到真实产物，避免规划误报“不支持机器”。
        ItemStack direct = readDirectResult(recipe);
        if (!direct.isEmpty()) return direct;
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    static ItemStack readDirectResult(Object recipe) {
        try {
            Method method = recipe.getClass().getMethod("getResultItem");
            Object value = method.invoke(recipe);
            if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
        } catch (ReflectiveOperationException ignored) {
            // 映射变化时继续走通用结果提取。
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!canHandle(recipe) || recipe.getIngredients().size() != 1) return null;
        var ingredient = recipe.getIngredients().get(0);
        return ingredient.isEmpty() ? null : List.of(new IngredientSpec(ingredient, 1));
    }
}
