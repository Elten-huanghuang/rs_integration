package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForcedRecipeGraphProjectorTest {

    @Test
    void forcedRecipeLeadsWithoutHidingOtherCandidates() {
        MaterialRef nineX = material("forced_log_9x");
        MaterialRef eightX = material("forced_log_8x");
        MaterialRef sevenX = material("forced_log_7x");
        RecipeNode nineFromEight = recipe("forced_compress_9x", nineX, 1,
                ingredient(eightX, 9));
        RecipeNode eightFromNine = recipe("forced_decompress_9x", eightX, 9,
                ingredient(nineX, 1));
        RecipeNode eightFromSeven = recipe("forced_compress_8x", eightX, 1,
                ingredient(sevenX, 9));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                nineX, List.of(nineFromEight),
                eightX, List.of(eightFromNine, eightFromSeven)));

        ImmutableRecipeGraph projected = ForcedRecipeGraphProjector.apply(
                graph, Map.of(eightX.itemId(), eightFromNine.recipeId()));

        assertNotNull(projected);
        assertEquals(List.of(eightFromNine, eightFromSeven),
                ImmutableRecipeGraphProjector.candidates(projected, eightX, NbtMatchMode.EXACT));
        assertEquals(List.of(nineFromEight),
                ImmutableRecipeGraphProjector.candidates(projected, nineX, NbtMatchMode.EXACT));
    }

    @Test
    void unprojectableForcedRecipeRequiresTypedFallback() {
        MaterialRef target = material("forced_missing_target");
        RecipeNode targetRecipe = recipe("forced_target_recipe", target, 1,
                ingredient(material("forced_target_input"), 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(targetRecipe)));

        assertNull(ForcedRecipeGraphProjector.apply(
                graph, Map.of(target.itemId(), id("missing_forced_recipe"))));
    }

    @Test
    void ignoresSelectionsOutsideTheCurrentRecipeScope() {
        MaterialRef target = material("forced_scoped_target");
        RecipeNode targetRecipe = recipe("forced_scoped_target_recipe", target, 1,
                ingredient(material("forced_scoped_input"), 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(targetRecipe)));

        ImmutableRecipeGraph projected = ForcedRecipeGraphProjector.apply(
                graph, Map.of(material("unrelated_log").itemId(), id("unrelated_recipe")));

        assertNotNull(projected);
        assertEquals(graph.recipesByOutput(), projected.recipesByOutput());
        assertEquals(graph.recipesById(), projected.recipesById());
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static RecipeNode recipe(String path, MaterialRef output, int count,
                                     IngredientRef... inputs) {
        return new RecipeNode(id(path), output, count, List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
