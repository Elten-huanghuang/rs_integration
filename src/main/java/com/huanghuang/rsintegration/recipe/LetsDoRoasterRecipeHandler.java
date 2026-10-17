package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * farm_and_charm 烘焙机配方（{@code farm_and_charm:roaster}）。
 * <p>
 * 与烹饪锅同构：同样带 {@code container} 字段、同样按输出堆叠数扣容器，
 * 只是配方类型与方块实体不同。单独注册一个 ModType，避免与烹饪锅互相抢配方。
 * 容器口径与结果读取完全复用 {@link LetsDoCookingPotRecipeHandler}。
 */
public final class LetsDoRoasterRecipeHandler extends AbstractRecipeHandler {

    public static final String RECIPE_CLASS =
            "net.satisfy.farm_and_charm.recipe.RoasterRecipe";

    static {
        registerRecipePrefixes(LetsDoRoasterRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_FC_ROASTER);
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!canHandle(recipe)) return null;
        List<IngredientSpec> inputs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) inputs.add(new IngredientSpec(ingredient, 1));
        }
        ItemStack container = LetsDoCookingPotRecipeHandler.declaredContainer(recipe);
        if (!container.isEmpty()) {
            inputs.add(new IngredientSpec(Ingredient.of(container),
                    LetsDoCookingPotRecipeHandler.containerPerCraft(recipe)));
        }
        return inputs.isEmpty() ? null : List.copyOf(inputs);
    }
}
