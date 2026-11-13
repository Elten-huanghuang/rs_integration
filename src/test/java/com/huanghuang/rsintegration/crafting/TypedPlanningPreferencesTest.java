package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedPlanningPreferencesTest extends BootstrapTest {
    private boolean diagnosticsEnabled;

    @BeforeEach
    void enableStandaloneDiagnostics() {
        diagnosticsEnabled = Diagnostics.isEnabled();
        Diagnostics.setEnabled(true);
    }

    @AfterEach
    void restoreDiagnostics() {
        Diagnostics.setEnabled(diagnosticsEnabled);
        Diagnostics.clear();
    }

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

    @ParameterizedTest
    @ValueSource(ints = {2, 1024})
    void stockOnlyBestEffortCountsPartialSupplyExactlyOnce(int needed) {
        Ingredient ingredient = Ingredient.of(Items.DIAMOND);
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                Map.of(new CraftingResolver.StackKey(Items.DIAMOND, null), 1L), null,
                true, null, Long.MAX_VALUE, false).withPreferences(new PlanningPreferences(
                Map.of("minecraft:diamond", RecipeChoice.NoRecipe.INSTANCE), Map.of(), Set.of()));
        NodeId node = new NodeId(0);
        InputPortId input = new InputPortId(node, 0);

        assertTrue(CraftingResolver.ensureIngredient(ingredient, needed, context, 0,
                new CraftingResolver.EdgeTracker(), input, null));

        assertEquals(1L, context.graphAllocations.stream().mapToLong(allocation -> allocation.quantity()).sum());
        assertEquals(needed - 1L, context.graphUnresolved.stream().mapToLong(demand -> demand.quantity()).sum());
        CraftNode terminal = new CraftNode(node, new ResourceLocation("test", "stock_only"), "generic",
                null, 1, List.of(), List.of(), false, null, null,
                List.of(new InputDemand(input, ingredient, needed, DemandRole.CONSUMED, new ItemStack(Items.DIAMOND))), List.of());
        CraftPlanValidator.validate(new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(terminal), context.graphAllocations, List.of(), context.graphUnresolved, List.of(node)));
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
