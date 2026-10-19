package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.plan.MagicResourceCostCatalog;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import mythicbotany.infuser.InfuserRecipe;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MagicResourceCostPlanningTest extends BootstrapTest {
    @Test
    void purePlannerAggregatesSelectedRecipeCostsRecursively() {
        ResourceLocation recipeId = new ResourceLocation("test", "mana_recipe");
        ResourceLocation itemId = new ResourceLocation("minecraft", "stone");
        ImmutableRecipeGraph.MaterialRef output =
                new ImmutableRecipeGraph.MaterialRef(itemId, "");
        ImmutableRecipeGraph.RecipeNode node = new ImmutableRecipeGraph.RecipeNode(
                recipeId, output, 1, List.of(), "test", recipeId,
                List.of(new MagicResourceCost(MagicResourceKind.MANA, 75)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(output, List.of(node)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(),
                List.of(new ImmutableRecipeGraph.IngredientRef(List.of(output), 1)), 8);

        assertEquals(List.of(new MagicResourceCost(MagicResourceKind.MANA, 75)),
                result.magicCosts());
    }

    @Test
    void costMergeAddsWithinRangeAndKeepsEnumOrder() {
        List<MagicResourceCost> merged = MagicResourceCost.merge(List.of(
                new MagicResourceCost(MagicResourceKind.SOURCE, 40),
                new MagicResourceCost(MagicResourceKind.MANA, 10),
                new MagicResourceCost(MagicResourceKind.SOURCE, 2)));

        assertEquals(List.of(
                new MagicResourceCost(MagicResourceKind.MANA, 10),
                new MagicResourceCost(MagicResourceKind.SOURCE, 42)), merged);
    }

    @Test
    void costMergeRejectsTotalsThatCannotBeRepresented() {
        assertThrows(ArithmeticException.class, () -> MagicResourceCost.merge(List.of(
                new MagicResourceCost(MagicResourceKind.MANA, 1_500_000_000),
                new MagicResourceCost(MagicResourceKind.MANA, 800_000_000))));
    }

    @Test
    void purePlannerRejectsPerRecipeCostsAboveIntRange() {
        ResourceLocation recipeId = new ResourceLocation("test", "large_mana_recipe");
        ResourceLocation itemId = new ResourceLocation("minecraft", "stone");
        ImmutableRecipeGraph.MaterialRef output =
                new ImmutableRecipeGraph.MaterialRef(itemId, "");
        ImmutableRecipeGraph.RecipeNode node = new ImmutableRecipeGraph.RecipeNode(
                recipeId, output, 1, List.of(), "test", recipeId,
                List.of(new MagicResourceCost(MagicResourceKind.MANA, 1_000_000_000)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(node)));

        assertThrows(ArithmeticException.class, () -> PureRecipePlanner.resolve(
                graph, Map.of(),
                List.of(new ImmutableRecipeGraph.IngredientRef(List.of(output), 3)), 8));
    }

    @Test
    void manaIsTrackedAndSourceRemainsOutsideRsiRecursiveCosts() {
        assertEquals(List.of(new MagicResourceCost(MagicResourceKind.MANA, 321)),
                MagicResourceCostCatalog.costsFor(new InfuserRecipe(321)));
        assertTrue(MagicResourceKind.MANA.supportsRsiRecursiveCrafting());
        assertFalse(MagicResourceKind.SOURCE.supportsRsiRecursiveCrafting());
        assertTrue(MagicResourceKind.SOUL.supportsRsiRecursiveCrafting());
    }
}
