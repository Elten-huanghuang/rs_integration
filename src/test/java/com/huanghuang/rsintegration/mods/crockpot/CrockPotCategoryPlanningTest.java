package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.sihenzhang.crockpot.base.FoodCategory;
import com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMax;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCategoryMin;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementCombinationOr;
import com.sihenzhang.crockpot.recipe.cooking.requirement.RequirementMustContainIngredient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.count;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotTestFixtures.matches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        assertNull(plan(CrockPotTestFixtures.fishRecipe(), Map.of(
                new StackKey(Items.COD, null), 1L, new StackKey(Items.DIAMOND, null), 64L)));
    }

    @Test
    void backgroundReplayIsIndependentOfLaterStockSlotsTargetAndConfiguration() {
        var recipe = CrockPotTestFixtures.fishRecipe();
        var stock = new LinkedHashMap<StackKey, Long>();
        stock.put(new StackKey(Items.COD, null), 1L);
        stock.put(new StackKey(Items.STICK, null), 3L);
        stock.put(new StackKey(Items.APPLE, null), 3L);
        var target = new ItemStack(Items.COOKED_COD);
        var first = CrockPotCategoryPlanning.capture(recipe, target, stock, level, 4);
        RSIntegrationConfig.CROCKPOT_FILLER_ITEM.set("minecraft:apple");
        stock.remove(new StackKey(Items.STICK, null));
        var second = CrockPotCategoryPlanning.capture(recipe, target, stock, level, 2);
        target.getOrCreateTag().putBoolean("other", true);
        assertNull(CrockPotCategoryPlanning.capture(recipe, target, stock, level, 2));
        stock.clear();
        clearInvocations(level);

        CompletableFuture.runAsync(() -> {
            assertEquals(3, count(CrockPotCategoryPlanning.ingredients(first), Items.STICK));
            assertEquals(1, count(CrockPotCategoryPlanning.ingredients(second), Items.APPLE));
            assertEquals(3, count(CrockPotCategoryPlanning.ingredients(first), Items.STICK));
        }).join();
        verifyNoInteractions(level);
    }

    @Test
    void firstSatisfiableBranchAndFirstEqualScoringVariantWin() {
        var stock = new LinkedHashMap<StackKey, Long>();
        stock.put(new StackKey(Items.APPLE, null), 1L);
        stock.put(new StackKey(Items.COD, "{variant:2}"), 1L);
        stock.put(new StackKey(Items.COD, "{variant:1}"), 1L);
        var recipe = CrockPotTestFixtures.recipe(new RequirementCombinationOr(
                new RequirementCategoryMin(FoodCategory.FISH, 1F),
                new RequirementCategoryMin(FoodCategory.FRUIT, 1F)));
        var selected = CrockPotCategoryPlanning.solve(CrockPotCategoryPlanning.capture(
                recipe, null, stock, level, 1));
        assertNotNull(selected);
        assertEquals(new StackKey(Items.COD, "{variant:2}"), StackKey.of(selected.filler().get(0), true));
    }

    @Test
    void selectedEndpointAndOrderedCountsProduceTheSamePlacement() {
        var recipe = CrockPotTestFixtures.fishSticksRecipe();
        var backend = new StorageBackendId("test");
        var endpoint = mock(CraftStorageEndpoint.class);
        var player = mock(ServerPlayer.class);
        Map<StackKey, Long> counts = new LinkedHashMap<>();
        counts.put(new StackKey(Items.STICK, null), 1L);
        counts.put(new StackKey(Items.COD, "{variant:2}"), 1L);
        counts.put(new StackKey(Items.CARROT, null), 2L);
        var items = counts.entrySet().stream().map(e -> new StoredItem(
                StorageItemKey.fromItemStack(backend, e.getKey().toStack()), e.getValue())).toList();
        when(endpoint.snapshot(player)).thenReturn(StorageSnapshotResult.success(new StorageSnapshot(backend, items)));
        var fromEndpoint = CrockPotCategoryPlanning.capture(recipe, null, endpoint, player, level, 4);
        var fromCounts = CrockPotCategoryPlanning.capture(recipe, null, counts, level, 4);
        verify(endpoint).snapshot(player);
        clearInvocations(endpoint, player, level);
        var first = CrockPotCategoryPlanning.ingredients(fromEndpoint);
        var second = CrockPotCategoryPlanning.ingredients(fromCounts);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(3, first.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(StackKey.of(first.get(i).ingredient().getItems()[0], true),
                    StackKey.of(second.get(i).ingredient().getItems()[0], true));
            assertEquals(first.get(i).count(), second.get(i).count());
        }
        verifyNoInteractions(endpoint, player, level);
    }

    @Test
    void liveCaptureRejectsWorkerBeforeReadingFoodValues() {
        var recipe = CrockPotTestFixtures.fishRecipe();
        when(level.getServer()).thenReturn(mock(MinecraftServer.class));
        assertThrows(IllegalStateException.class, () -> CrockPotCategoryPlanning.capture(
                recipe, null, Map.of(), level, 4));
    }

    private List<IngredientSpec> plan(CrockPotCookingRecipe recipe, Map<StackKey, Long> available) {
        return CrockPotCategoryPlanning.ingredients(CrockPotCategoryPlanning.capture(
                recipe, null, available, level, CrockPotRecipeHandler.INPUT_SLOT_COUNT));
    }
}
