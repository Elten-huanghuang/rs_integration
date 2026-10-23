package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;
import static org.junit.jupiter.api.Assertions.*;

class SlashBladePlanningTest extends BootstrapTest {
    @Test
    void thresholdsAcceptRicherNbtButRejectWrongBladeInsufficientStateAndTaglessStock() {
        Ingredient required = ingredient("ak:rage", 1000, 10);
        IngredientRef projected = project(required);
        ItemStack richer = blade("ak:rage", 1200, 15);
        richer.getOrCreateTag().putString("uuid", "runtime-only");
        assertEquals(NbtMatchMode.SLASHBLADE, projected.nbtMatchMode());
        assertTrue(matches(richer, projected));
        assertFalse(matches(blade("ak:greedy", 1200, 15), projected));
        assertFalse(matches(blade("ak:rage", 999, 15), projected));
        assertFalse(matches(blade("ak:rage", 1200, 9), projected));
        assertFalse(matches(new ItemStack(Items.IRON_SWORD), projected));
        assertFalse(SlashBladeRecipeHandler.matchesMaterialKey(required,
                new MaterialKey(Items.DIAMOND, richer.getTag().toString())));
        assertFalse(SlashBladeRecipeHandler.matchesMaterialKey(required, new MaterialKey(Items.IRON_SWORD, null)));
        assertSame(projected, ImmutableRecipeGraphProjector.bindIngredient(projected, Map.of(material(richer), 1)));
    }

    @Test
    void noneMeansNamelessAndUnknownRequirementsStayOutOfPureProjection() {
        IngredientRef nameless = project(ingredient("slashblade:none", 0, 0));
        assertTrue(matches(blade("slashblade:none", 0, 0), nameless));
        assertFalse(matches(blade("ak:rage", 1000, 10), nameless));
        JsonObject request = new JsonObject();
        request.addProperty("name", "ak:rage");
        JsonArray swordTypes = new JsonArray();
        swordTypes.add("bewitched");
        request.add("sword_type", swordTypes);
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(ingredient(request), 1)));
        request.remove("sword_type");
        request.addProperty("future_condition", 1);
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(ingredient(request), 1)));
    }

    @Test
    void teleportBladeRetainsBrokenAndSealedRequirements() {
        JsonObject request = new JsonObject();
        request.addProperty("name", "slashblade:yamato");
        request.addProperty("kill", 1000);
        request.addProperty("refine", 10);
        JsonArray types = new JsonArray();
        types.add("broken");
        types.add("sealed");
        request.add("sword_type", types);
        IngredientRef demand = project(ingredient(request));
        ItemStack yamato = blade("slashblade:yamato", 1000, 10);
        assertFalse(matches(yamato, demand));
        yamato.getTag().getCompound("bladeState").putBoolean("isBroken", true);
        yamato.getTag().getCompound("bladeState").putBoolean("isSealed", true);
        assertTrue(matches(yamato, demand));
        yamato.getTag().putBoolean("Unbreakable", true);
        assertFalse(matches(yamato, demand));
    }

    @Test
    void inheritedMinimumIsConditionalAndNeverPromisesExactNbt() {
        ItemStack declared = blade("test:result", 0, 0);
        RecipeNode node = recipe("test:result", declared, List.of(
                new IngredientSpec(ingredient("ak:rage", 1000, 10), 1),
                new IngredientSpec(ingredient("ak:greedy", 800, 8), 1)));
        assertTrue(node.output().constrainedNbt());
        IngredientRef demand = project(ingredient("test:result", 1800, 10));
        assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(node.output(), demand));
        for (NbtMatchMode mode : List.of(NbtMatchMode.EXACT, NbtMatchMode.PARTIAL)) {
            assertFalse(ImmutableRecipeGraphProjector.matchesIngredient(node.output(),
                    new IngredientRef(List.of(new MaterialRef(node.output().itemId(), node.output().nbt())), 1, mode)));
        }
        assertEquals(0, declared.getTag().getCompound("bladeState").getInt("killCount"));
        assertFalse(PureRecipePlanner.resolve(graph(node), Map.of(), List.of(demand), 30).feasible());
    }

    @Test
    void higherDownstreamThresholdPropagatesThroughEverySingleBladeUpgrade() {
        RecipeNode middle = recipe("test:middle", blade("test:middle", 0, 0),
                List.of(new IngredientSpec(ingredient("test:base", 200, 2), 1)));
        RecipeNode top = recipe("test:top", blade("test:top", 0, 0),
                List.of(new IngredientSpec(ingredient("test:middle", 800, 8), 1)));
        ImmutableRecipeGraph graph = graph(middle, top);
        IngredientRef demand = project(ingredient("test:top", 1000, 10));
        var success = PlanningThreadContext.runInBackground(() -> PureRecipePlanner.resolve(graph,
                Map.of(material(blade("test:base", 1300, 12)), 1), List.of(demand), 30));
        assertTrue(success.feasible(), success.toString());
        assertEquals(List.of(middle.recipeId(), top.recipeId()), success.steps().stream()
                .map(PureRecipePlanner.PlannedStep::recipeId).toList());
        assertNotNull(success.steps().get(0).demandedOutput());
        var failure = PureRecipePlanner.resolve(graph,
                Map.of(material(blade("test:base", 999, 10)), 1), List.of(demand), 30);
        assertFalse(failure.feasible());
        assertEquals(2, failure.steps().size(), "缺料预览仍应展开两层升级");
        assertTrue(matches(blade("test:base", 1000, 10), failure.missing().get(0)));
        assertFalse(matches(blade("test:base", 999, 10), failure.missing().get(0)));
    }

    @Test
    void executionReservationKeepsRaisedThresholdInsteadOfTakingWeakerSameNameBlade() {
        List<IngredientSpec> specs = List.of(new IngredientSpec(ingredient("test:base", 800, 8), 1));
        List<IngredientSpec> narrowed = SlashBladePlanning.requireOutput(specs,
                blade("test:result", 0, 0), blade("test:result", 1000, 10));
        Ingredient requirement = narrowed.get(0).ingredient();
        assertFalse(IngredientMatcher.test(requirement, blade("test:base", 900, 9)));
        assertTrue(IngredientMatcher.test(requirement, blade("test:base", 1200, 12)));
        assertTrue(IngredientMatcher.test(specs.get(0).ingredient(), blade("test:base", 900, 9)));
        assertEquals(1000, requirement.toJson().getAsJsonObject().getAsJsonObject("request").get("kill").getAsInt());
    }

    @Test
    void typedCandidateAndGridPreviewUseTheSameRaisedInputWithoutInventingBladeFlags() {
        Ingredient input = ingredient("test:base", 800, 8);
        ItemStack declared = blade("test:result", 0, 0);
        List<IngredientSpec> specs = List.of(new IngredientSpec(input, 1));
        Ingredient demand = ingredient("test:result", 1000, 10);
        ItemStack output = SlashBladePlanning.outputForDemand(declared, specs, demand);
        assertNotNull(output);
        assertTrue(IngredientMatcher.test(demand, output));
        List<Ingredient> grid = SlashBladePlanning.requireOutputIngredients(
                List.of(Ingredient.EMPTY, input, Ingredient.EMPTY), declared, output);
        assertSame(Ingredient.EMPTY, grid.get(0));
        assertSame(Ingredient.EMPTY, grid.get(2));
        assertFalse(IngredientMatcher.test(grid.get(1), blade("test:base", 999, 10)));
        assertTrue(IngredientMatcher.test(grid.get(1), blade("test:base", 1000, 10)));
        JsonObject brokenRequest = demand.toJson().getAsJsonObject().getAsJsonObject("request");
        JsonArray types = new JsonArray();
        types.add("broken");
        brokenRequest.add("sword_type", types);
        Ingredient broken = ingredient(brokenRequest);
        ItemStack candidate = SlashBladePlanning.outputForDemand(declared, specs, broken);
        assertNotNull(candidate);
        assertFalse(IngredientMatcher.test(broken, candidate), "提高数值不能凭空制造断刀状态");
    }

    @Test
    void multipleBladeInputsDoNotInventAnAllocationForHigherOutputRequirements() {
        List<IngredientSpec> specs = List.of(new IngredientSpec(ingredient("test:left", 800, 8), 1),
                new IngredientSpec(ingredient("test:right", 100, 1), 1));
        Ingredient demand = ingredient("test:result", 1000, 10);
        ItemStack output = SlashBladePlanning.outputForDemand(blade("test:result", 0, 0), specs, demand);
        assertNotNull(output);
        assertFalse(IngredientMatcher.test(demand, output));
        assertEquals(900, output.getTag().getCompound("bladeState").getInt("killCount"));
    }

    @Test
    void thousandsOfOtherBladeNamesAreRejectedWithoutReparsingTheirNbtForEveryDemand() {
        RecipeNode top = recipe("test:top", blade("test:top", 0, 0),
                List.of(new IngredientSpec(ingredient("test:base", 800, 8), 1)));
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        outputs.put(top.output(), List.of(top));
        for (int i = 0; i < 5000; i++) {
            MaterialRef unrelated = new MaterialRef(top.output().itemId(),
                    "{bladeState:{translationKey:\"item.noise.blade_" + i + "\",killCount:0,RepairCounter:0,proudSoul:0}}",
                    false, true);
            outputs.put(unrelated, List.of(new RecipeNode(new ResourceLocation("noise:blade_" + i), unrelated,
                    1, top.inputs(), "slashblade", new ResourceLocation("minecraft:crafting"))));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(outputs);
        var stats = PlanningLookupCache.run(() -> {
            for (int threshold : List.of(1000, 1200, 1500)) {
                IngredientRef demand = project(ingredient("test:top", threshold, 10));
                var candidates = ImmutableRecipeGraphProjector.candidates(graph,
                        demand.alternatives().get(0), demand.nbtMatchMode());
                assertEquals(1, candidates.size());
                assertEquals(top.recipeId(), candidates.get(0).recipeId());
            }
            return PlanningLookupCache.currentStats();
        });
        assertTrue(stats.nbtParses() <= 5010, stats.toString());
        assertTrue(stats.cachedNbtCharacters() <= PlanningLookupCache.DEFAULT_LIMITS.maxNbtCharacters());
    }

    static IngredientRef project(Ingredient ingredient) {
        return assertNotNullValue(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(ingredient, 1)));
    }

    private static <T> T assertNotNullValue(T value) { assertNotNull(value); return value; }

    static MaterialRef material(ItemStack stack) { return ImmutableRecipeGraphProjector.material(stack, true); }

    static boolean matches(ItemStack stack, IngredientRef demand) {
        return ImmutableRecipeGraphProjector.matchesIngredient(material(stack), demand);
    }

    static RecipeNode recipe(String id, ItemStack output, List<IngredientSpec> specs) {
        return assertNotNullValue(ImmutableRecipeGraphProjector.projectRecipe(new ResourceLocation(id), output,
                specs, "slashblade", new ResourceLocation("minecraft:crafting"), true));
    }

    static ImmutableRecipeGraph graph(RecipeNode... nodes) {
        Map<MaterialRef, List<RecipeNode>> byOutput = new LinkedHashMap<>();
        for (RecipeNode node : nodes) byOutput.put(node.output(), List.of(node));
        return new ImmutableRecipeGraph(byOutput);
    }
}
