package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnbreakableSmithingChainTest extends BootstrapTest {
    private static final String REQUIRED = "{Unbreakable:1}";
    private static final String STOCKED = "{Damage:0,Unbreakable:1b}";
    private static final String MODIFIED = "{Unbreakable:1b,RepairCost:8,Damage:0,itemModifier:\"celestial_forge:vicious\"}";
    private static final String[] SWORDS = {
            "minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
            "minecraft:diamond_sword", "minecraft:netherite_sword",
            "callfromthedepth_:immemorialsword", "callfromthedepth_:soul_blade"};
    private static final String[] UPGRADES = {
            "minecraft:stone_sword_smithing", "minecraft:iron_sword_smithing",
            "minecraft:diamond_sword_smithing", "minecraft:netherite_sword_smithing",
            "callfromthedepth_:swordcraft", "callfromthedepth_:soulblade"};
    private static final MaterialRef TEMPLATE = material("test:template", "");
    private static final MaterialRef ADDITION = material("test:addition", "");
    private static final MaterialRef AWAKENED = material("yuusha:awakened_ichorium_sword", REQUIRED);
    private static final MaterialRef FINAL = material("avaritia:infinity_sword", "");
    private static final ResourceLocation FINAL_RECIPE = new ResourceLocation(
            "wizards_reborn:arcane_workbench/avaritia_infinity_sword");

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void existingUnbreakableSwordReachesTheRealSoulBladeAndFinalWorkbench(int start) {
        ImmutableRecipeGraph graph = graph(NbtMatchMode.PARTIAL);
        Map<MaterialRef, Integer> stock = stock(start, STOCKED);
        PureRecipePlanner.Result plan = plan(graph, stock);
        PureDemandTreeInspector.Result tree = PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1);

        assertTrue(plan.feasible(), plan.toString());
        plan.steps().stream().filter(step -> !step.recipeId().getNamespace().equals("crafttweaker"))
                .forEach(step -> {
                    assertNotNull(step.demandedOutput());
                    assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(
                            REQUIRED, step.demandedOutput().nbt()));
                });
        assertEquals(PureDemandTreeInspector.Status.COMPLETE, tree.status());
        assertEquals(7 - start, plan.steps().size());
        assertTrue(plan.steps().stream().anyMatch(step -> step.recipeId().toString()
                .equals("callfromthedepth_:soulblade")));
        assertFalse(plan.remaining().containsKey(material(SWORDS[start], STOCKED)));
        assertNotNull(graph.recipesById().get(new ResourceLocation("callfromthedepth_:soulblade")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{Damage:3,Unbreakable:1b}", "{Damage:0,Unbreakable:0b}"})
    void ordinaryOrDamagedSwordCannotAcquirePristineUnbreakableState(String nbt) {
        ImmutableRecipeGraph graph = graph(NbtMatchMode.PARTIAL);
        Map<MaterialRef, Integer> stock = stock(0, nbt);
        assertFalse(plan(graph, stock).feasible());
        assertEquals(PureDemandTreeInspector.Status.MISSING_MATERIALS,
                PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void lookupOptimizationKeepsTheFullModifiedSwordPlanAndTree(int start) {
        ImmutableRecipeGraph graph = graph(NbtMatchMode.PARTIAL);
        Map<MaterialRef, Integer> stock = stock(start, MODIFIED);
        var uncachedLimits = new PlanningLookupCache.Limits(0, 0, 0, 0);
        PureRecipePlanner.Result expected = PlanningLookupCache.run(uncachedLimits, () -> plan(graph, stock));
        PureRecipePlanner.Result actual = plan(graph, stock);
        assertTrue(actual.feasible(), actual.toString());
        assertEquals(expected, actual);
        PureDemandTreeInspector.Result expectedTree = PlanningLookupCache.run(uncachedLimits,
                () -> PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1));
        assertEquals(PureDemandTreeInspector.Status.COMPLETE, expectedTree.status());
        assertEquals(expectedTree, PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1));
        assertEquals(7 - start, actual.steps().size());
    }

    @Test
    void exactChainAlsoAcceptsEquivalentDefaultDamageWithoutInventingOrdinaryStock() {
        ImmutableRecipeGraph graph = graph(NbtMatchMode.EXACT);
        Map<MaterialRef, Integer> stock = stock(0, STOCKED);
        assertTrue(plan(graph, stock).feasible());
        List<IngredientRef> roots = new ArrayList<>(graph.recipesById().get(FINAL_RECIPE).inputs());
        roots.add(any(material(SWORDS[0], ""), 1));
        assertFalse(PureRecipePlanner.resolve(graph, stock, roots, 30).feasible());
    }

    @Test
    void smithingSpecializationPinsTheBaseSlotNotTheTemplateOrAddition() {
        RecipeNode soul = graph(NbtMatchMode.PARTIAL).recipesById()
                .get(new ResourceLocation("callfromthedepth_:soulblade"));
        RecipeNode tagged = ImmutableRecipeGraphProjector.withDemandedOutput(soul,
                material(SWORDS[6], REQUIRED), NbtMatchMode.EXACT);
        assertNotNull(tagged);
        assertEquals(REQUIRED, tagged.output().nbt());
        assertEquals(REQUIRED, tagged.inputs().get(1).alternatives().get(0).nbt());
        assertEquals(soul.inputs().get(0), tagged.inputs().get(0));
        assertEquals(soul.inputs().get(2), tagged.inputs().get(2));
    }

    @Test
    void ordinarySwordAlongsideTheUnbreakableSwordIsNotConsumedByTheUpgradeChain() {
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>(stock(1, STOCKED));
        MaterialRef ordinary = material(SWORDS[1], "");
        stock.put(ordinary, 1);
        var result = plan(graph(NbtMatchMode.EXACT), stock);
        assertTrue(result.feasible(), result.toString());
        assertEquals(1, result.remaining().get(ordinary));
        assertFalse(result.remaining().containsKey(material(SWORDS[1], STOCKED)));
    }

    @Test
    void exactCandidateDiscoveryAcceptsEquivalentStaticOutputNbt() {
        MaterialRef output = material("minecraft:stone_sword", STOCKED);
        RecipeNode producer = new RecipeNode(new ResourceLocation("test:tagged_output"),
                output, 1, List.of(any(ADDITION, 1)));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(Map.of(output, List.of(producer))),
                Map.of(ADDITION, 1), List.of(new IngredientRef(
                        List.of(material("minecraft:stone_sword", REQUIRED)), 1, NbtMatchMode.EXACT)), 8);
        assertTrue(result.feasible(), result.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{Unbreakable:1}", "{Damage:0,Unbreakable:1}", "{Damage:0,Unbreakable:1b}"})
    void physicalStrictMatcherAndValueMatcherAgree(String expectedNbt) throws Exception {
        ItemStack expected = new ItemStack(Items.WOODEN_SWORD);
        expected.setTag(TagParser.parseTag(expectedNbt));
        ItemStack actual = new ItemStack(Items.WOODEN_SWORD);
        actual.setTag(TagParser.parseTag(STOCKED));
        assertTrue(IngredientMatcher.test(StrictNBTIngredient.of(expected), actual));
        assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(expectedNbt, STOCKED));
        actual.setDamageValue(2);
        assertFalse(IngredientMatcher.test(StrictNBTIngredient.of(expected), actual));
        assertFalse(ImmutableRecipeGraphProjector.partialNbtMatches(expectedNbt, actual.getTag().toString()));
    }

    @Test
    void exactNbtIsNotSubsetMatchingAndPartialNbtStillRejectsDamagedTools() {
        assertFalse(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED,
                "{Damage:0,Unbreakable:1b,owner:1}"));
        assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED,
                "{Damage:0,Unbreakable:1b,owner:1}"));
        assertFalse(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED,
                "{Damage:1,Unbreakable:1}"));
        assertFalse(ImmutableRecipeGraphProjector.exactNbtMatches("{level:1}", "{level:1b}"));
        assertFalse(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED,
                "{Damage:broken,Unbreakable:1}"));
    }

    private static PureRecipePlanner.Result plan(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> stock) {
        return PureRecipePlanner.resolve(graph, stock, graph.recipesById().get(FINAL_RECIPE).inputs(), 30);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void combinedBackgroundRoutePreservesFullModifiedSwordChain(int start) {
        var source = graph(NbtMatchMode.PARTIAL);
        var paper = material("minecraft:paper", "");
        var iron = material("minecraft:iron_ingot", "");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        source.recipesByOutput().forEach((output, nodes) -> recipes.put(output, nodes.stream()
                .map(node -> new RecipeNode(node.recipeId(), node.output(), node.outputCount(),
                        node.inputs().stream().map(input -> new IngredientRef(input.alternatives().stream()
                                .map(material -> material.equals(TEMPLATE) ? paper
                                        : material.equals(ADDITION) ? iron : material).toList(),
                                input.count(), input.nbtMatchMode(), input.role())).toList(),
                        node.modTypeId(), node.recipeTypeId())).toList()));
        var mapped = new ImmutableRecipeGraph(recipes);
        var available = Map.of(new com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey(
                        start == 0 ? Items.WOODEN_SWORD : Items.STONE_SWORD, MODIFIED), 1,
                new com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey(Items.PAPER, null), 6,
                new com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey(Items.IRON_INGOT, null), 6);
        var snapshot = new PlanningSnapshot(java.util.UUID.randomUUID(), 1, 1, FINAL_RECIPE,
                available, Map.of(), mapped, "network", "binding", false);
        var stock = ImmutableRecipeGraphProjector.projectAvailability(available);
        var routing = new AsyncPurePlanningService.RouteInputs(stock, 512,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        var combined = PlanningThreadContext.runInBackground(() -> AsyncPurePlanningService.computeRouted(
                snapshot, routing, 1, 30, 65536, 8192, 1500));
        assertEquals(PureDemandTreeInspector.inspect(mapped, stock, FINAL_RECIPE, 1), combined.inspection());
        assertEquals(plan(ImmutableRecipeGraphProjector.bindAvailability(mapped, stock), stock), combined.plan());
        assertTrue(combined.plan().feasible());
        assertTrue(combined.plan().steps().stream().anyMatch(step -> step.recipeId().toString()
                .equals("callfromthedepth_:soulblade")));
        assertFalse(PlanningLookupCache.isActive());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void preparationReusePreservesModifiedSwordPlanningAndInspection(int start) {
        ImmutableRecipeGraph graph = graph(NbtMatchMode.PARTIAL);
        Map<MaterialRef, Integer> stock = stock(start, MODIFIED);
        var expectedPlan = PlanningLookupCache.run(new PlanningLookupCache.Limits(0, 0, 0, 0),
                () -> plan(graph, stock));
        var expectedTree = PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1);
        PlanningLookupCache.run(() -> {
            for (int repetition = 0; repetition < 3; repetition++) {
                assertEquals(expectedPlan, plan(graph, stock));
                assertEquals(expectedTree, PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1));
            }
            assertTrue(expectedPlan.feasible());
            assertEquals(PureDemandTreeInspector.Status.COMPLETE, expectedTree.status());
            var stats = PlanningLookupCache.preparationStats(PlanningLookupCache.PreparationStage.SMITHING);
            assertEquals(1, stats.builds());
            assertEquals(5, stats.hits());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void modifiedSwordKeepsRepairCostAndModifierThroughEveryPlannedUpgrade(int start) throws Exception {
        var stock = stock(start, MODIFIED);
        var graph = graph(NbtMatchMode.PARTIAL);
        var plan = plan(graph, stock);
        assertTrue(plan.feasible(), plan.toString());
        assertEquals(PureDemandTreeInspector.Status.COMPLETE,
                PureDemandTreeInspector.inspect(graph, stock, FINAL_RECIPE, 1).status());
        assertFalse(plan.remaining().containsKey(material(SWORDS[start], MODIFIED)));
        for (var step : plan.steps()) {
            if (step.demandedOutput() == null) continue;
            var state = TagParser.parseTag(step.demandedOutput().nbt());
            assertEquals(8, state.getInt("RepairCost"));
            assertEquals("celestial_forge:vicious", state.getString("itemModifier"));
            assertTrue(state.getBoolean("Unbreakable"));
            assertEquals(0, state.getInt("Damage"));
        }
        var bound = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
        assertTrue(plan(bound, stock).feasible());
        assertEquals(graph.recipesById().get(new ResourceLocation(UPGRADES[start])).output(),
                bound.recipesById().get(new ResourceLocation(UPGRADES[start])).output());
    }

    @Test
    void modifiedSwordStillCannotSatisfyAnExactBareStateOrASecondPhysicalDemand() {
        var stock = stock(0, MODIFIED);
        assertFalse(plan(graph(NbtMatchMode.EXACT), stock).feasible());
        assertFalse(plan(graph(NbtMatchMode.PARTIAL), stock(0, MODIFIED.replace("Damage:0", "Damage:2"))).feasible());
        var graph = graph(NbtMatchMode.PARTIAL);
        var roots = new ArrayList<>(graph.recipesById().get(FINAL_RECIPE).inputs());
        roots.add(any(material(SWORDS[0], ""), 1));
        assertFalse(PureRecipePlanner.resolve(graph, stock, roots, 30).feasible());
    }

    @Test
    void templateOnlyCandidateCannotEraseExtraStateFromAPartialBase() {
        var graph = graph(NbtMatchMode.PARTIAL);
        var recipe = graph.recipesById().get(new ResourceLocation(UPGRADES[0]));
        var specialized = ImmutableRecipeGraphProjector.withDemandedOutput(recipe,
                material(SWORDS[1], REQUIRED), NbtMatchMode.PARTIAL);
        assertNotNull(specialized);
        assertEquals(NbtMatchMode.EXACT, specialized.inputs().get(1).nbtMatchMode());
        assertFalse(ImmutableRecipeGraphProjector.matchesIngredient(
                material(SWORDS[0], MODIFIED), specialized.inputs().get(1)));
    }

    @Test
    void physicalPartialMatchingRetainsModifierAndRejectsDamage() throws Exception {
        ItemStack actual = new ItemStack(Items.WOODEN_SWORD);
        actual.setTag(TagParser.parseTag(MODIFIED));
        var partial = net.minecraftforge.common.crafting.PartialNBTIngredient.of(
                Items.WOODEN_SWORD, TagParser.parseTag(REQUIRED));
        assertEquals(NbtMatchMode.PARTIAL, ImmutableRecipeGraphProjector.nbtMatchMode(partial));
        assertTrue(IngredientMatcher.test(partial, actual));
        assertEquals(TagParser.parseTag(MODIFIED), actual.getTag());
        actual.setDamageValue(1);
        assertFalse(IngredientMatcher.test(partial, actual));
    }

    private static Map<MaterialRef, Integer> stock(int start, String nbt) {
        return Map.of(material(SWORDS[start], nbt), 1, TEMPLATE, 6, ADDITION, 6);
    }

    private static ImmutableRecipeGraph graph(NbtMatchMode mode) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int index = 0; index < UPGRADES.length; index++) {
            MaterialRef output = material(SWORDS[index + 1], "");
            recipes.put(output, List.of(new RecipeNode(new ResourceLocation(UPGRADES[index]), output, 1,
                    List.of(any(TEMPLATE, 1), any(material(SWORDS[index], ""), 1), any(ADDITION, 1)),
                    "smithing", new ResourceLocation("minecraft:smithing"))));
        }
        recipes.put(AWAKENED, List.of(new RecipeNode(new ResourceLocation("crafttweaker:yuusha.awakened_ichorium_sword"),
                AWAKENED, 1, List.of(new IngredientRef(List.of(material(SWORDS[6], REQUIRED)), 1, mode)))));
        recipes.put(FINAL, List.of(new RecipeNode(FINAL_RECIPE, FINAL, 1,
                List.of(any(material(AWAKENED.itemId().toString(), ""), 1)), "wr_arcane_workbench",
                new ResourceLocation("wizards_reborn:arcane_workbench"))));
        return new ImmutableRecipeGraph(recipes);
    }

    private static IngredientRef any(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count, NbtMatchMode.ANY);
    }

    private static MaterialRef material(String id, String nbt) {
        return new MaterialRef(new ResourceLocation(id), nbt);
    }
}
