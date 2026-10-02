package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.crockpot.BirdcageEggRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.List;

/** Recipe semantics for CrockPot's birdcage feeding recipes. */
public final class ParrotFeedingRecipeHandler extends AbstractRecipeHandler {
    private static final String RECIPE_CLASS = "com.sihenzhang.crockpot.recipe.ParrotFeedingRecipe";

    static {
        registerRecipePrefixes(ParrotFeedingRecipeHandler.class, RECIPE_CLASS);
    }

    @Nonnull
    @Override
    public ModType modType() {
        return ModType.byId("crockpot_birdcage");
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        if (recipe instanceof BirdcageEggRecipe eggRecipe) {
            return eggRecipe.getResultItem(access);
        }
        try {
            Object ranged = recipe.getClass().getMethod("getResult").invoke(recipe);
            return rangedPlanningResult(ranged);
        } catch (ReflectiveOperationException ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (recipe instanceof BirdcageEggRecipe eggRecipe) {
            return List.of(new IngredientSpec(eggRecipe.ingredient(), 1));
        }
        try {
            Object ingredient = recipe.getClass().getMethod("getIngredient").invoke(recipe);
            return ingredient instanceof Ingredient value && !value.isEmpty()
                    ? List.of(new IngredientSpec(value, 1)) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return false;
    }

    @Override
    public boolean supportsTargetedProduction(@Nonnull Recipe<?> recipe) {
        // 肉换蛋还依赖具体鹦鹉颜色，继续使用它已有的索引禁入规则。
        return !(recipe instanceof BirdcageEggRecipe);
    }

    @Override
    public boolean indexPrimaryOutput(@Nonnull Recipe<?> recipe) {
        // The actual egg colour is selected by the parrot in the bound cage,
        // and monster meat may produce no egg at all. Neither is a fixed
        // recursive producer for a requested egg item.
        return !(recipe instanceof BirdcageEggRecipe);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return recipe instanceof BirdcageEggRecipe || super.canHandle(recipe);
    }

    /**
     * Gets a non-empty display/planning result. Some valid CrockPot feeds have
     * a 0..N random result; planning one requested item as one feed attempt is
     * preferable to rejecting the supported recipe as an unknown machine.
     */
    static ItemStack rangedPlanningResult(Object ranged) throws ReflectiveOperationException {
        if (ranged == null) return ItemStack.EMPTY;
        Field item = ranged.getClass().getField("item");
        Field min = ranged.getClass().getField("min");
        Field max = ranged.getClass().getField("max");
        Object value = item.get(ranged);
        int minimum = min.getInt(ranged);
        int maximum = max.getInt(ranged);
        return value instanceof Item result && maximum > 0
                ? new ItemStack(result, Math.max(1, minimum)) : ItemStack.EMPTY;
    }

    /** True when a successful feed is allowed to enqueue no world output. */
    public static boolean canProduceNoOutput(@Nonnull Recipe<?> recipe) {
        if (recipe instanceof BirdcageEggRecipe eggRecipe) return eggRecipe.monsterMeat();
        try {
            Object ranged = recipe.getClass().getMethod("getResult").invoke(recipe);
            if (ranged == null) return false;
            return ranged.getClass().getField("min").getInt(ranged) <= 0;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
