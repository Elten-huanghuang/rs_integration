package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanningPreferences;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerRequestAmount;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    @Test
    void observedInkTokensAreProjectedAsFluidOutputs() {
        ItemStack ink = InkFluidSupport.token(
                InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 250)
        );

        List<ConsumerOutputResult> outputs =
                ConsumerCraftingRuntime.observedOutputs(
                        List.of(ink),
                        ConsumerOutputDestination.RS_NETWORK,
                        true
                );

        assertEquals(1, outputs.size());
        assertEquals(
                IntegrationResourceKey.ResourceKind.FLUID,
                outputs.get(0).resource().kind());
        assertEquals(250L, outputs.get(0).amount());
    }

    @Test
    void reportedFluidOutputsRemainFluidResourcesInOrderStatus() {
        IntegrationResourceKey water = IntegrationResourceKey.fluid(
                new FluidStack(Fluids.WATER, 1));
        List<ConsumerOutputResult> outputs =
                ConsumerCraftingRuntime.observedReportedOutputs(
                        List.of(
                                new CollectedOutput(
                                        water,
                                        750L,
                                        OutputKind.PRIMARY,
                                        resource("water")
                                ),
                                new CollectedOutput(
                                        water,
                                        250L,
                                        OutputKind.SECONDARY,
                                        resource("water")
                                )
                        ),
                        ConsumerOutputDestination.RS_NETWORK
                );

        assertEquals(1, outputs.size());
        assertEquals(IntegrationResourceKey.ResourceKind.FLUID,
                outputs.get(0).resource().kind());
        assertEquals(1_000L, outputs.get(0).amount());
        assertTrue(outputs.get(0).delivered());
    }

    @Test
    void typedTerminalTargetsRealOutputQuantityWithoutPrefillingAttempts() {
        Recipe<?> recipe = mock(Recipe.class);
        CraftingResolver.ResolutionStep step =
                ConsumerCraftingRuntime.typedTerminalStep(
                        resource("typed_terminal"),
                        recipe,
                        ModType.EXTERNAL_INTEGRATION,
                        1,
                        new ItemStack(Items.DIAMOND),
                        17L,
                        true
                );

        assertEquals(1, step.executions());
        assertEquals(17L, step.productionTarget().quantity());
        assertEquals(Items.DIAMOND, step.syntheticOutput().getItem());
    }

    @Test
    void typedTerminalCanRepresentAProbabilisticFluidTarget() {
        IntegrationResourceKey water =
                IntegrationResourceKey.fluid(
                        new FluidStack(Fluids.WATER, 1));
        Recipe<?> recipe = mock(Recipe.class);
        CraftingResolver.ResolutionStep step =
                ConsumerCraftingRuntime.typedTerminalStep(
                        resource("fluid_terminal"),
                        recipe,
                        ModType.EXTERNAL_INTEGRATION,
                        1,
                        water,
                        new ItemStack(Items.PAPER),
                        750L,
                        true
                );

        assertNull(step.syntheticOutput());
        assertEquals(
                IntegrationResourceKey.ResourceKind.FLUID,
                step.productionTarget().resource().kind());
        assertEquals(750L,
                step.productionTarget().quantity());
    }

    @Test
    void executionsUseDeclaredOutputUnitsPerOperation() {
        ConsumerRecipeKey executions = new ConsumerRecipeKey(
                resource("ink_recycling"),
                null,
                new ConsumerRequestAmount.Executions(3),
                ConsumerPlanningPreferences.empty()
        );
        ConsumerRecipeKey units = new ConsumerRecipeKey(
                resource("ink_recycling"),
                null,
                new ConsumerRequestAmount.OutputUnits(750L),
                ConsumerPlanningPreferences.empty()
        );

        assertEquals(750L,
                ConsumerCraftingRuntime.outputsRequestedCount(
                        executions, 250));
        assertEquals(750L,
                ConsumerCraftingRuntime.outputsRequestedCount(
                        units, 250));
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(new ResourceLocation("test", path), "");
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }
}
