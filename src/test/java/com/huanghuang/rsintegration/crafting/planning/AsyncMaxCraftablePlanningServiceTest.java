package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncMaxCraftablePlanningServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void accountsForMultiItemRecipeOutputs() {
        MaterialRef log = material("oak_log");
        MaterialRef plank = material("oak_planks");
        MaterialRef stick = material("stick");
        RecipeNode planks = recipe("planks", plank, 4, ingredient(log, 1));
        RecipeNode sticks = recipe("sticks", stick, 4, ingredient(plank, 2));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                plank, List.of(planks), stick, List.of(sticks)));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1L, 1L,
                sticks.recipeId(), Map.of(new StackKey(Items.OAK_LOG, null), 10), Map.of(),
                graph, "network", "bindings", false);

        AsyncMaxCraftablePlanningService.CompletedSearch result =
                AsyncMaxCraftablePlanningService.compute(snapshot, 1024, 100, 65_536, 8_192);

        assertTrue(result.determined());
        assertEquals(20, result.maximum());
        assertNotNull(result.plan());
    }

    @Test
    void reportsUnknownInsteadOfLoweringMaximumOnSearchLimit() {
        MaterialRef base = material("cobblestone");
        MaterialRef input = material("input");
        MaterialRef output = material("output");
        RecipeNode producer = recipe("producer", input, 1, ingredient(base, 1));
        RecipeNode target = recipe("target", output, 1, ingredient(input, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                input, List.of(producer), output, List.of(target)));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1L, 1L,
                target.recipeId(), Map.of(new StackKey(Items.COBBLESTONE, null), 1), Map.of(),
                graph, "network", "bindings", false);

        AsyncMaxCraftablePlanningService.CompletedSearch result =
                AsyncMaxCraftablePlanningService.compute(snapshot, 1024, 100, 1, 0);

        assertFalse(result.determined());
        assertEquals(0, result.maximum());
    }

    @Test
    void maximumProbesReusePreparationWithoutChangingTheMaximum() {
        MaterialRef log = material("oak_log");
        MaterialRef plank = material("oak_planks");
        MaterialRef stick = material("stick");
        RecipeNode planks = recipe("planks", plank, 4, ingredient(log, 1));
        RecipeNode sticks = recipe("sticks", stick, 4, ingredient(plank, 2));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(plank, List.of(planks), stick, List.of(sticks)));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1L, 1L,
                sticks.recipeId(), Map.of(new StackKey(Items.OAK_LOG, null), 10), Map.of(),
                graph, "network", "bindings", false);
        var expected = PlanningLookupCache.run(new PlanningLookupCache.Limits(0, 0, 0, 0),
                () -> AsyncMaxCraftablePlanningService.compute(snapshot, 1024, 100, 65_536, 8_192));
        PlanningLookupCache.run(() -> {
            var actual = AsyncMaxCraftablePlanningService.compute(snapshot, 1024, 100, 65_536, 8_192);
            assertEquals(expected, actual);
            assertTrue(actual.determined());
            assertEquals(20, actual.maximum());
            var stats = PlanningLookupCache.preparationStats(PlanningLookupCache.PreparationStage.SMITHING);
            assertEquals(1, stats.builds());
            assertTrue(stats.hits() > 1);
            System.out.printf("[RSI-max-preparation] maximum=%d smithingBuilds=%d reused=%d%n",
                    actual.maximum(), stats.builds(), stats.hits());
            return null;
        });
    }

    private static RecipeNode recipe(String id, MaterialRef output, int outputCount,
                                     IngredientRef... inputs) {
        return new RecipeNode(new ResourceLocation("test", id), output, outputCount,
                List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(new ResourceLocation("minecraft", path), "");
    }
}
