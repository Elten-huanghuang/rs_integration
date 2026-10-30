package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.mods.dungeonsdelight.DungeonsDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.CampfireRecipeSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.recipe.OutputSemantics;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
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
    /**
     * 面团配方类，对应配方类型 {@code farmersdelight:dough}。
     *
     * <p>它继承 {@code CustomRecipe}，json 里没有原料列表，实际是在工作台用
     * 1 小麦 + 1 水桶合成；按类型识别可以覆盖同类型的其它面团配方。</p>
     */
    private static final String DOUGH_CLASS =
            "vectorwing.farmersdelight.common.crafting.DoughRecipe";

    @Override
    public ModType modType() { return ModType.byId("farmersdelight"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(COOKING_POT_CLASS)
                || isDoughRecipe(recipe)
                || recipe instanceof CampfireCookingRecipe;
    }

    static boolean isCuttingBoardRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CUTTING_BOARD_CLASS);
    }

    /**
     * 面团配方（{@code farmersdelight:dough}）继承 {@code CustomRecipe}，
     * 配方 json 里没有原料列表，实际是在工作台用 1 小麦 + 1 水桶合成。
     */
    static boolean isDoughRecipe(@Nullable Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe
                && recipe.getClass().getName().equals(DOUGH_CLASS);
    }

    /** 面团配方的材料，顺序即合成格槽位；水桶会作为剩余物原样返还。 */
    static List<IngredientSpec> doughMaterials() {
        // 主代码没有农夫乐事的编译依赖，这里按具体物品描述水桶；
        // 该物品正好是配方匹配使用的 buckets/water 标签里唯一的实际物品。
        return List.of(
                new IngredientSpec(Ingredient.of(Items.WHEAT), 1),
                new IngredientSpec(Ingredient.of(Items.WATER_BUCKET), 1));
    }

    /** 模拟装配面团时放进合成格的物品，下标与 {@link #doughMaterials()} 一致。 */
    private static ItemStack[] doughGridStacks() {
        return new ItemStack[]{new ItemStack(Items.WHEAT), new ItemStack(Items.WATER_BUCKET)};
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (isDoughRecipe(recipe) && recipe instanceof CraftingRecipe craftingRecipe) {
            // 面团配方没有 getResultItem() 数据，只能按配方自己的匹配条件模拟一次装配取产物。
            return CraftPacketUtils.assembleCraftingOutput(craftingRecipe, doughGridStacks(), access);
        }
        if (recipe instanceof CampfireCookingRecipe) {
            return CampfireRecipeSupport.resolveOutput(recipe, access);
        }
        if (recipe.getClass().getName().equals(CUTTING_BOARD_CLASS)) {
            List<CuttingBoardResult> results = getCuttingBoardResults(recipe);
            return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack().copy();
        }
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (isDoughRecipe(recipe)) {
            // 与农夫乐事源码一致：1 个小麦 + 1 个水桶类物品，产物为小麦面团，水桶按剩余物返还。
            return doughMaterials();
        }
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
        List<RecipeOutputSpec> results = cuttingBoardDeclaredOutputs(recipe);
        if (results.size() <= 1) return List.of();
        List<ItemStack> secondary = new ArrayList<>(results.size() - 1);
        for (RecipeOutputSpec result : results.subList(1, results.size())) {
            // 按结果条目判断，避免同种物品的概率额外产出抹掉必出数量。
            if (result.guaranteedMinimum() > 0L) {
                secondary.add(result.material().toStack((int) result.guaranteedMinimum()));
            }
        }
        return List.copyOf(secondary);
    }

    /** 保留每个原生结果条目的概率与数量，预览和执行使用同一份结构化合同。 */
    static List<RecipeOutputSpec> cuttingBoardDeclaredOutputs(Recipe<?> recipe) {
        List<CuttingBoardResult> results = getCuttingBoardResults(recipe);
        List<RecipeOutputSpec> outputs = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            CuttingBoardResult result = results.get(index);
            int count = result.stack().getCount();
            String group = "cutting:" + recipe.getId() + ":" + index;
            OutputProbability probability = result.chance() >= 1.0F
                    ? OutputProbability.guaranteed()
                    : Float.isFinite(result.chance())
                    ? OutputProbability.probabilistic(Math.max(0.0D, result.chance()), 16, group)
                    : OutputProbability.dynamic(null, 16, group);
            int guaranteed = probability.isGuaranteed() ? count : 0;
            outputs.add(RecipeOutputSpec.item(MaterialKey.of(result.stack()),
                    guaranteed, guaranteed, count,
                    index == 0 ? OutputKind.PRIMARY : OutputKind.SECONDARY,
                    probability, false, null));
        }
        return List.copyOf(outputs);
    }

    private record CuttingBoardResult(ItemStack stack, float chance) {}

    /** 两个版本均用 ChanceResult 描述所有产物；省略 chance 的固定配方默认是 1。 */
    private static List<CuttingBoardResult> getCuttingBoardResults(Recipe<?> recipe) {
        if (!isCuttingBoardRecipe(recipe)) return List.of();
        try {
            Object values = recipe.getClass().getMethod("getRollableResults").invoke(recipe);
            if (!(values instanceof List<?> list) || list.isEmpty()) return List.of();
            List<CuttingBoardResult> outputs = new ArrayList<>();
            for (Object value : list) {
                if (value == null) continue;
                Object stack = value.getClass().getMethod("getStack").invoke(value);
                if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) continue;
                Object chance = value.getClass().getMethod("getChance").invoke(value);
                if (chance instanceof Number number) {
                    outputs.add(new CuttingBoardResult(itemStack.copy(), number.floatValue()));
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
        return OutputSemantics.hasDeterministicPrimary(cuttingBoardDeclaredOutputs(recipe));
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
