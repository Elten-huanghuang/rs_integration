package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureDemandTreeInspectorTest {
    @Test
    void acceptsRecursivelyProjectedDemandTree() {
        MaterialRef log = material("log");
        MaterialRef plank = material("plank");
        MaterialRef target = material("target");
        RecipeNode planks = recipe("planks", plank, 4, ingredient(log, 1));
        RecipeNode assemble = recipe("assemble", target, 1, ingredient(plank, 2));

        var result = PureDemandTreeInspector.inspect(graph(planks, assemble), Map.of(log, 1),
                assemble.recipeId(), 1);

        assertTrue(result.complete());
        assertEquals(PureDemandTreeInspector.Status.COMPLETE, result.status());
    }

    @Test
    void routesMissingUnprojectedDependencyToTypedPlanner() {
        MaterialRef special = material("special_intermediate");
        RecipeNode target = recipe("target", material("result"), 1, ingredient(special, 1));

        var result = PureDemandTreeInspector.inspect(graph(target), Map.of(),
                target.recipeId(), 1);

        assertFalse(result.complete());
        assertEquals(PureDemandTreeInspector.Status.INCOMPLETE, result.status());
        assertEquals(special, result.unresolved());
    }

    @Test
    void inventoryPrunesAnUnprojectedDependency() {
        MaterialRef special = material("special_intermediate");
        RecipeNode target = recipe("target", material("result"), 1, ingredient(special, 3));

        var result = PureDemandTreeInspector.inspect(graph(target), Map.of(special, 3),
                target.recipeId(), 1);

        assertTrue(result.complete());
        assertEquals(0, result.visitedNodes());
    }

    @Test
    void tagNeedsOnlyOneCompletableAlternative() {
        MaterialRef unsupported = material("unsupported_ingot");
        MaterialRef supported = material("supported_ingot");
        MaterialRef ore = material("ore");
        RecipeNode smelt = recipe("smelt", supported, 1, ingredient(ore, 1));
        RecipeNode target = recipe("target", material("result"), 1,
                new IngredientRef(List.of(unsupported, supported), 1));

        var result = PureDemandTreeInspector.inspect(graph(smelt, target), Map.of(ore, 1),
                target.recipeId(), 1);

        assertTrue(result.complete());
    }

    @Test
    void partialTagStockDoesNotReduceThePurePlannerProductionDemand() {
        MaterialRef unsupported = material("unsupported_ingot");
        MaterialRef supported = material("supported_ingot");
        MaterialRef ore = material("ore");
        RecipeNode smelt = recipe("smelt", supported, 1, ingredient(ore, 1));
        RecipeNode target = recipe("target", material("result"), 1,
                new IngredientRef(List.of(unsupported, supported), 2));

        var result = PureDemandTreeInspector.inspect(graph(smelt, target),
                Map.of(unsupported, 1, ore, 1), target.recipeId(), 1);

        assertFalse(result.complete());
    }

    @Test
    void stockedTagVariantAvoidsAbsentVariantExpansion() {
        MaterialRef plank = material("oak_plank");
        MaterialRef oakChest = material("oak_chest");
        List<MaterialRef> variants = new java.util.ArrayList<>();
        List<RecipeNode> allRecipes = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            MaterialRef variant = material("absent_chest_" + i);
            variants.add(variant);
            allRecipes.add(recipe("absent_chest_" + i, variant, 1,
                    ingredient(material("missing_" + i), 1)));
        }
        variants.add(oakChest);
        allRecipes.add(recipe("oak_chest", oakChest, 1, ingredient(plank, 1)));
        RecipeNode target = new RecipeNode(id("compressed_chest"), material("compressed_chest"),
                1, java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> new IngredientRef(variants, 1)).toList());
        allRecipes.add(target);

        var result = PureDemandTreeInspector.inspect(
                graph(allRecipes.toArray(RecipeNode[]::new)),
                Map.of(oakChest, 1, plank, 7), target.recipeId(), 1, 2);

        assertTrue(result.complete());
        assertEquals(1, result.visitedNodes());
    }

    @Test
    void doesNotReuseOneStackAcrossTwoInputs() {
        MaterialRef token = material("token");
        RecipeNode target = recipe("target", material("result"), 1,
                ingredient(token, 1), ingredient(token, 1));

        var result = PureDemandTreeInspector.inspect(graph(target), Map.of(token, 1),
                target.recipeId(), 1);

        assertFalse(result.complete());
    }

    @Test
    void doesNotDoubleCountNbtStackForPlainAndExactDemands() {
        MaterialRef plain = material("charm");
        MaterialRef exact = new MaterialRef(plain.itemId(), "{quality:1}");
        RecipeNode target = recipe("target", material("result"), 1,
                ingredient(plain, 1), ingredient(exact, 1));

        var result = PureDemandTreeInspector.inspect(graph(target), Map.of(exact, 1),
                target.recipeId(), 1);

        assertFalse(result.complete());
    }

    @Test
    void repeatCountConsumesDistinctInventory() {
        MaterialRef token = material("token");
        RecipeNode target = recipe("target", material("result"), 1, ingredient(token, 1));

        assertTrue(PureDemandTreeInspector.inspect(graph(target), Map.of(token, 2),
                target.recipeId(), 2).complete());
        assertFalse(PureDemandTreeInspector.inspect(graph(target), Map.of(token, 1),
                target.recipeId(), 2).complete());
    }

    @Test
    void repeatedAmplificationTargetRequiresOneSeedAndScaledCosts() {
        MaterialRef template = material("template");
        MaterialRef diamond = material("diamond");
        MaterialRef stone = material("stone");
        RecipeNode duplicate = recipe("duplicate", template, 2,
                ingredient(template, 1), ingredient(diamond, 7), ingredient(stone, 1));

        assertTrue(PureDemandTreeInspector.inspect(graph(duplicate),
                Map.of(template, 1, diamond, 42, stone, 6), duplicate.recipeId(), 6).complete());
        assertFalse(PureDemandTreeInspector.inspect(graph(duplicate),
                Map.of(diamond, 42, stone, 6), duplicate.recipeId(), 6).complete());
        assertFalse(PureDemandTreeInspector.inspect(graph(duplicate),
                Map.of(template, 1, diamond, 41, stone, 6), duplicate.recipeId(), 6).complete());
    }

    @Test
    void cycleTerminatesConservatively() {
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        RecipeNode leftFromRight = recipe("left_from_right", left, 1, ingredient(right, 1));
        RecipeNode rightFromLeft = recipe("right_from_left", right, 1, ingredient(left, 1));
        RecipeNode target = recipe("target", material("result"), 1, ingredient(left, 1));

        var result = PureDemandTreeInspector.inspect(
                graph(leftFromRight, rightFromLeft, target), Map.of(), target.recipeId(), 1);

        assertFalse(result.complete());
        assertEquals(PureDemandTreeInspector.Status.INCOMPLETE, result.status());
    }

    @Test
    void nodeBudgetOverflowRoutesConservatively() {
        MaterialRef source = material("source");
        MaterialRef raw = material("raw");
        MaterialRef middle = material("middle");
        RecipeNode rawRecipe = recipe("raw", raw, 1, ingredient(source, 1));
        RecipeNode middleRecipe = recipe("middle", middle, 1, ingredient(raw, 1));
        RecipeNode target = recipe("target", material("result"), 1, ingredient(middle, 1));

        var result = PureDemandTreeInspector.inspect(graph(rawRecipe, middleRecipe, target),
                Map.of(source, 1), target.recipeId(), 1, 1);

        assertFalse(result.complete());
        assertEquals(PureDemandTreeInspector.Status.NODE_LIMIT, result.status());
    }

    @Test
    void missingTargetProjectionRoutesConservatively() {
        ResourceLocation target = id("not_projected");

        var result = PureDemandTreeInspector.inspect(new ImmutableRecipeGraph(Map.of()), Map.of(),
                target, 1);

        assertFalse(result.complete());
        assertEquals(PureDemandTreeInspector.Status.TARGET_NOT_PROJECTED, result.status());
    }

    @Test
    void detectsCatalystBackedAlternativeEvenWhenDirectRecipeIsCompletable() {
        MaterialRef block = material("iron_block");
        MaterialRef nugget = material("iron_nugget");
        MaterialRef ingot = material("iron_ingot");
        RecipeNode direct = recipe("ingots_from_block", ingot, 9, ingredient(block, 1));
        RecipeNode compress = recipe("ingot_from_nuggets", ingot, 1, ingredient(nugget, 9));
        RecipeNode target = recipe("door", material("iron_door"), 1, ingredient(ingot, 6));

        var result = PureDemandTreeInspector.inspect(graph(direct, compress, target),
                Map.of(block, 1), target.recipeId(), 1, 64, Set.of(nugget.itemId()));

        assertTrue(result.complete());
        assertTrue(result.catalystRouteAvailable());
    }

    @Test
    void stockedCatalystOutputKeepsPureRoute() {
        MaterialRef block = material("iron_block");
        MaterialRef nugget = material("iron_nugget");
        MaterialRef ingot = material("iron_ingot");
        RecipeNode direct = recipe("ingots_from_block", ingot, 9, ingredient(block, 1));
        RecipeNode compress = recipe("ingot_from_nuggets", ingot, 1, ingredient(nugget, 9));
        RecipeNode target = recipe("door", material("iron_door"), 1, ingredient(ingot, 6));

        var result = PureDemandTreeInspector.inspect(graph(direct, compress, target),
                Map.of(block, 1, nugget, 54), target.recipeId(), 1, 64,
                Set.of(nugget.itemId()));

        assertTrue(result.complete());
        assertFalse(result.catalystRouteAvailable());
    }

    @Test
    void targetRecipeWithReusableCatalystRoutesTypedEvenWhenInputsAreStocked() {
        MaterialRef catalyst = material("catalyst");
        MaterialRef cost = material("cost");
        RecipeNode target = recipe("copy", material("result"), 3,
                ingredient(catalyst, 1), ingredient(cost, 1));

        var result = PureDemandTreeInspector.inspect(graph(target),
                Map.of(catalyst, 1, cost, 1), target.recipeId(), 1, 64,
                Set.of(), Set.of(target.recipeId()));

        assertTrue(result.complete());
        assertTrue(result.catalystRouteAvailable());
    }

    private static ImmutableRecipeGraph graph(RecipeNode... recipes) {
        Map<MaterialRef, List<RecipeNode>> byOutput = new LinkedHashMap<>();
        for (RecipeNode recipe : recipes) {
            byOutput.computeIfAbsent(recipe.output(), ignored -> new java.util.ArrayList<>())
                    .add(recipe);
        }
        return new ImmutableRecipeGraph(byOutput);
    }

    private static RecipeNode recipe(String name, MaterialRef output, int outputCount,
                                     IngredientRef... inputs) {
        return new RecipeNode(id(name), output, outputCount, List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(id(name), "");
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("test", name);
    }
}
