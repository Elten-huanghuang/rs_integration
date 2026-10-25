package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputResult;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerCraftingRuntimeTest extends BootstrapTest {
    @Test
    void configuredPlannerHonorsForcedAndExcludedRecipes() {
        MaterialRef input = material("input");
        MaterialRef output = material("output");
        RecipeNode recipe = new RecipeNode(
                resource("recipes/consumer"),
                output,
                1,
                List.of(new IngredientRef(List.of(input), 1))
        );
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(output, List.of(recipe)));

        PureRecipePlanner.Result ready = PureRecipePlanner.resolveConfigured(
                graph,
                Map.of(input, 1L),
                List.of(new IngredientRef(List.of(output), 1)),
                32,
                1024,
                256,
                System.nanoTime() + 5_000_000_000L,
                Map.of(output.itemId(), recipe.recipeId()),
                PlanningPreferences.empty()
        );
        PureRecipePlanner.Result excluded =
                PureRecipePlanner.resolveConfigured(
                        graph,
                        Map.of(input, 1L),
                        List.of(new IngredientRef(List.of(output), 1)),
                        32,
                        1024,
                        256,
                        System.nanoTime() + 5_000_000_000L,
                        Map.of(output.itemId(), recipe.recipeId()),
                        new PlanningPreferences(
                                Map.of(),
                                Map.of(),
                                Set.of(recipe.recipeId())
                        )
                );

        assertTrue(ready.feasible());
        assertFalse(excluded.feasible());
        assertEquals(
                PureRecipePlanner.Feasibility.INFEASIBLE,
                excluded.feasibility()
        );
    }

    @Test
    void observedOutputsMergeOnlyRealRuntimeStacks() {
        List<ConsumerOutputResult> outputs =
                ConsumerCraftingRuntime.observedOutputs(
                List.of(
                        new ItemStack(Items.DIAMOND, 2),
                        new ItemStack(Items.DIAMOND, 3),
                        ItemStack.EMPTY
                ),
                ConsumerOutputDestination.RS_NETWORK,
                true
        );

        assertEquals(1, outputs.size());
        assertEquals(5L, outputs.get(0).amount());
        assertTrue(outputs.get(0).delivered());
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(new ResourceLocation("test", path), "");
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }
}
