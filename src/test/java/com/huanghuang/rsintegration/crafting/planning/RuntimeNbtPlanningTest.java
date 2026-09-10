package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.*;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeNbtPlanningTest extends BootstrapTest {
    private static final MaterialRef RAW = material("avaritia:diamond_lattice");
    private static final MaterialRef DUST = material("forbidden_arcanus:arcane_crystal_dust");
    private static final MaterialRef EMBER = material("embers:ember_crystal");
    private static final MaterialRef SPIRIT = material("malum:infernal_spirit");
    private static final MaterialRef REMNANT = material("simplyswords:empowered_remnant");
    private static final MaterialRef UNKNOWN = new MaterialRef(REMNANT.itemId(), "", true);
    private static final MaterialRef CONTAINED = material("simplyswords:contained_remnant");
    private static final MaterialRef EMBLEM = material("confluence:destroyer_emblem");
    private static final ResourceLocation INFUSION = new ResourceLocation(
            "malum:spirit_infusion/simplyswords_empowered_remnant");

    @Test
    void projectionDistinguishesUnknownNbtFromAnActuallyTaglessStack() {
        ItemStack output = new ItemStack(Items.DIAMOND, 4);
        output.getOrCreateTag().putString("displayOnly", "not guaranteed");
        var node = ImmutableRecipeGraphProjector.projectRecipe(INFUSION, output,
                List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1)),
                "malum", new ResourceLocation("malum:spirit_infusion"), false);
        assertNotNull(node);
        assertTrue(node.output().runtimeNbt());
        assertEquals("", node.output().nbt());
        assertEquals(4, node.outputCount());
        assertNotEquals(new MaterialRef(node.output().itemId(), ""), node.output());
        assertTrue(output.hasTag(), "Projection must not mutate the real result stack");
    }

    @Test
    void emblemChainPlansInfusionAndScalesFourItemBatches() {
        var graph = graph();
        var stock = stock(2);
        var prepared = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
        assertTrue(prepared.recipesById().get(INFUSION).output().runtimeNbt());
        var result = PureRecipePlanner.resolve(prepared, stock, List.of(any(EMBLEM, 17)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(2, result.steps().stream().filter(s -> s.recipeId().equals(INFUSION))
                .mapToInt(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(3, result.remaining().get(UNKNOWN));
        assertFalse(result.remaining().containsKey(REMNANT));
        assertEquals(PureDemandTreeInspector.Status.COMPLETE,
                PureDemandTreeInspector.inspect(graph, stock, EMBLEM.itemId(), 1).status());
    }

    @ParameterizedTest
    @EnumSource(value = NbtMatchMode.class, names = {"EXACT", "PARTIAL"})
    void unknownProducerCannotSatisfySpecificNbt(NbtMatchMode mode) {
        for (String nbt : List.of("", "{Unbreakable:1b}")) {
            var demand = new IngredientRef(List.of(new MaterialRef(REMNANT.itemId(), nbt)), 1, mode);
            assertTrue(ImmutableRecipeGraphProjector.candidates(graph(), demand.alternatives().get(0), mode).isEmpty());
            assertFalse(PureRecipePlanner.resolve(graph(), stock(1), List.of(demand), 20).feasible());
            assertFalse(ImmutableRecipeGraphProjector.matchesIngredient(UNKNOWN, demand));
        }
    }

    @Test
    void surplusFromAnEarlierAnyDemandDoesNotBecomeExactTaglessStock() {
        var strict = new IngredientRef(List.of(REMNANT), 1, NbtMatchMode.EXACT);
        var result = PureRecipePlanner.resolve(graph(), stock(1), List.of(any(REMNANT, 1), strict), 20);
        assertFalse(result.feasible());
        assertTrue(result.missing().stream().anyMatch(m -> m.nbtMatchMode() == NbtMatchMode.EXACT
                && m.alternatives().contains(REMNANT)), result.toString());
    }

    @Test
    void unknownCatalystCannotBeReusedAsExactTaglessMaterial() {
        var catalyst = new IngredientRef(List.of(REMNANT), 1, NbtMatchMode.ANY, DemandRole.CATALYST);
        var strict = new IngredientRef(List.of(REMNANT), 1, NbtMatchMode.EXACT);
        assertFalse(PureRecipePlanner.resolve(graph(), stock(1), List.of(catalyst, strict), 20).feasible());
        var root = new RecipeNode(EMBLEM.itemId(), EMBLEM, 1, List.of(catalyst, strict));
        var recipes = new java.util.HashMap<>(graph().recipesByOutput());
        recipes.put(EMBLEM, List.of(root));
        assertEquals(PureDemandTreeInspector.Status.MISSING_MATERIALS,
                PureDemandTreeInspector.inspect(new ImmutableRecipeGraph(recipes), stock(1),
                        root.recipeId(), 1).status());
    }

    @Test
    void existingStockCanCombineWithOneNewBatch() {
        var stock = new java.util.HashMap<>(stock(1));
        stock.put(REMNANT, 1);
        var result = PureRecipePlanner.resolve(graph(), stock, List.of(any(REMNANT, 5)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(1, result.steps().get(0).batches());
    }

    @Test
    void laterDependencyMayProduceTheSameRuntimeOutputAgain() {
        var stock = stock(2);
        var result = PureRecipePlanner.resolve(graph(), stock,
                List.of(any(REMNANT, 4), any(CONTAINED, 4)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(2, result.steps().stream().filter(s -> s.recipeId().equals(INFUSION))
                .mapToInt(PureRecipePlanner.PlannedStep::batches).sum());
    }

    @Test
    void treeReusesBatchSurplusAcrossDifferentDependencies() {
        var root = new RecipeNode(EMBLEM.itemId(), EMBLEM, 1,
                List.of(any(REMNANT, 1), any(CONTAINED, 4)));
        var recipes = new java.util.HashMap<>(graph().recipesByOutput());
        recipes.put(EMBLEM, List.of(root));
        assertEquals(PureDemandTreeInspector.Status.COMPLETE,
                PureDemandTreeInspector.inspect(new ImmutableRecipeGraph(recipes), stock(1),
                        root.recipeId(), 1).status());
    }

    @Test
    void actualTaggedInventoryRemainsUsableForExactDemand() {
        var actual = new MaterialRef(REMNANT.itemId(), "{owner:1}");
        var strict = new IngredientRef(List.of(actual), 1, NbtMatchMode.EXACT);
        var result = PureRecipePlanner.resolve(graph(), Map.of(actual, 1), List.of(strict), 20);
        assertTrue(result.feasible());
        assertTrue(result.steps().isEmpty());
    }

    private static ImmutableRecipeGraph graph() {
        var infusion = new RecipeNode(INFUSION, UNKNOWN, 4,
                List.of(any(RAW, 1), any(DUST, 1), any(EMBER, 1), any(SPIRIT, 4)),
                "malum", new ResourceLocation("malum:spirit_infusion"));
        var contained = new RecipeNode(new ResourceLocation("crafttweaker:simplyswords.contained_remnant"),
                CONTAINED, 4, List.of(any(REMNANT, 1)));
        var emblem = new RecipeNode(EMBLEM.itemId(), EMBLEM, 1, List.of(any(CONTAINED, 1)),
                "confluence", new ResourceLocation("confluence:workshop"));
        return new ImmutableRecipeGraph(Map.of(UNKNOWN, List.of(infusion),
                CONTAINED, List.of(contained), EMBLEM, List.of(emblem)));
    }

    private static Map<MaterialRef, Integer> stock(int batches) {
        return Map.of(RAW, batches, DUST, batches, EMBER, batches, SPIRIT, 4 * batches);
    }

    private static IngredientRef any(MaterialRef item, int count) {
        return new IngredientRef(List.of(item), count, NbtMatchMode.ANY);
    }

    private static MaterialRef material(String id) {
        return new MaterialRef(new ResourceLocation(id), "");
    }
}
