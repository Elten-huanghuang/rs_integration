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
    void costMergeSaturatesAndKeepsEnumOrder() {
        List<MagicResourceCost> merged = MagicResourceCost.merge(List.of(
                new MagicResourceCost(MagicResourceKind.SOURCE, 40),
                new MagicResourceCost(MagicResourceKind.MANA, 10),
                new MagicResourceCost(MagicResourceKind.SOURCE, 2)));

        assertEquals(List.of(
                new MagicResourceCost(MagicResourceKind.MANA, 10),
                new MagicResourceCost(MagicResourceKind.SOURCE, 42)), merged);
    }

    @Test
    void manaAndSourceStayOutOfRsiRecursiveCosts() {
        assertEquals(List.of(),
                MagicResourceCostCatalog.costsFor(new InfuserRecipe(321)));
        assertTrue(MagicResourceKind.MANA.supportsRsiRecursiveCrafting());
        assertFalse(MagicResourceKind.SOURCE.supportsRsiRecursiveCrafting());
        assertTrue(MagicResourceKind.SOUL.supportsRsiRecursiveCrafting());
    }
}
