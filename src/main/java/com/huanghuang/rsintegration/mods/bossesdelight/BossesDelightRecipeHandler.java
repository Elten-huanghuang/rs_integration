package com.huanghuang.rsintegration.mods.bossesdelight;

import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;

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
 * BOMD 乐事（bosses_delight）灵魂锅配方（{@code bosses_delight:soul_cooking}）。
 * <p>
 * 反编译核对（{@code SoulCookingPotBlockEntity.processCooking}）：
 * <ul>
 *   <li>食材只在 <b>0–5</b> 号槽各扣 1，且是无条件对全部 6 个槽遍历扣料；</li>
 *   <li>配方另有 {@code container} 字段（{@code getOutputContainer()}），原生不把它当消耗品，
 *       而是当 <b>盛装基准物品</b>：产物完成时留在 6 号展示槽，只有 7 号槽里放着同种容器
 *       （或产物自带合成余留物）才会搬进输出槽；</li>
 *   <li>搬运时 7 号槽每份扣 1 个容器（{@code useStoredContainersOnMeal}），因此容器需要
 *       按每份 1 个预留；</li>
 *   <li>另有一条"配方类型" {@code bosses_delight:soul_cooking_pot} 是玩家给锅具本身点亮的
 *       配方，{@code isSpecial() == true} 且声明了容器，必须排除，否则会被当成可执行锅具配方。</li>
 * </ul>
 * 因此这里与 {@link FarmersDelightRecipeHandler#appendOutputContainerSpec} 的差异有两处：
 * 容器条目的份数按配方<b>输出数量</b>计（而不是恒为 1），并且拒绝特殊配方。
 */
public final class BossesDelightRecipeHandler extends AbstractRecipeHandler {

    /** 灵魂锅配方类全名。 */
    public static final String RECIPE_CLASS =
            "io.github.rcneg.bossesdelight.recipes.SoulCookingPotRecipe";

    // ── 原生槽位布局 ──
    /** 食材占用 0–5 共 6 个槽位。 */
    public static final int INGREDIENTS_AREA = 6;
    /** 待装盛的成品展示槽。 */
    public static final int MEAL_DISPLAY_SLOT = 6;
    /** 装盛容器槽。 */
    public static final int CONTAINER_SLOT = 7;
    /** 成品槽；只有 7 号槽有容器时才从展示槽搬过来。 */
    public static final int OUTPUT_SLOT = 8;
    /** 库存总槽数。 */
    public static final int INVENTORY_SIZE = 9;

    static {
        registerRecipePrefixes(BossesDelightRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_BD_SOUL_COOKING_POT);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName())
                && !recipe.isSpecial();
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
        if (inputs.isEmpty()) return null;
        ItemStack container = getOutputContainer(recipe);
        if (!container.isEmpty()) inputs.add(new IngredientSpec(Ingredient.of(container), 1));
        return List.copyOf(inputs);
    }

    /**
     * 读取配方声明的盛装容器，份数按配方输出数量放大。
     * <p>
     * 原生只要求 "7 号槽里的容器与它同种"，但每完成一份就扣 1 个，因此一次合成输出 N 份就要
     * 预留 N 个；原型数量保持 1，避免计划视图把容器显示成一叠。
     */
    public static ItemStack getOutputContainer(Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        ItemStack container;
        try {
            Object value = recipe.getClass().getMethod("getOutputContainer").invoke(recipe);
            if (!(value instanceof ItemStack stack) || stack.isEmpty()) return ItemStack.EMPTY;
            container = stack.copyWithCount(1);
        } catch (ReflectiveOperationException e) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY);
        return container.copyWithCount(result.isEmpty() ? 1 : Math.max(1, result.getCount()));
    }

    /** 单次合成所需容器总量：原型 1 个，份数按输出数量。 */
    public static ItemStack containerForCraft(Recipe<?> recipe) {
        return getOutputContainer(recipe);
    }
}
