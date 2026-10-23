package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;
import static org.junit.jupiter.api.Assertions.*;

class ChronosnPackPlanningTest extends BootstrapTest {
    private static final ResourceLocation TARGET = new ResourceLocation("prinegorerouse:chronosn_blade");
    private static final Set<ResourceLocation> FIRST_TIER = Set.of(
            new ResourceLocation("ak:rage"), new ResourceLocation("ak:greedy"),
            new ResourceLocation("ak:lust"), new ResourceLocation("ak:lazy"),
            new ResourceLocation("ak:arrogant"), new ResourceLocation("ak:jealous"),
            new ResourceLocation("ak:tai_ti"), new ResourceLocation("shizuku:teleport_blade"));

    @Test
    void modpackOverrideExpandsAllEightBladeBranchesBeyondFirstTierWithoutInventingProgress() throws Exception {
        ImmutableRecipeGraph graph = fixture(false);
        var stock = Map.of(SlashBladePlanningTest.material(new ItemStack(Items.STICK)), 100000);
        var oldGraph = fixture(true);
        var before = PureRecipePlanner.resolve(oldGraph, stock, oldGraph.recipesById().get(TARGET).inputs(), 512);
        assertTrue(before.steps().isEmpty(), "旧整份 NBT 比较无法找到第一层刀的生产者");

        var result = PlanningThreadContext.runInBackground(() -> PureRecipePlanner.resolve(
                graph, stock, graph.recipesById().get(TARGET).inputs(), 512));
        Set<ResourceLocation> planned = result.steps().stream()
                .map(PureRecipePlanner.PlannedStep::recipeId).collect(Collectors.toSet());
        assertTrue(planned.containsAll(FIRST_TIER), result.toString());
        assertTrue(planned.contains(new ResourceLocation("shizuku:tsubame_blade")), result.toString());
        assertTrue(planned.contains(new ResourceLocation("ak:child")), result.toString());
        assertTrue(result.steps().size() > 20, result.toString());
        assertFalse(result.feasible(), "没有达标刀的库存时仍然必须报告缺料");
        assertFalse(result.missing().isEmpty());
        assertTrue(result.expandedStates() < 1000, result.toString());
        System.out.println("chronosn fixture: recipes=" + graph.recipesById().size()
                + " beforeSteps=" + before.steps().size() + " afterSteps=" + result.steps().size()
                + " missing=" + result.missing().size() + " states=" + result.expandedStates());
    }

    @Test
    void richerStockSatisfiesAllEightDistinctNamesWithoutCombiningDifferentBlades() throws Exception {
        ImmutableRecipeGraph graph = fixture(false);
        Map<MaterialRef, Integer> stock = new HashMap<>();
        stock.put(SlashBladePlanningTest.material(new ItemStack(Items.STICK)), 1);
        for (ResourceLocation id : FIRST_TIER) stock.put(
                SlashBladePlanningTest.material(blade(id.toString(), 1500, 15)), 1);
        var result = PureRecipePlanner.resolve(graph, stock, graph.recipesById().get(TARGET).inputs(), 512);
        assertTrue(result.feasible(), result.toString());
        assertTrue(result.steps().isEmpty());
        stock.remove(SlashBladePlanningTest.material(blade("ak:greedy", 1500, 15)));
        stock.put(SlashBladePlanningTest.material(blade("ak:rage", 1500, 15)), 2);
        assertFalse(PureRecipePlanner.resolve(graph, stock, graph.recipesById().get(TARGET).inputs(), 512).feasible());
    }

    @Test
    void unrelatedLargeCatalogDoesNotAddSearchBranches() throws Exception {
        ImmutableRecipeGraph original = fixture(false);
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>(original.recipesByOutput());
        MaterialRef raw = new MaterialRef(new ResourceLocation("test:external"), "");
        for (int i = 0; i < 100000; i++) {
            ResourceLocation id = new ResourceLocation("noise:item_" + i);
            MaterialRef output = new MaterialRef(id, "");
            outputs.put(output, List.of(new RecipeNode(id, output, 1, List.of(new IngredientRef(List.of(raw), 1)))));
        }
        ImmutableRecipeGraph large = new ImmutableRecipeGraph(outputs);
        ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.restrictToDependencies(large, TARGET);
        assertEquals(original.recipesById().keySet(), scoped.recipesById().keySet());
        var result = PureRecipePlanner.resolve(scoped, Map.of(), scoped.recipesById().get(TARGET).inputs(), 512);
        assertTrue(result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId)
                .collect(Collectors.toSet()).containsAll(FIRST_TIER));
        assertTrue(result.expandedStates() < 1000, result.toString());
    }

    private static ImmutableRecipeGraph fixture(boolean legacy) throws Exception {
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        try (var stream = ChronosnPackPlanningTest.class.getResourceAsStream("/planning/chronosn-pack-fixture.json")) {
            assertNotNull(stream);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : json.getAsJsonArray("recipes")) {
                JsonObject recipe = element.getAsJsonObject();
                List<IngredientSpec> specs = new ArrayList<>();
                for (JsonElement input : recipe.getAsJsonArray("inputs")) {
                    JsonObject object = input.getAsJsonObject();
                    Ingredient ingredient = object.has("request") ? ingredient(object.getAsJsonObject("request"))
                            : Ingredient.of(Items.STICK);
                    specs.add(new IngredientSpec(ingredient, 1));
                }
                String name = recipe.get("blade").getAsString();
                RecipeNode node;
                if (legacy) {
                    List<IngredientRef> inputs = specs.stream().map(spec -> {
                        boolean named = spec.ingredient().toJson().isJsonObject()
                                && spec.ingredient().toJson().getAsJsonObject().has("request");
                        MaterialRef material = ImmutableRecipeGraphProjector.material(spec.ingredient().getItems()[0], named);
                        return new IngredientRef(List.of(material), 1, named ? NbtMatchMode.EXACT : NbtMatchMode.ANY);
                    }).toList();
                    node = new RecipeNode(new ResourceLocation(recipe.get("id").getAsString()),
                            SlashBladePlanningTest.material(blade(name, 0, 0)), 1, inputs);
                } else {
                    node = ImmutableRecipeGraphProjector.projectRecipe(
                            new ResourceLocation(recipe.get("id").getAsString()), blade(name, 0, 0), specs,
                            "slashblade", new ResourceLocation("minecraft:crafting"), true);
                    // 需要原生刀类型/附魔判定的叶子保持兼容路径，不放宽条件。
                    if (node == null) continue;
                }
                outputs.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
            }
        }
        return new ImmutableRecipeGraph(outputs);
    }
}
