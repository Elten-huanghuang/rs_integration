package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DependencyProjectionTest {
    @Test
    void sparseProjectionKeepsEveryVariantOfTheSelectedRecipeAndBucketOrder() {
        MaterialRef raw = material("raw");
        MaterialRef middle = material("middle");
        MaterialRef result = material("result");
        MaterialRef taggedResult = new MaterialRef(result.itemId(), "{state:1}");
        MaterialRef alternateOutput = material("alternate_result");
        RecipeNode producer = recipe("producer", middle, raw);
        RecipeNode target = recipe("target", result, middle);
        RecipeNode taggedTarget = recipe("target", taggedResult, middle);
        RecipeNode alternateTarget = recipe("target", alternateOutput, middle);
        RecipeNode unrelated = recipe("unrelated", result, raw);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                result, List.of(unrelated, target), taggedResult, List.of(taggedTarget),
                alternateOutput, List.of(alternateTarget), middle, List.of(producer)),
                Map.of(target.recipeId(), target, producer.recipeId(), producer, unrelated.recipeId(), unrelated));
        for (boolean compiled : List.of(false, true)) {
            try {
                if (compiled) ImmutableRecipeGraphProjector.publishCompiled(null, 1L, graph, 0L);
                ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.restrictToDependencies(graph, target.recipeId());
                assertEquals(Set.of(target.recipeId(), producer.recipeId()), scoped.recipesById().keySet());
                assertSame(target, scoped.recipesById().get(target.recipeId()));
                assertEquals(List.of(target), scoped.recipesByOutput().get(result));
                assertEquals(List.of(taggedTarget), scoped.recipesByOutput().get(taggedResult));
                assertEquals(List.of(alternateTarget), scoped.recipesByOutput().get(alternateOutput));
                assertEquals(List.of(producer), scoped.recipesByOutput().get(middle));
            } finally {
                ImmutableRecipeGraphProjector.clearCache();
            }
        }
    }

    @Test
    void largePublishedCatalogKeepsOnlyTheRequestedChainAndChecksCancellationOnCacheHit() {
        MaterialRef raw = material("raw");
        MaterialRef middle = material("middle");
        MaterialRef result = material("result");
        RecipeNode producer = recipe("producer", middle, raw);
        RecipeNode target = recipe("target", result, middle);
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        outputs.put(middle, List.of(producer));
        outputs.put(result, List.of(target));
        for (int i = 0; i < 50_000; i++) {
            MaterialRef output = material("unrelated_" + i);
            outputs.put(output, List.of(recipe("unrelated_" + i, output, raw)));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(outputs);
        try {
            ImmutableRecipeGraphProjector.publishCompiled(null, 1L, graph, 0L);
            ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.restrictToDependencies(graph, target.recipeId());
            assertEquals(Map.of(middle, List.of(producer), result, List.of(target)), scoped.recipesByOutput());
            assertSame(scoped, ImmutableRecipeGraphProjector.restrictToDependencies(graph, target.recipeId()));
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> ImmutableRecipeGraphProjector.restrictToDependencies(graph, target.recipeId()));
        } finally {
            Thread.interrupted();
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(new ResourceLocation("test", name), "");
    }

    private static RecipeNode recipe(String name, MaterialRef output, MaterialRef input) {
        return new RecipeNode(new ResourceLocation("test", name), output, 1,
                List.of(new IngredientRef(List.of(input), 1)));
    }
}
