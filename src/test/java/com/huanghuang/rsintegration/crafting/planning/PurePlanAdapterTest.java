package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PurePlanAdapterTest extends com.huanghuang.rsintegration.testutil.BootstrapTest {
    @Test
    void keepsThePlannedUnbreakableStateForThePhysicalSmithingDelegate() {
        MaterialRef output = new MaterialRef(new ResourceLocation("minecraft:stone_sword"), "");
        MaterialRef demanded = new MaterialRef(output.itemId(), "{Unbreakable:1}");
        RecipeNode recipe = new RecipeNode(id("smithing"), output, 1, List.of(), "smithing",
                new ResourceLocation("minecraft:smithing"));
        var result = new PureRecipePlanner.Result(true,
                List.of(new PureRecipePlanner.PlannedStep(recipe.recipeId(), 1, demanded)), List.of(), Map.of());

        var steps = PurePlanAdapter.toResolutionSteps(result,
                new ImmutableRecipeGraph(Map.of(output, List.of(recipe))));

        org.junit.jupiter.api.Assertions.assertNotNull(steps.get(0).syntheticOutput());
        org.junit.jupiter.api.Assertions.assertTrue(steps.get(0).syntheticOutput().getTag()
                .getBoolean("Unbreakable"));
        org.junit.jupiter.api.Assertions.assertNull(steps.get(0).syntheticInput());
    }
    @Test
    void preservesTypedIntermediateExecutionRoute() {
        MaterialRef cost = material("cost");
        MaterialRef output = material("output");
        ResourceLocation recipeId = id("typed_intermediate");
        ResourceLocation recipeTypeId = id("typed_recipe_type");
        RecipeNode typed = new RecipeNode(recipeId, output, 1,
                List.of(new IngredientRef(List.of(cost), 1)),
                ModType.FARMINGFORBLOCKHEADS_MARKET.id(), recipeTypeId);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(typed)));
        PureRecipePlanner.Result plan = new PureRecipePlanner.Result(true,
                List.of(new PureRecipePlanner.PlannedStep(recipeId, 3)), List.of(), Map.of());

        var steps = PurePlanAdapter.toResolutionSteps(plan, graph);

        assertEquals(1, steps.size());
        assertEquals(ModType.FARMINGFORBLOCKHEADS_MARKET, steps.get(0).modType());
        assertEquals(recipeTypeId, steps.get(0).recipeTypeId());
        assertEquals(3, steps.get(0).executions());
    }

    @Test
    void findsPlannedRecipeByOutputWhenByIdIndexIsIncomplete() {
        MaterialRef output = material("indexed_after_binding");
        ResourceLocation recipeId = id("indexed_after_binding_recipe");
        RecipeNode recipe = new RecipeNode(recipeId, output, 2, List.of());
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(output, List.of(recipe)), Map.of());
        PureRecipePlanner.Result plan = new PureRecipePlanner.Result(true,
                List.of(new PureRecipePlanner.PlannedStep(recipeId, 6)),
                List.of(), Map.of());

        var steps = PurePlanAdapter.toResolutionSteps(plan, graph);

        assertEquals(1, steps.size());
        assertEquals(6, steps.get(0).executions());
    }

    @Test
    void splitsLongPlannedBatchesIntoWireSizedExecutionSegments() {
        MaterialRef output = material("large_batch_output");
        ResourceLocation recipeId = id("large_batch_recipe");
        RecipeNode recipe = new RecipeNode(recipeId, output, 1, List.of());
        long batches = (long) Integer.MAX_VALUE + 5L;
        PureRecipePlanner.Result plan = new PureRecipePlanner.Result(
                true, List.of(new PureRecipePlanner.PlannedStep(recipeId, batches)),
                List.of(), Map.of());

        var steps = PurePlanAdapter.toResolutionSteps(plan,
                new ImmutableRecipeGraph(Map.of(output, List.of(recipe))));

        assertEquals(2, steps.size());
        assertEquals(Integer.MAX_VALUE, steps.get(0).executions());
        assertEquals(5, steps.get(1).executions());
    }

    @Test
    void fluidOutputKeepsItsResourceTargetInsteadOfAnEmptyItem() {
        MaterialRef water = MaterialRef.fluid(
                new ResourceLocation("minecraft", "water"));
        ResourceLocation recipeId = id("fluid_recipe");
        RecipeNode recipe = new RecipeNode(
                recipeId, water, 250L, List.of());
        PureRecipePlanner.Result plan =
                new PureRecipePlanner.Result(
                        true,
                        List.of(new PureRecipePlanner.PlannedStep(
                                recipeId, 3L)),
                        List.of(),
                        Map.of()
                );

        var steps = PurePlanAdapter.toResolutionSteps(
                plan,
                new ImmutableRecipeGraph(
                        Map.of(water, List.of(recipe)))
        );

        assertEquals(1, steps.size());
        assertNull(steps.get(0).syntheticOutput());
        assertEquals(
                IntegrationResourceKey.ResourceKind.FLUID,
                steps.get(0).productionTarget()
                        .resource().kind());
        assertEquals(
                Fluids.WATER,
                ForgeRegistries.FLUIDS
                        .getValue(steps.get(0).productionTarget()
                                .resource().id()));
        assertEquals(750L, steps.get(0).productionTarget()
                .quantity());
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
