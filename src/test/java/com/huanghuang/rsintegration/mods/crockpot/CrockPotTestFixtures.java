package com.huanghuang.rsintegration.mods.crockpot;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.sihenzhang.crockpot.base.FoodCategory;
import com.sihenzhang.crockpot.base.FoodValues;
import com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe;
import com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe.Wrapper;
import com.sihenzhang.crockpot.recipe.cooking.requirement.IRequirement;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMax;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMin;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementMustContainIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mockStatic;

/** 保留真实配方和选料算法，只替代需要已启动世界的食材属性查询。 */
public final class CrockPotTestFixtures implements AutoCloseable {
    private final MockedStatic<CrockPotFoodValues> foodValues;
    private final Map<Item, float[]> values = Map.of(
            Items.STICK, value(FoodCategory.INEDIBLE, 1F),
            Items.COD, value(FoodCategory.FISH, 1F),
            Items.CARROT, value(FoodCategory.VEGGIE, 0.5F),
            Items.APPLE, value(FoodCategory.FRUIT, 1F),
            Items.BEEF, value(FoodCategory.MEAT, 1F));

    public CrockPotTestFixtures() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        foodValues = mockStatic(CrockPotFoodValues.class, call ->
            switch (call.getMethod().getName()) {
                case "isReady" -> true;
                case "compute" -> values.get(((ItemStack) call.getArgument(0)).getItem());
                default -> call.callRealMethod();
            });
    }

    public static float[] value(FoodCategory category, float amount) {
        float[] values = new float[CrockPotRecipeHandler.CAT_COUNT];
        values[category.ordinal()] = amount;
        return values;
    }

    public static float[] noMaximums() {
        float[] values = new float[CrockPotRecipeHandler.CAT_COUNT];
        Arrays.fill(values, Float.MAX_VALUE);
        return values;
    }

    public static CrockPotCookingRecipe recipe(IRequirement... requirements) {
        return new CrockPotCookingRecipe(
                ResourceLocation.fromNamespaceAndPath("crockpot", "test_food_order"),
                List.of(requirements), new ItemStack(Items.COOKED_COD), 0, 1, 200, 0);
    }

    public static CrockPotCookingRecipe fishRecipe() {
        return recipe(new RequirementCategoryMin(FoodCategory.FISH, 1F));
    }

    public static CrockPotCookingRecipe fishSticksRecipe() {
        return recipe(
                new RequirementMustContainIngredient(Ingredient.of(Items.STICK), 1),
                new RequirementCategoryMin(FoodCategory.FISH, 1F),
                new RequirementCategoryMax(FoodCategory.INEDIBLE, 1F));
    }

    public static int count(List<IngredientSpec> specs, Item item) {
        return specs.stream().filter(spec -> spec.ingredient().test(new ItemStack(item)))
                .mapToInt(IngredientSpec::count).sum();
    }

    public static boolean matches(CrockPotCookingRecipe recipe, List<IngredientSpec> specs, Level level) {
        List<ItemStack> inputs = new ArrayList<>();
        FoodValues total = FoodValues.create();
        for (IngredientSpec spec : specs) {
            ItemStack stack = spec.ingredient().getItems()[0].copyWithCount(1);
            float[] values = CrockPotFoodValues.compute(stack, level);
            for (int count = 0; count < spec.count(); count++) {
                inputs.add(stack.copy());
            }
            if (values != null) {
                for (FoodCategory category : FoodCategory.values()) {
                    total.set(category, total.get(category) + values[category.ordinal()] * spec.count());
                }
            }
        }
        return inputs.size() == CrockPotRecipeHandler.INPUT_SLOT_COUNT
                && recipe.matches(new Wrapper(inputs, total, 0), level);
    }

    @Override
    public void close() {
        foodValues.close();
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }
}
