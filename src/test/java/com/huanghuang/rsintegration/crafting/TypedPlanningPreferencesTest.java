package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedPlanningPreferencesTest extends BootstrapTest {

    @BeforeAll
    static void loadServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @Test
    void noRecipeConsumesPartialStockAndStopsBeforeCandidateSearch() {
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of("minecraft:diamond", RecipeChoice.NoRecipe.INSTANCE),
                Map.of(), Set.of());
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), List.of(new ItemStack(Items.DIAMOND, 2)), null)
                .withPreferences(preferences);

        boolean resolved = CraftingResolver.ensureIngredient(
                Ingredient.of(Items.DIAMOND), 3L, context, 0,
                new CraftingResolver.EdgeTracker());

        assertFalse(resolved);
        assertEquals(0L, context.countMatching(Ingredient.of(Items.DIAMOND)));
        assertEquals(0L, context.countMatching(Ingredient.of(Items.IRON_INGOT)));
    }

    @Test
    void invalidIngredientPreferenceFallsBackToOriginalCandidates() {
        ResourceLocation recipeId = new ResourceLocation("test", "material_choice");
        Ingredient original = Ingredient.of(Items.DIAMOND, Items.IRON_INGOT);
        String key = MaterialLocks.key(recipeId, original);
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of(),
                Map.of(key, new ItemStack(Items.GOLD_INGOT)),
                Set.of());
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), List.of(), null)
                .withPreferences(preferences);

        Ingredient resolved = context.lockedIngredient(recipeId, original);

        assertSame(original, resolved);
    }

    @Test
    void validIngredientPreferenceNarrowsOnlyTheMatchingSlot() {
        ResourceLocation recipeId = new ResourceLocation("test", "material_choice");
        Ingredient original = Ingredient.of(Items.DIAMOND, Items.IRON_INGOT);
        String key = MaterialLocks.key(recipeId, original);
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of(),
                Map.of(key, new ItemStack(Items.IRON_INGOT)),
                Set.of());
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), List.of(), null)
                .withPreferences(preferences);

        Ingredient resolved = context.lockedIngredient(recipeId, original);

        assertEquals(1, resolved.getItems().length);
        assertEquals(Items.IRON_INGOT, resolved.getItems()[0].getItem());
    }

    @Test
    void noRecipeOnOneTagMemberDoesNotDisableOtherMembers() {
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of("minecraft:diamond", RecipeChoice.NoRecipe.INSTANCE),
                Map.of(), Set.of());
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), List.of(), null)
                .withPreferences(preferences);

        Ingredient mixed = Ingredient.of(Items.DIAMOND, Items.IRON_INGOT);

        assertFalse(context.isNoRecipe(mixed));
        assertTrue(context.isNoRecipe(Ingredient.of(Items.DIAMOND)));
    }
}
