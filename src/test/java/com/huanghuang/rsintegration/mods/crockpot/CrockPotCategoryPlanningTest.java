package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.sihenzhang.crockpot.base.FoodCategory;
import com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMax;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMin;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCombinationOr;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementMustContainIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.count;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.matches;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.noMaximums;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CrockPotCategoryPlanningTest extends BootstrapTest {
    private CrockPotTestFixtures fixture;
    private Level level;

    @BeforeEach
    void setUp() {
        fixture = new CrockPotTestFixtures();
        level = mock(Level.class);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void categoryOnlyRecipeIncludesFoodAndConfiguredFillerInAllFourSlots() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.fishRecipe();
        Map<StackKey, Long> available = Map.of(
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.STICK, null), 3L,
                new StackKey(Items.CARROT, null), 3L);

        List<IngredientSpec> specs = plan(recipe, available);

        assertNotNull(specs);
        assertEquals(1, count(specs, Items.COD));
        assertEquals(3, count(specs, Items.STICK));
        assertEquals(0, count(specs, Items.CARROT));
        assertTrue(matches(recipe, specs, level));
        assertEquals(3L, available.get(new StackKey(Items.STICK, null)));
    }

    @Test
    void changedFillerConfigurationTakesPriorityOverSticks() {
        RSIntegrationConfig.CROCKPOT_FILLER_ITEM.set("minecraft:apple");
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.fishRecipe();

        List<IngredientSpec> specs = plan(recipe, Map.of(
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.STICK, null), 3L,
                new StackKey(Items.APPLE, null), 3L));

        assertNotNull(specs);
        assertEquals(1, count(specs, Items.COD));
        assertEquals(3, count(specs, Items.APPLE));
        assertEquals(0, count(specs, Items.STICK));
        assertTrue(matches(recipe, specs, level));
    }

    @Test
    void mixedRecipeDoesNotReuseItsFixedStickAsExtraFiller() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.fishSticksRecipe();

        List<IngredientSpec> specs = plan(recipe, Map.of(
                new StackKey(Items.STICK, null), 64L,
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.CARROT, null), 2L));

        assertNotNull(specs);
        assertEquals(1, count(specs, Items.STICK));
        assertEquals(1, count(specs, Items.COD));
        assertEquals(2, count(specs, Items.CARROT));
        assertTrue(matches(recipe, specs, level));
    }

    @Test
    void forbiddenInedibleCategorySkipsDefaultStickAndUsesValidFood() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.recipe(
                new RequirementCategoryMin(FoodCategory.FISH, 1F),
                new RequirementCategoryMax(FoodCategory.INEDIBLE, 0F));

        List<IngredientSpec> specs = plan(recipe, Map.of(
                new StackKey(Items.STICK, null), 64L,
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.CARROT, null), 3L));

        assertNotNull(specs);
        assertEquals(0, count(specs, Items.STICK));
        assertTrue(matches(recipe, specs, level));
    }

    @Test
    void satisfyingCategoryMinimumWithoutEnoughFillerIsNotACompletePlan() {
        assertNull(plan(CrockPotTestFixtures.fishRecipe(), Map.of(
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.STICK, null), 2L)));
    }

    @Test
    void fixedIngredientAndCategoryMinimumStillNeedEveryOtherSlot() {
        assertNull(plan(CrockPotTestFixtures.fishSticksRecipe(), Map.of(
                new StackKey(Items.STICK, null), 64L,
                new StackKey(Items.COD, null), 1L,
                new StackKey(Items.CARROT, null), 1L)));
    }

    @Test
    void categoryMinimumIsCheckedEvenWhenFixedIngredientsFillEverySlot() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.recipe(
                new RequirementMustContainIngredient(Ingredient.of(Items.STICK), 4),
                new RequirementCategoryMin(FoodCategory.FISH, 1F));

        assertNull(plan(recipe, Map.of(new StackKey(Items.STICK, null), 4L)));
    }

    @Test
    void fixedIngredientsCannotAlreadyExceedCategoryMaximum() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.recipe(
                new RequirementMustContainIngredient(Ingredient.of(Items.STICK), 2),
                new RequirementCategoryMax(FoodCategory.INEDIBLE, 1F));

        assertNull(plan(recipe, Map.of(
                new StackKey(Items.STICK, null), 2L,
                new StackKey(Items.CARROT, null), 2L)));
    }

    @Test
    void fullFixedRecipeThatMeetsItsCategoriesNeedsNoFiller() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.recipe(
                new RequirementMustContainIngredient(Ingredient.of(Items.COD), 4),
                new RequirementCategoryMin(FoodCategory.FISH, 1F));

        List<IngredientSpec> specs = plan(recipe, Map.of(new StackKey(Items.COD, null), 4L));

        assertNotNull(specs);
        assertTrue(matches(recipe, specs, level));
    }

    @Test
    void alternativeCategoryBranchCanProvideACompletePlan() {
        CrockPotCookingRecipe recipe = CrockPotTestFixtures.recipe(
                new RequirementCombinationOr(
                        new RequirementCategoryMin(FoodCategory.FISH, 1F),
                        new RequirementCategoryMin(FoodCategory.FRUIT, 1F)));

        List<IngredientSpec> specs = plan(recipe, Map.of(
                new StackKey(Items.APPLE, null), 1L,
                new StackKey(Items.STICK, null), 3L));

        assertNotNull(specs);
        assertEquals(1, count(specs, Items.APPLE));
        assertTrue(matches(recipe, specs, level));
    }

    @Test
    void ordinaryNonFoodItemsCannotStandInForMissingFiller() {
        assertNull(CrockPotFoodValues.select(
                value(FoodCategory.FISH, 1F), new float[CrockPotRecipeHandler.CAT_COUNT],
                noMaximums(), 3,
                List.of(new CrockPotFoodValues.Candidate(new ItemStack(Items.DIAMOND), 64)),
                level));
    }

    private List<IngredientSpec> plan(CrockPotCookingRecipe recipe, Map<StackKey, Long> available) {
        return CrockPotBatchDelegate.buildCategoryPlanIngredients(
                recipe, available, level, CrockPotRecipeHandler.INPUT_SLOT_COUNT);
    }
}
