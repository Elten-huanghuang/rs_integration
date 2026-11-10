package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 用原版物品代替符文注册项，保留标签展开后的 OR 候选及真实物料图。 */
class TagCyclePlanningTest extends BootstrapTest {
    private static final MaterialRef LIGHTNING = material(Items.AMETHYST_SHARD);
    private static final MaterialRef BLANK = material(Items.BRICK);
    private static final MaterialRef SCARLET = material(Items.REDSTONE);
    private static final MaterialRef COST_B = material(Items.COAL);
    private static final MaterialRef EXTRA = material(Items.DIAMOND);
    private static final ResourceLocation BLANK_RECIPE = id("blank_rune");
    private static final ResourceLocation SCARLET_RECIPE = id("scarlet_rune");

    @ParameterizedTest
    @ValueSource(ints = {1, 64})
    void stockedSiblingBuildsTheWholeChainAndItsDisplayGraph(int count) {
        ImmutableRecipeGraph graph = runes(List.of(SCARLET, BLANK, LIGHTNING));
        Map<MaterialRef, Long> available = stock(count);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, available,
                List.of(input(SCARLET, count)), 64);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(BLANK_RECIPE, SCARLET_RECIPE),
                result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
        assertTrue(result.steps().stream().allMatch(step -> step.batches() == count));
        Map<StackKey, Long> stacks = Map.of(new StackKey(Items.AMETHYST_SHARD, null), (long) count,
                new StackKey(Items.COAL, null), (long) count, new StackKey(Items.DIAMOND, null), (long) count);
        var executionGraph = PurePlanGraphAdapter.toOutputGraph(result, stacks);
        CraftPlanValidator.validate(executionGraph);
        PlanGraphView display = PlanGraphView.from(executionGraph);
        assertEquals(2, display.nodes().size());
        assertTrue(display.unresolved().isEmpty());
        assertTrue(executionGraph.allocations().stream().anyMatch(allocation ->
                allocation.source() instanceof MaterialSource.InitialPool
                        && allocation.material().item() == Items.AMETHYST_SHARD));
        PlanResponse response = new PlanResponse(true, "", new ItemStack(Items.REDSTONE),
                display.nodes().stream().map(PlanGraphView.NodeView::asPlanStep).toList(), Map.of(),
                List.of(), SCARLET_RECIPE.toString(), null, null, 0, 0, 0, List.of(), count,
                null, null, null, 0, false, false, false, null, Set.of(), Map.of(), null, display);
        PlanTreeModel tree = PlanTreeModel.from(response);
        assertFalse(tree.truncated());
        assertEquals(SCARLET_RECIPE, tree.root.step.recipeId());
        PlanTreeNode blankNode = tree.root.children.stream().filter(child -> child.step != null
                && child.step.recipeId().equals(BLANK_RECIPE)).findFirst().orElseThrow();
        assertFalse(blankNode.cycle);
        assertTrue(blankNode.children.stream().anyMatch(child -> child.initialSource
                && child.displayStack.is(Items.AMETHYST_SHARD) && child.amount == count));
    }

    @Test
    void selectedTerminalDoesNotRejectAnIngredientWhoseTagContainsTheTerminalOutput() {
        ImmutableRecipeGraph graph = runes(List.of(LIGHTNING, SCARLET, BLANK));
        RecipeNode target = graph.recipesById().get(SCARLET_RECIPE);
        var result = AsyncPurePlanningService.resolveInScope(graph, stock(1), target,
                1, 64, 2_000, 256, Long.MAX_VALUE, Map.of(), PlanningPreferences.empty());
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(BLANK_RECIPE), result.steps().stream()
                .map(PureRecipePlanner.PlannedStep::recipeId).toList());
    }

    @Test
    void routeInspectionKeepsTheValidTagChainOnThePurePlanner() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(runes(List.of(SCARLET, BLANK, LIGHTNING)).recipesByOutput());
        MaterialRef bundle = material(Items.CHEST);
        RecipeNode target = new RecipeNode(id("rune_bundle"), bundle, 1, List.of(input(SCARLET, 1)));
        recipes.put(bundle, List.of(target));
        var result = PureDemandTreeInspector.inspect(new ImmutableRecipeGraph(recipes), stock(1), target.recipeId(), 1);
        assertEquals(PureDemandTreeInspector.Status.COMPLETE, result.status(), result.toString());
    }

    @Test
    void missingAuxiliaryMaterialStillShowsTheObtainableBlankRune() {
        ImmutableRecipeGraph graph = runes(List.of(SCARLET, BLANK, LIGHTNING));
        var result = AsyncPurePlanningService.resolveInScope(graph,
                Map.of(LIGHTNING, 1L, COST_B, 1L), graph.recipesById().get(SCARLET_RECIPE),
                1, 64, 2_000, 256, Long.MAX_VALUE, Map.of(), PlanningPreferences.empty());
        assertFalse(result.feasible());
        assertTrue(result.steps().stream().anyMatch(step -> step.recipeId().equals(BLANK_RECIPE)), result.toString());
        assertTrue(result.missing().stream().anyMatch(missing -> missing.alternatives().equals(List.of(EXTRA))), result.toString());
    }

    @Test
    void aCraftableSiblingAlsoEscapesTheTagCycle() {
        var recipes = new LinkedHashMap<>(runes(List.of(SCARLET, BLANK, LIGHTNING)).recipesByOutput());
        MaterialRef raw = material(Items.IRON_INGOT);
        recipes.put(LIGHTNING, List.of(new RecipeNode(id("lightning_rune"), LIGHTNING, 1,
                List.of(input(raw, 1)))));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(recipes),
                Map.of(raw, 1L, COST_B, 1L, EXTRA, 1L), List.of(input(SCARLET, 1)), 64);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(id("lightning_rune"), BLANK_RECIPE, SCARLET_RECIPE),
                result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
    }

    @Test
    void noRuneSeedCannotInventTheFirstRune() {
        var result = PureRecipePlanner.resolve(runes(List.of(SCARLET, BLANK, LIGHTNING)),
                Map.of(COST_B, 64L, EXTRA, 64L), List.of(input(SCARLET, 1)), 64, 2_000, 256);
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status(), result.toString());
        assertFalse(result.feasible());
    }

    @Test
    void exactNbtAlternativesOfTheSameItemRemainSeparateSeeds() {
        MaterialRef lightning = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.PAPER), "{rune:\"lightning\"}");
        MaterialRef blank = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.PAPER), "{rune:\"blank\"}");
        MaterialRef scarlet = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.PAPER), "{rune:\"scarlet\"}");
        RecipeNode reset = new RecipeNode(BLANK_RECIPE, blank, 1,
                List.of(new IngredientRef(List.of(scarlet, blank, lightning), 1, NbtMatchMode.EXACT), input(COST_B, 1)));
        RecipeNode upgrade = new RecipeNode(SCARLET_RECIPE, scarlet, 1, List.of(input(blank, 1), input(EXTRA, 1)));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(Map.of(blank, List.of(reset), scarlet, List.of(upgrade))),
                Map.of(lightning, 1L, COST_B, 1L, EXTRA, 1L), List.of(input(scarlet, 1)), 64);
        assertTrue(result.feasible(), result.toString());
        assertEquals(2, result.steps().size());
    }

    @Test
    void anOptionalTagMemberDoesNotMakeTheSelectedTargetAReverseConversion() {
        var recipes = new LinkedHashMap<>(runes(List.of(SCARLET, BLANK, LIGHTNING)).recipesByOutput());
        RecipeNode target = recipes.get(SCARLET).get(0);
        RecipeNode alternative = new RecipeNode(id("other_scarlet"), SCARLET, 1,
                List.of(input(material(Items.EMERALD), 1)));
        recipes.put(SCARLET, List.of(target, alternative));
        assertFalse(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(target,
                new ImmutableRecipeGraph(recipes), stock(1)));
    }

    private static ImmutableRecipeGraph runes(List<MaterialRef> family) {
        // 经过实际 Ingredient 投影入口，验证标签展开后的多候选不会在投影时丢失。
        IngredientRef tagInput = ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                Ingredient.of(family.stream().map(material ->
                        new ItemStack(BuiltInRegistries.ITEM.get(material.itemId())))), 1));
        assertNotNull(tagInput);
        assertEquals(Set.copyOf(family), Set.copyOf(tagInput.alternatives()));
        RecipeNode reset = new RecipeNode(BLANK_RECIPE, BLANK, 1,
                List.of(tagInput, input(COST_B, 1)));
        RecipeNode upgrade = new RecipeNode(SCARLET_RECIPE, SCARLET, 1,
                List.of(input(BLANK, 1), input(EXTRA, 1)));
        return new ImmutableRecipeGraph(Map.of(BLANK, List.of(reset), SCARLET, List.of(upgrade)));
    }

    private static Map<MaterialRef, Long> stock(int count) {
        return Map.of(LIGHTNING, (long) count, COST_B, (long) count, EXTRA, (long) count);
    }

    private static IngredientRef input(MaterialRef material, long count) {
        return new IngredientRef(List.of(material), count);
    }

    private static MaterialRef material(Item item) {
        return new MaterialRef(BuiltInRegistries.ITEM.getKey(item), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("tag_cycle_test", path);
    }
}
