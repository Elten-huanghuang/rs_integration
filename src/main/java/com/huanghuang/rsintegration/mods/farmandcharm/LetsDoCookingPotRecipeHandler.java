package com.huanghuang.rsintegration.mods.farmandcharm;

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
 * farm_and_charm 烹饪锅配方（{@code farm_and_charm:pot_cooking}）。
 * <p>
 * 配方自带一个"盛装容器"字段（如碗），它在原生逻辑里是**每完成一份**各消耗一个：
 * 输出槽为空时先把产物写进 7 号槽，随后只要 6 号槽非空就无条件扣 1，不再检查产物是否
 * 放得下。为了与原生行为一致，这里把容器按 {@code 输出数量} 追加成一条材料需求，
 * 让一次合成的容器需求量与实际会被扣掉的数量相等（例如输出 3 份就需要预留 3 个碗）。
 * <p>
 * 容器预留的原型 Stack 数量为 1，避免在计划视图里把一个碗显示成一叠；
 * 实际插进 6 号槽的是"每份用量 × 份数"。
 * <p>
 * candlelight 的大汤锅复用同一种配方类型，因此共用该 handler（ModType 各自独立）。
 */
public final class LetsDoCookingPotRecipeHandler extends AbstractRecipeHandler {

    /** 配方类全名；两个模组的锅共用同一套配方。 */
    public static final String RECIPE_CLASS =
            "net.satisfy.farm_and_charm.recipe.CookingPotRecipe";

    // ── 原生槽位布局（CookingPotBlockEntity / RoasterBlockEntity / LargeCookingPotBlockEntity 一致） ──
    /** 食材占用 0–5 共 6 个槽位。 */
    public static final int INGREDIENTS_AREA = 6;
    /** 盛装容器槽。 */
    public static final int CONTAINER_SLOT = 6;
    /** 成品槽。 */
    public static final int OUTPUT_SLOT = 7;
    /** 库存总槽数。 */
    public static final int INVENTORY_SIZE = OUTPUT_SLOT + 1;

    static {
        registerRecipePrefixes(LetsDoCookingPotRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_FC_COOKING_POT);
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    /**
     * 本类配方实现了 {@code isSpecial() == true}，原版数据包加载器会直接跳过它们，
     * 因此无法从 {@code RecipeManager} 取回。这里保持默认的"未声明结果"，
     * 让 {@code ModRecipeHandlers} 走结构字段回退，直接读 {@code output} 字段。
     */
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
        ItemStack container = declaredContainer(recipe);
        if (!container.isEmpty()) {
            inputs.add(new IngredientSpec(Ingredient.of(container), containerPerCraft(recipe)));
        }
        return inputs.isEmpty() ? null : List.copyOf(inputs);
    }

    /**
     * 读取配方声明的盛装容器。
     * <p>
     * 这里是 vanilla {@code getMethod("getContainer")}：原版条目没有混淆，
     * 不复刻原版方法不会踩到 SRG 重映射。
     */
    @Nullable
    public static ItemStack declaredContainer(Recipe<?> recipe) {
        if (recipe == null) return null;
        try {
            Object value = recipe.getClass().getMethod("getContainer").invoke(recipe);
            if (!(value instanceof ItemStack stack) || stack.isEmpty()) return ItemStack.EMPTY;
            return stack.copyWithCount(1);
        } catch (ReflectiveOperationException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 单份合成要预留的容器总量（原型数量 1，份数为 1）。 */
    public static ItemStack containerForCraft(Recipe<?> recipe) {
        return containerForCraft(recipe, 1);
    }

    /**
     * 批量合成要预留的容器总量。
     * <p>
     * 原生每个成品各扣一个容器，因此总预留量 = 每份用量 × 份数。
     */
    public static ItemStack containerForCraft(Recipe<?> recipe, int operations) {
        ItemStack prototype = declaredContainer(recipe);
        if (prototype.isEmpty()) return ItemStack.EMPTY;
        long total = (long) containerPerCraft(recipe) * Math.max(1, operations);
        return prototype.copyWithCount((int) Math.min(Integer.MAX_VALUE, total));
    }

    /** 每份成品消耗的容器数量（原生按输出堆叠数扣容器）。 */
    public static int containerPerCraft(Recipe<?> recipe) {
        if (recipe == null) return 1;
        ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY);
        return result.isEmpty() ? 1 : Math.max(1, result.getCount());
    }
}
