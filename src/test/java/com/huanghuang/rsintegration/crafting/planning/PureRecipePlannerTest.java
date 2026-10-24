package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PureRecipePlannerTest extends BootstrapTest {
    private static final MaterialRef LOG = material("log");
    private static final MaterialRef PLANK = material("plank");
    private static final MaterialRef STICK = material("stick");

    @Test
    void recursivelyPlansAndScalesBatchesWithoutMinecraftObjects() {
        RecipeNode planks = recipe("planks", PLANK, 4, ingredient(LOG, 1));
        RecipeNode sticks = recipe("sticks", STICK, 4, ingredient(PLANK, 2));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                PLANK, List.of(planks), STICK, List.of(sticks)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(LOG, 2),
                List.of(ingredient(STICK, 8)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("planks"), 1),
                new PureRecipePlanner.PlannedStep(id("sticks"), 2)), result.steps());
    }

    @Test
    void prefersDirectMetalIngotDecompositionOverEquivalentProcessingChain() {
        MaterialRef ingot = material("metal_ingot");
        MaterialRef nugget = material("metal_nugget");
        MaterialRef tool = material("metal_tool");

        RecipeNode direct = recipe("decompose_ingot", nugget, 9, ingredient(ingot, 1));
        RecipeNode makeTool = recipe("make_tool", tool, 1, ingredient(ingot, 1));
        RecipeNode processTool = recipe("process_tool", nugget, 1, ingredient(tool, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                nugget, List.of(processTool, direct), tool, List.of(makeTool)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(ingot, 1), List.of(ingredient(nugget, 1)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("decompose_ingot"), 1)),
                result.steps());
    }

    @Test
    void forcedRecipeWinsWithoutRemovingAlternativeFromTheGraph() {
        MaterialRef target = material("forced_target");
        MaterialRef firstInput = material("forced_input_a");
        MaterialRef secondInput = material("forced_input_b");
        RecipeNode first = recipe("forced_recipe_a", target, 1,
                ingredient(firstInput, 1));
        RecipeNode second = recipe("forced_recipe_b", target, 1,
                ingredient(secondInput, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(target, List.of(first, second)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(firstInput, 1, secondInput, 1),
                List.of(ingredient(target, 1)), 20, 1024, 100,
                Long.MAX_VALUE, System::nanoTime, Set.of(),
                Map.of(target.itemId(), id("forced_recipe_b")));

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("forced_recipe_b"), 1)), result.steps());
        assertEquals(2, graph.recipesByOutput().get(target).size());
    }

    @Test
    void forcedRecipeIsHardAndDoesNotSilentlyFallBackToAnotherCandidate() {
        MaterialRef target = material("hard_forced_target");
        MaterialRef availableInput = material("hard_forced_available_input");
        MaterialRef missingInput = material("hard_forced_missing_input");
        RecipeNode fallback = recipe("hard_forced_fallback", target, 1,
                ingredient(availableInput, 1));
        RecipeNode forced = recipe("hard_forced_selected", target, 1,
                ingredient(missingInput, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(target, List.of(fallback, forced)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(availableInput, 1), List.of(ingredient(target, 1)),
                20, 1024, 100, Long.MAX_VALUE, System::nanoTime, Set.of(),
                Map.of(target.itemId(), forced.recipeId()));

        assertFalse(result.feasible(), result.toString());
        assertEquals(PureRecipePlanner.FailureReason.MISSING_INPUTS,
                result.failureReason(), result.toString());
        assertTrue(result.steps().stream().noneMatch(step ->
                step.recipeId().equals(fallback.recipeId())), result.steps().toString());
    }

    @Test
    void noRecipeConsumesStockAndNeverFallsBackToARecursiveProducer() {
        MaterialRef target = material("stock_only_target");
        MaterialRef input = material("stock_only_input");
        RecipeNode producer = recipe("stock_only_producer", target, 1,
                ingredient(input, 1));
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of(target.itemId().toString(), RecipeChoice.NoRecipe.INSTANCE),
                Map.of(), Set.of());

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(producer))),
                Map.of(target, 1, input, 1), List.of(ingredient(target, 2)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime, Set.of(), Map.of(),
                preferences);

        assertFalse(result.feasible(), result.toString());
        assertTrue(result.steps().isEmpty(), result.steps().toString());
        assertEquals(1, result.missing().get(0).count());
    }

    @Test
    void savedNormalRecipeIsSoftAndFallsBackWhenItsInputsAreMissing() {
        MaterialRef target = material("saved_soft_target");
        MaterialRef availableInput = material("saved_soft_available");
        MaterialRef missingInput = material("saved_soft_missing");
        RecipeNode fallback = recipe("saved_soft_fallback", target, 1,
                ingredient(availableInput, 1));
        RecipeNode preferred = recipe("saved_soft_preferred", target, 1,
                ingredient(missingInput, 1));
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of(target.itemId().toString(),
                        new RecipeChoice.Normal(preferred.recipeId())),
                Map.of(), Set.of());

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(fallback, preferred))),
                Map.of(availableInput, 1), List.of(ingredient(target, 1)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime, Set.of(), Map.of(),
                preferences);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                fallback.recipeId(), 1)), result.steps());
    }

    @Test
    void excludedRecipeKeepsAlternativeAvailableToThePlanner() {
        MaterialRef target = material("excluded_target");
        MaterialRef input = material("excluded_input");
        RecipeNode excluded = recipe("excluded_recipe", target, 1,
                ingredient(input, 1));
        RecipeNode usable = recipe("usable_alternative", target, 1,
                ingredient(input, 1));
        PlanningPreferences preferences = new PlanningPreferences(Map.of(), Map.of(),
                Set.of(excluded.recipeId()));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(excluded, usable))),
                Map.of(input, 1), List.of(ingredient(target, 1)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime, Set.of(), Map.of(),
                preferences);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(usable.recipeId(), 1)),
                result.steps());
    }

    @Test
    void missingFallbackIsNotReportedAsCycleBecauseAnExcludedRecipeWouldHaveLooped() {
        MaterialRef ingot = material("crystal_matrix_ingot");
        MaterialRef block = material("crystal_matrix");
        MaterialRef lattice = material("diamond_lattice");
        MaterialRef star = material("nether_star");
        RecipeNode excludedNormal = recipe("crystal_matrix_ingot_normal", ingot, 1,
                ingredient(lattice, 3), ingredient(star, 3));
        RecipeNode decompression = recipe("crystal_matrix_ingot_from_block", ingot, 9,
                ingredient(block, 1));
        RecipeNode compression = recipe("crystal_matrix", block, 1,
                ingredient(ingot, 9));
        RecipeNode tableFallback = recipe("crystal_matrix_ingot_table", ingot, 1,
                ingredient(lattice, 6), ingredient(star, 2));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                ingot, List.of(excludedNormal, decompression, tableFallback),
                block, List.of(compression)));
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of(ingot.itemId().toString(),
                        new RecipeChoice.Normal(decompression.recipeId())),
                Map.of(), Set.of(excludedNormal.recipeId()));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(lattice, 6), List.of(ingredient(ingot, 1)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime,
                Set.of(), Map.of(), preferences);

        assertFalse(result.feasible(), result.toString());
        assertFalse(result.missing().isEmpty(), result.toString());
        assertEquals(PureRecipePlanner.FailureReason.MISSING_INPUTS,
                result.failureReason(), result.missing().toString());
    }

    @Test
    void valueOnlyDemandCanUseTaggedOutputVariant() {
        MaterialRef sword = material("awakened_sword");
        MaterialRef raw = material("sword_raw");
        MaterialRef taggedSword = new MaterialRef(sword.itemId(), "{Unbreakable:1b}");
        RecipeNode recipe = recipe("make_tagged_sword", taggedSword, 1, ingredient(raw, 1));
        IngredientRef valueOnlySword = new IngredientRef(List.of(sword), 1,
                NbtMatchMode.ANY, DemandRole.CONSUMED);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(taggedSword, List.of(recipe))),
                Map.of(raw, 1), List.of(valueOnlySword), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("make_tagged_sword"), 1)), result.steps());
    }

    @Test
    void anyNbtInputConsumesConcreteTaggedStockWithoutGraphBinding() {
        MaterialRef target = material("tag_agnostic_result");
        MaterialRef input = material("tag_agnostic_input");
        MaterialRef enchanted = new MaterialRef(input.itemId(), "{Enchantments:[{lvl:1s}]}" );
        IngredientRef anyInput = new IngredientRef(List.of(input), 1,
                NbtMatchMode.ANY, DemandRole.CONSUMED);
        RecipeNode recipe = recipe("consume_tagged_input", target, 1, anyInput);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(target, List.of(recipe)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(enchanted, 1), List.of(ingredient(target, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(0L, result.remaining().getOrDefault(enchanted, 0L));
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("consume_tagged_input"), 1)), result.steps());
    }

    @Test
    void taggedWoodenSwordCanTraverseSmithingUpgradeChain() {
        MaterialRef woodenSword = material("wooden_sword");
        MaterialRef taggedWoodenSword = new MaterialRef(woodenSword.itemId(), "{Unbreakable:1b}");
        MaterialRef stoneSword = material("stone_sword");
        MaterialRef ironSword = material("iron_sword");
        MaterialRef diamondSword = material("diamond_sword");
        MaterialRef netheriteSword = material("netherite_sword");
        MaterialRef immemorialSword = material("immemorialsword");
        MaterialRef soulBlade = material("soul_blade");
        MaterialRef template = material("upgrade_template");
        MaterialRef stone = material("stone");
        MaterialRef ironBlock = material("iron_block");
        MaterialRef diamondBlock = material("diamond_block");
        MaterialRef netheriteIngot = material("netherite_ingot");
        MaterialRef immemorialIngot = material("immemorial_ingot");
        MaterialRef soulIngot = material("soul_ingot");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(stoneSword, List.of(smithing("wood_to_stone", stoneSword,
                ingredientAny(woodenSword), ingredient(stone, 1), ingredient(template, 1))));
        recipes.put(ironSword, List.of(smithing("stone_to_iron", ironSword,
                ingredientAny(stoneSword), ingredient(ironBlock, 1), ingredient(template, 1))));
        recipes.put(diamondSword, List.of(smithing("iron_to_diamond", diamondSword,
                ingredientAny(ironSword), ingredient(diamondBlock, 1), ingredient(template, 1))));
        recipes.put(netheriteSword, List.of(smithing("diamond_to_netherite", netheriteSword,
                ingredientAny(diamondSword), ingredient(netheriteIngot, 1), ingredient(template, 1))));
        recipes.put(immemorialSword, List.of(smithing("make_immemorial", immemorialSword,
                ingredientAny(netheriteSword), ingredient(immemorialIngot, 1), ingredient(template, 1))));
        recipes.put(soulBlade, List.of(smithing("make_soul_blade", soulBlade,
                ingredientAny(immemorialSword), ingredient(soulIngot, 1), ingredient(template, 1))));

        Map<MaterialRef, Integer> stock = Map.of(
                taggedWoodenSword, 1, stone, 1, ironBlock, 1, diamondBlock, 1,
                netheriteIngot, 1, immemorialIngot, 1, soulIngot, 1, template, 6);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), stock,
                List.of(new IngredientRef(List.of(soulBlade), 1, NbtMatchMode.ANY)), 30);

        assertTrue(result.feasible(), result.toString());
        assertEquals(6, result.steps().size());
    }

    @Test
    void anyDemandConsumesTaggedStockVariant() {
        MaterialRef plain = material("plain_sword");
        MaterialRef tagged = new MaterialRef(plain.itemId(), "{Unbreakable:1b}");
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(tagged, 1),
                List.of(new IngredientRef(List.of(plain), 1, NbtMatchMode.ANY)), 10);
        assertTrue(result.feasible(), result.toString());
    }

    @Test
    void reusableCatalystIsNotScaledOrConsumedAcrossBatches() {
        MaterialRef copper = material("copper");
        MaterialRef hammer = material("hammer");
        MaterialRef plate = material("plate");
        RecipeNode hammering = recipe("hammering", plate, 1,
                ingredient(copper, 1), catalyst(hammer, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(plate, List.of(hammering))),
                Map.of(copper, 10, hammer, 1), List.of(ingredient(plate, 10)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("hammering"), 10)),
                result.steps());
        assertEquals(1L, result.remaining().get(hammer));
    }

    @Test
    void oneCatalystCanBeSharedByDifferentRecipeSteps() {
        MaterialRef rawA = material("raw_a");
        MaterialRef rawB = material("raw_b");
        MaterialRef hammer = material("shared_hammer");
        MaterialRef partA = material("part_a");
        MaterialRef partB = material("part_b");
        MaterialRef resultMaterial = material("shared_result");
        RecipeNode makeA = recipe("make_a", partA, 1,
                ingredient(rawA, 1), catalyst(hammer, 1));
        RecipeNode makeB = recipe("make_b", partB, 1,
                ingredient(rawB, 1), catalyst(hammer, 1));
        RecipeNode assemble = recipe("assemble_shared", resultMaterial, 1,
                ingredient(partA, 1), ingredient(partB, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        partA, List.of(makeA), partB, List.of(makeB),
                        resultMaterial, List.of(assemble))),
                Map.of(rawA, 1, rawB, 1, hammer, 1),
                List.of(ingredient(resultMaterial, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(1L, result.remaining().get(hammer));
    }

    @Test
    void missingCatalystIsProducedOnceAndThenReused() {
        MaterialRef iron = material("hammer_iron");
        MaterialRef copper = material("hammer_copper");
        MaterialRef hammer = material("crafted_hammer");
        MaterialRef plate = material("crafted_plate");
        RecipeNode makeHammer = recipe("make_hammer", hammer, 1, ingredient(iron, 2));
        RecipeNode hammering = recipe("crafted_hammering", plate, 1,
                ingredient(copper, 1), catalyst(hammer, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        hammer, List.of(makeHammer), plate, List.of(hammering))),
                Map.of(iron, 2, copper, 10), List.of(ingredient(plate, 10)), 20);

        assertTrue(result.feasible());
        assertEquals(1L, result.steps().stream()
                .filter(step -> step.recipeId().equals(id("make_hammer"))).count());
        assertEquals(1L, result.remaining().get(hammer));
    }

    @Test
    void missingDurabilityToolIsProducedBeforeTheCuttingRecipe() {
        MaterialRef iron = material("cutting_tool_iron");
        MaterialRef carrot = material("cutting_tool_carrot");
        MaterialRef knife = material("cutting_tool_knife");
        MaterialRef slices = material("cutting_tool_slices");
        RecipeNode makeKnife = recipe("make_cutting_knife", knife, 1, ingredient(iron, 1));
        RecipeNode cutCarrot = recipe("cut_carrot", slices, 1,
                ingredient(carrot, 1), durability(knife, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                knife, List.of(makeKnife), slices, List.of(cutCarrot)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(iron, 1, carrot, 1),
                List.of(ingredient(slices, 1)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(id("make_cutting_knife"), id("cut_carrot")),
                result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
    }

    @Test
    void twoCatalystSlotsStillRequireTwoTools() {
        MaterialRef raw = material("double_tool_raw");
        MaterialRef tool = material("double_tool");
        MaterialRef output = material("double_tool_output");
        RecipeNode recipe = recipe("double_tool_recipe", output, 1,
                ingredient(raw, 1), catalyst(tool, 1), catalyst(tool, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(recipe)));

        assertFalse(PureRecipePlanner.resolve(graph, Map.of(raw, 1, tool, 1),
                List.of(ingredient(output, 1)), 20).feasible());
        assertTrue(PureRecipePlanner.resolve(graph, Map.of(raw, 1, tool, 2),
                List.of(ingredient(output, 1)), 20).feasible());
    }

    @Test
    void catalystTagCanCombineDifferentStockedVariants() {
        MaterialRef raw = material("tag_tool_raw");
        MaterialRef ironTool = material("iron_tool");
        MaterialRef goldTool = material("gold_tool");
        MaterialRef output = material("tag_tool_output");
        IngredientRef tools = new IngredientRef(List.of(ironTool, goldTool), 2,
                NbtMatchMode.EXACT, DemandRole.CATALYST);
        RecipeNode recipe = recipe("tag_tool_recipe", output, 1,
                ingredient(raw, 1), tools);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(output, List.of(recipe))),
                Map.of(raw, 1, ironTool, 1, goldTool, 1),
                List.of(ingredient(output, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(1L, result.remaining().get(ironTool));
        assertEquals(1L, result.remaining().get(goldTool));
    }

    @Test
    void recursivelyAmplifiesASeedUsedByItsOwnRecipe() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 1, stone, 49), List.of(ingredient(template, 8)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 1),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 2),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 4)),
                result.steps());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void amplifiesTemplateFromFiftyNineStockToSixtyFiveOutput() {
        MaterialRef template = material("template_with_large_seed_stock");
        MaterialRef stone = material("stone_for_large_seed_stock");
        RecipeNode duplicate = recipe("duplicate_template_large_seed_stock",
                template, 2, ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 59, stone, 49), List.of(ingredient(template, 65)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_large_seed_stock"), 6)),
                result.steps());
    }

    @Test
    void amplifiesTemplateFromTwoStockToSixtyFiveOutputInSeedStages() {
        MaterialRef template = material("template_with_small_seed_stock");
        MaterialRef stone = material("stone_for_small_seed_stock");
        RecipeNode duplicate = recipe("duplicate_template_small_seed_stock",
                template, 2, ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 2, stone, 512), List.of(ingredient(template, 65)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 2),
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 4),
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 8),
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 16),
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 32),
                new PureRecipePlanner.PlannedStep(
                        id("duplicate_template_small_seed_stock"), 1)),
                result.steps());
    }

    @Test
    void runtimeNbtOutputStillRecognizesSmithingSelfAmplification() {
        SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                id("runtime_template_duplicate"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.NETHERRACK), Ingredient.of(Items.DIAMOND),
                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2));
        ItemStack outputStack = recipe.getResultItem(RegistryAccess.EMPTY);
        List<IngredientSpec> specs = new SmithingRecipeHandler().getIngredients(recipe);
        RecipeNode duplicate = ImmutableRecipeGraphProjector.projectRecipe(
                recipe, outputStack, specs, "smithing",
                new ResourceLocation("minecraft", "smithing"), false);

        assertFalse(duplicate.output().runtimeNbt());
        assertTrue(SelfAmplifyingRecipePolicy.isSelfAmplifying(duplicate));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(duplicate.output(), List.of(duplicate)));

        PureRecipePlanner.Result planningResult = PureRecipePlanner.resolve(graph,
                Map.of(duplicate.output(), 2,
                        minecraftMaterial("netherrack"), 512,
                        minecraftMaterial("diamond"), 512),
                SelfAmplifyingRecipePolicy.planningRoots(duplicate, 65), 20);

        assertTrue(planningResult.feasible(), planningResult.toString());
        assertTrue(planningResult.missing().isEmpty(), planningResult.toString());
        assertEquals(6, planningResult.steps().size());
        assertEquals(63L, planningResult.steps().stream()
                .mapToLong(PureRecipePlanner.PlannedStep::batches)
                .sum());
        assertEquals(1L, planningResult.steps().get(
                planningResult.steps().size() - 1).batches());
    }

    @Test
    void amplificationRecipeStillRequiresASeed() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(stone, 49), List.of(ingredient(template, 8)), 20);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(Map.of(stone, 49L), result.remaining());
    }

    @Test
    void selfAmplifyingRecipeWorksAsAnIntermediateStep() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        MaterialRef artifact = material("artifact");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        RecipeNode assemble = recipe("assemble_artifact", artifact, 1,
                ingredient(template, 8));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate), artifact, List.of(assemble)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 1, stone, 49), List.of(ingredient(artifact, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 1),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 2),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 4),
                new PureRecipePlanner.PlannedStep(id("assemble_artifact"), 1)),
                result.steps());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void selfAmplifyingIntermediateScalesOtherInputsAcrossNodes() {
        MaterialRef template = material("template_with_cost");
        MaterialRef stone = material("template_cost_stone");
        MaterialRef artifact = material("template_artifact");
        RecipeNode duplicate = recipe("duplicate_template_with_cost", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        RecipeNode assemble = recipe("assemble_template_artifact", artifact, 1,
                ingredient(template, 8));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate), artifact, List.of(assemble)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 1, stone, 49), List.of(ingredient(artifact, 1)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("duplicate_template_with_cost"), 1),
                new PureRecipePlanner.PlannedStep(id("duplicate_template_with_cost"), 2),
                new PureRecipePlanner.PlannedStep(id("duplicate_template_with_cost"), 4),
                new PureRecipePlanner.PlannedStep(id("assemble_template_artifact"), 1)),
                result.steps());
        assertTrue(result.remaining().isEmpty());
        assertEquals(0L, result.remaining().getOrDefault(stone, 0L));
    }

    @Test
    void cycleTerminatesAndReportsCycle() {
        RecipeNode logFromPlank = recipe("log", LOG, 1, ingredient(PLANK, 1));
        RecipeNode plankFromLog = recipe("plank", PLANK, 1, ingredient(LOG, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                LOG, List.of(logFromPlank), PLANK, List.of(plankFromLog)));
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(),
                List.of(ingredient(LOG, 1)), 20);
        assertFalse(result.feasible());
        assertEquals(1, result.missing().size());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(PureRecipePlanner.FailureReason.CYCLE, result.failureReason());
    }

    @Test
    void terminalDecompressionLoopIsReportedAsCycle() {
        MaterialRef ingot = material("cycle_ingot");
        MaterialRef block = material("cycle_block");
        RecipeNode decompress = recipe("cycle_decompress", ingot, 1,
                ingredient(block, 1));
        RecipeNode compress = recipe("cycle_compress", block, 1,
                ingredient(ingot, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                ingot, List.of(decompress),
                block, List.of(compress)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(), List.of(ingredient(block, 1)), 20,
                256, 256, Long.MAX_VALUE, System::nanoTime, Set.of(ingot));

        assertFalse(result.feasible(), result.toString());
        assertEquals(PureRecipePlanner.FailureReason.CYCLE, result.failureReason());
    }

    @Test
    void reverseConversionCannotConsumeAnAncestorWhenForwardChainExists() {
        MaterialRef nineX = material("acacia_log_9x");
        MaterialRef eightX = material("acacia_log_8x");
        MaterialRef sevenX = material("acacia_log_7x");
        RecipeNode nineFromEight = recipe("compress_9x", nineX, 1, ingredient(eightX, 9));
        RecipeNode eightFromNine = recipe("decompress_9x", eightX, 9, ingredient(nineX, 1));
        RecipeNode eightFromSeven = recipe("compress_8x", eightX, 1, ingredient(sevenX, 9));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                nineX, List.of(nineFromEight),
                eightX, List.of(eightFromNine, eightFromSeven)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(sevenX, 81), List.of(ingredient(nineX, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("compress_8x"), 9),
                new PureRecipePlanner.PlannedStep(id("compress_9x"), 1)), result.steps());
    }

    @Test
    void fullCompressionChainNeverReturnsToAnAncestorTier() {
        List<MaterialRef> tiers = new ArrayList<>();
        for (int tier = 1; tier <= 9; tier++) {
            tiers.add(material("full_chain_log_" + tier + "x"));
        }

        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int tier = 1; tier < tiers.size(); tier++) {
            MaterialRef lower = tiers.get(tier - 1);
            MaterialRef higher = tiers.get(tier);
            recipes.computeIfAbsent(higher, ignored -> new ArrayList<>()).add(
                    recipe("full_chain_compress_" + (tier + 1) + "x",
                            higher, 1, ingredient(lower, 9)));
            recipes.computeIfAbsent(lower, ignored -> new ArrayList<>()).add(
                    recipe("full_chain_decompress_" + (tier + 1) + "x",
                            lower, 9, ingredient(higher, 1)));
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes),
                Map.of(tiers.get(6), 81),
                List.of(ingredient(tiers.get(7), 9)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime,
                Set.of(tiers.get(8)));

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("full_chain_compress_8x"), 9)),
                result.steps());
    }

    @Test
    void plansRawDemandAboveIntegerMaxWithoutTruncatingStock() {
        MaterialRef raw = material("large_chain_raw");
        List<MaterialRef> tiers = new ArrayList<>();
        tiers.add(raw);
        for (int tier = 1; tier <= 9; tier++) {
            tiers.add(material("large_chain_log_" + tier + "x"));
        }

        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int tier = 1; tier < tiers.size(); tier++) {
            MaterialRef lower = tiers.get(tier - 1);
            MaterialRef higher = tiers.get(tier);
            recipes.computeIfAbsent(higher, ignored -> new ArrayList<>()).add(
                    recipe("large_chain_compress_" + tier + "x",
                            higher, 1, ingredient(lower, 9)));
            recipes.computeIfAbsent(lower, ignored -> new ArrayList<>()).add(
                    recipe("large_chain_decompress_" + tier + "x",
                            lower, 9, ingredient(higher, 1)));
        }

        long requested = 6L;
        long targetInput = requested * 9L;
        long rawDemand = requested * 387_420_489L;
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes),
                Map.of(raw, 3_000_000_000L),
                List.of(ingredient(tiers.get(8), targetInput)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime,
                Set.of(tiers.get(9)));

        assertTrue(result.feasible(), result.toString());
        assertTrue(rawDemand > Integer.MAX_VALUE);
        assertEquals(rawDemand,
                result.steps().stream()
                        .filter(step -> step.recipeId().equals(id("large_chain_compress_1x")))
                        .mapToLong(step -> step.batches() * 9L)
                        .sum());
        assertEquals(3_000_000_000L - rawDemand, result.remaining().getOrDefault(raw, 0L));
    }

    @Test
    void targetOutputIsAncestorDuringRecursiveResolution() {
        MaterialRef nineX = material("active_root_log_9x");
        MaterialRef eightX = material("active_root_log_8x");
        MaterialRef sevenX = material("active_root_log_7x");
        RecipeNode nineFromEight = recipe("active_root_compress_9x", nineX, 1,
                ingredient(eightX, 9));
        RecipeNode eightFromNine = recipe("active_root_decompress_9x", eightX, 9,
                ingredient(nineX, 1));
        RecipeNode eightFromSeven = recipe("active_root_compress_8x", eightX, 1,
                ingredient(sevenX, 9));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                nineX, List.of(nineFromEight),
                eightX, List.of(eightFromNine, eightFromSeven)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(), List.of(ingredient(eightX, 9)),
                20, 256, 256, Long.MAX_VALUE, System::nanoTime, Set.of(nineX));

        assertFalse(result.feasible());
        assertTrue(result.steps().stream().noneMatch(step ->
                step.recipeId().equals(id("active_root_decompress_9x"))), result.steps().toString());
    }

    @Test
    void partialStockCannotBootstrapAZeroGainReverseCycle() {
        MaterialRef nineX = material("partial_acacia_log_9x");
        MaterialRef eightX = material("partial_acacia_log_8x");
        RecipeNode nineFromEight = recipe("partial_compress_9x", nineX, 1, ingredient(eightX, 9));
        RecipeNode eightFromNine = recipe("partial_decompress_9x", eightX, 9, ingredient(nineX, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                nineX, List.of(nineFromEight),
                eightX, List.of(eightFromNine)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(nineX, 1), List.of(ingredient(nineX, 2)), 20);

        assertFalse(result.feasible());
        assertTrue(result.steps().stream().noneMatch(step ->
                step.recipeId().equals(id("partial_decompress_9x"))), result.steps().toString());
    }

    @Test
    void reportsAllIndependentMissingLeavesFromTheSelectedPlan() {
        MaterialRef iron = material("missing_iron");
        MaterialRef gold = material("missing_gold");
        MaterialRef artifact = material("missing_artifact");
        RecipeNode assemble = recipe("assemble_missing_artifact", artifact, 1,
                ingredient(iron, 2), ingredient(gold, 3), ingredient(iron, 4));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(artifact, List.of(assemble))),
                Map.of(), List.of(ingredient(artifact, 1)), 20);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(List.of(ingredient(iron, 6), ingredient(gold, 3)), result.missing());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("assemble_missing_artifact"), 1)), result.steps());
    }

    @Test
    void backtracksAcrossEarlierRootWhenItsFirstRecipeConsumesLaterMaterial() {
        MaterialRef ore = material("ore");
        MaterialRef fuel = material("fuel");
        MaterialRef gear = material("gear");
        RecipeNode gearFromFuel = recipe("gear_from_fuel", gear, 1, ingredient(fuel, 1));
        RecipeNode gearFromOre = recipe("gear_from_ore", gear, 1, ingredient(ore, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                gear, List.of(gearFromFuel, gearFromOre)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(ore, 1, fuel, 1),
                List.of(ingredient(gear, 1), ingredient(fuel, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("gear_from_ore"), 1)),
                result.steps());
        assertTrue(result.backtracks() > 0);
    }

    @Test
    void equalCoveragePrefersRecipeWithFewerIndependentInputGroups() {
        MaterialRef target = material("simple_route_target");
        MaterialRef simpleInput = material("simple_route_input");
        MaterialRef simpleSeed = material("simple_route_seed");
        MaterialRef blockedA = material("complex_route_a");
        MaterialRef blockedB = material("complex_route_b");
        MaterialRef complexSeedA = material("complex_route_seed_a");
        MaterialRef complexSeedB = material("complex_route_seed_b");
        RecipeNode complex = recipe("a_complex_route", target, 1,
                ingredient(blockedA, 4), ingredient(blockedB, 4));
        RecipeNode simple = recipe("z_simple_route", target, 1,
                ingredient(simpleInput, 8));
        RecipeNode makeSimple = recipe("make_simple_input", simpleInput, 8,
                ingredient(simpleSeed, 1));
        RecipeNode makeComplexA = recipe("make_complex_a", blockedA, 4,
                ingredient(complexSeedA, 1));
        RecipeNode makeComplexB = recipe("make_complex_b", blockedB, 4,
                ingredient(complexSeedB, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        target, List.of(complex, simple),
                        simpleInput, List.of(makeSimple),
                        blockedA, List.of(makeComplexA),
                        blockedB, List.of(makeComplexB))),
                Map.of(simpleSeed, 1, complexSeedA, 1, complexSeedB, 1),
                List.of(ingredient(target, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("make_simple_input"), 1),
                new PureRecipePlanner.PlannedStep(id("z_simple_route"), 1)), result.steps());
        assertEquals(0, result.backtracks());
    }

    @Test
    void equalScoredCandidatesUseRecipeIdAsStableFinalTieBreaker() {
        MaterialRef target = material("stable_tie_target");
        MaterialRef input = material("stable_tie_input");
        RecipeNode laterId = recipe("tie_b", target, 1, ingredient(input, 1));
        RecipeNode earlierId = recipe("tie_a", target, 1, ingredient(input, 1));

        PureRecipePlanner.Result first = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(laterId, earlierId))),
                Map.of(input, 1), List.of(ingredient(target, 1)), 20);
        PureRecipePlanner.Result reversed = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(earlierId, laterId))),
                Map.of(input, 1), List.of(ingredient(target, 1)), 20);

        List<PureRecipePlanner.PlannedStep> expected =
                List.of(new PureRecipePlanner.PlannedStep(id("tie_a"), 1));
        assertEquals(expected, first.steps());
        assertEquals(expected, reversed.steps());
    }

    @Test
    void backtracksAcrossStockedIngredientAlternatives() {
        MaterialRef copper = material("copper");
        MaterialRef tin = material("tin");
        IngredientRef eitherMetal = new IngredientRef(List.of(copper, tin), 1);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(copper, 1, tin, 1),
                List.of(eitherMetal, ingredient(copper, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(0, result.remaining().size());
        assertTrue(result.backtracks() > 0);
    }

    @Test
    void stockedFallbackVariantIsUsedWithoutCraftingPreferredVariant() {
        MaterialRef whiteWool = minecraftMaterial("white_wool");
        MaterialRef greenWool = minecraftMaterial("green_wool");
        MaterialRef string = minecraftMaterial("string");
        IngredientRef anyWool = new IngredientRef(List.of(whiteWool, greenWool), 1);
        RecipeNode makeWhite = recipe("make_white_wool", whiteWool, 1,
                ingredient(string, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(whiteWool, List.of(makeWhite))),
                Map.of(greenWool, 1, string, 1), List.of(anyWool), 20);

        assertTrue(result.feasible(), result.toString());
        assertTrue(result.steps().isEmpty());
        assertEquals(1L, result.remaining().get(string));
    }

    @Test
    void preferredVariantIsProducedFirstOnlyAfterAcceptedStockRunsOut() {
        MaterialRef whiteWool = minecraftMaterial("white_wool");
        MaterialRef greenWool = minecraftMaterial("green_wool");
        MaterialRef string = minecraftMaterial("string");
        MaterialRef dye = material("green_dye");
        IngredientRef anyWool = new IngredientRef(List.of(greenWool, whiteWool), 1);
        RecipeNode makeGreen = recipe("make_green_wool", greenWool, 1,
                ingredient(dye, 1));
        RecipeNode makeWhite = recipe("make_white_wool", whiteWool, 1,
                ingredient(string, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        greenWool, List.of(makeGreen), whiteWool, List.of(makeWhite))),
                Map.of(string, 1, dye, 1), List.of(anyWool), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("make_white_wool"), 1)), result.steps());
        assertEquals(1L, result.remaining().get(dye));
    }

    @Test
    void impossiblePreferredVariantFallsBackToAnotherProductiveVariant() {
        MaterialRef whiteWool = minecraftMaterial("white_wool");
        MaterialRef greenWool = minecraftMaterial("green_wool");
        MaterialRef missing = material("missing_white_input");
        MaterialRef dye = material("available_green_input");
        IngredientRef anyWool = new IngredientRef(List.of(whiteWool, greenWool), 1);
        RecipeNode makeWhite = recipe("blocked_white_wool", whiteWool, 1,
                ingredient(missing, 1));
        RecipeNode makeGreen = recipe("make_green_wool", greenWool, 1,
                ingredient(dye, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        whiteWool, List.of(makeWhite), greenWool, List.of(makeGreen))),
                Map.of(dye, 1), List.of(anyWool), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("make_green_wool"), 1)), result.steps());
    }

    @Test
    void preferredVariantDoesNotAuthorizeAZeroGainRecoloringChain() {
        MaterialRef whiteWool = minecraftMaterial("white_wool");
        MaterialRef greenWool = minecraftMaterial("green_wool");
        MaterialRef redWool = minecraftMaterial("red_wool");
        MaterialRef purpleWool = minecraftMaterial("purple_wool");
        IngredientRef anyWool = new IngredientRef(
                List.of(whiteWool, greenWool, redWool, purpleWool), 2);
        RecipeNode greenToRed = recipe("green_to_red", redWool, 1,
                ingredient(greenWool, 1));
        RecipeNode redToPurple = recipe("red_to_purple", purpleWool, 1,
                ingredient(redWool, 1));
        RecipeNode purpleToWhite = recipe("purple_to_white", whiteWool, 1,
                ingredient(purpleWool, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(
                        redWool, List.of(greenToRed),
                        purpleWool, List.of(redToPurple),
                        whiteWool, List.of(purpleToWhite))),
                Map.of(greenWool, 1), List.of(anyWool), 20);

        assertFalse(result.feasible());
        assertTrue(result.steps().isEmpty(), result.toString());
    }

    @Test
    void aggregatesTagVariantsWhilePreservingLaterExactDemand() {
        MaterialRef whiteWool = material("white_wool");
        MaterialRef blackWool = material("black_wool");
        IngredientRef anyWool = new IngredientRef(List.of(whiteWool, blackWool), 6);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(whiteWool, 4, blackWool, 4),
                List.of(anyWool, ingredient(whiteWool, 2)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void producesOnlyTheUnreservedShortfallForAnyModeTagDemand() {
        MaterialRef whiteWool = minecraftMaterial("white_wool");
        MaterialRef greenWool = minecraftMaterial("green_wool");
        MaterialRef dye = material("spare_green_dye");
        IngredientRef anyWool = new IngredientRef(List.of(whiteWool, greenWool), 2,
                NbtMatchMode.ANY, DemandRole.CONSUMED);
        RecipeNode makeGreen = recipe("make_reserved_green_wool", greenWool, 1,
                ingredient(dye, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(greenWool, List.of(makeGreen))),
                Map.of(whiteWool, 2, greenWool, 1, dye, 1),
                List.of(anyWool, ingredient(whiteWool, 2)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("make_reserved_green_wool"), 1)), result.steps());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void largeTagUsesAggregateStockWithoutRecipeSearch() {
        List<MaterialRef> fuels = new ArrayList<>();
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        for (int i = 0; i < 128; i++) {
            MaterialRef fuel = material("fuel_" + i);
            fuels.add(fuel);
            stock.put(fuel, 1);
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), stock,
                List.of(new IngredientRef(fuels, 96)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.expandedStates() <= 2);
    }

    @Test
    void mergesRepeatedEquivalentDemandsBeforeSearching() {
        MaterialRef token = material("token");

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(token, 9),
                List.of(ingredient(token, 1), ingredient(token, 3), ingredient(token, 5)), 20);

        assertTrue(result.feasible());
        assertEquals(2, result.expandedStates());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void stockedTagVariantIsExpandedBeforeAbsentVariants() {
        MaterialRef plank = material("oak_plank");
        MaterialRef oakChest = material("oak_chest");
        List<MaterialRef> chestVariants = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < 32; i++) {
            MaterialRef variant = material("absent_chest_" + i);
            MaterialRef missing = material("missing_input_" + i);
            chestVariants.add(variant);
            recipes.put(variant, List.of(recipe("absent_chest_" + i, variant, 1,
                    ingredient(missing, 1))));
        }
        chestVariants.add(oakChest);
        recipes.put(oakChest, List.of(recipe("oak_chest", oakChest, 1,
                ingredient(plank, 1))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(oakChest, 1, plank, 7),
                List.of(new IngredientRef(chestVariants, 8)), 20, 6);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("oak_chest"), 7)),
                result.steps());
    }

    @Test
    void prefabStyleCompressedTagSkipsUnseededVariants() {
        MaterialRef dirt = material("dirt");
        MaterialRef compressedDirt = material("compressed_dirt");
        MaterialRef prefabDoubleDirt = material("prefab_double_compressed_dirt");
        List<MaterialRef> doubleDirtVariants = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();

        for (int i = 0; i < 96; i++) {
            MaterialRef deadVariant = material("dead_double_dirt_" + i);
            MaterialRef missingCompressedVariant = material("missing_compressed_dirt_" + i);
            doubleDirtVariants.add(deadVariant);
            recipes.put(deadVariant, List.of(recipe("dead_double_dirt_" + i,
                    deadVariant, 1, ingredient(missingCompressedVariant, 9))));
        }
        doubleDirtVariants.add(prefabDoubleDirt);
        recipes.put(compressedDirt, List.of(recipe("compress_dirt", compressedDirt, 1,
                ingredient(dirt, 9))));
        recipes.put(prefabDoubleDirt, List.of(recipe("double_compress_dirt",
                prefabDoubleDirt, 1, ingredient(compressedDirt, 9))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(dirt, 243),
                List.of(new IngredientRef(doubleDirtVariants, 3)),
                20, 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("compress_dirt"), 27),
                new PureRecipePlanner.PlannedStep(id("double_compress_dirt"), 3)),
                result.steps());
        assertTrue(result.expandedStates() < 20);
    }

    @Test
    void prefabTimberChainDoesNotPrewalkEveryLogVariantRecipe() {
        List<MaterialRef> logs = new ArrayList<>();
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int species = 0; species < 67; species++) {
            MaterialRef log = material("log_species_" + species);
            logs.add(log);
            stock.put(log, species == 66 ? 3 : 11); // 66 * 11 + 3 = 729 logs

            MaterialRef previous = material("dead_log_input_" + species + "_0");
            recipes.put(log, List.of(recipe("dead_log_recipe_" + species, log, 1,
                    ingredient(previous, 1))));
            for (int depth = 1; depth < 128; depth++) {
                MaterialRef next = material("dead_log_input_" + species + "_" + depth);
                recipes.put(previous, List.of(recipe(
                        "dead_log_chain_" + species + "_" + depth,
                        previous, 1, ingredient(next, 1))));
                previous = next;
            }
        }

        MaterialRef bundle = material("bundle_of_timber");
        MaterialRef heap = material("heap_of_timber");
        MaterialRef ton = material("ton_of_timber");
        recipes.put(bundle, List.of(recipe("bundle_of_timber", bundle, 1,
                new IngredientRef(logs, 9))));
        recipes.put(heap, List.of(recipe("heap_of_timber", heap, 1,
                ingredient(bundle, 9))));
        recipes.put(ton, List.of(recipe("ton_of_timber", ton, 1,
                ingredient(heap, 9))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), stock, List.of(ingredient(ton, 1)),
                20, 1_000, 1_000, System.nanoTime() + 200_000_000L);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("bundle_of_timber"), 81),
                new PureRecipePlanner.PlannedStep(id("heap_of_timber"), 9),
                new PureRecipePlanner.PlannedStep(id("ton_of_timber"), 1)), result.steps());
        assertTrue(result.expandedStates() < 20);
    }

    @Test
    void missingPrefabLogsStillReturnTheSelectedCompressionTrace() {
        List<MaterialRef> logs = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int species = 0; species < 67; species++) {
            logs.add(material("missing_log_" + species));
        }
        MaterialRef bundle = material("missing_bundle");
        MaterialRef heap = material("missing_heap");
        MaterialRef ton = material("missing_ton");
        recipes.put(bundle, List.of(recipe("missing_bundle", bundle, 1,
                new IngredientRef(logs, 9))));
        recipes.put(heap, List.of(recipe("missing_heap", heap, 1,
                ingredient(bundle, 9))));
        recipes.put(ton, List.of(recipe("missing_ton", ton, 1,
                ingredient(heap, 9))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(), List.of(ingredient(ton, 1)),
                20, 2_000, 1_000, System.nanoTime() + 200_000_000L);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(logs, result.missing().get(0).alternatives());
        assertEquals(729, result.missing().get(0).count());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("missing_bundle"), 81),
                new PureRecipePlanner.PlannedStep(id("missing_heap"), 9),
                new PureRecipePlanner.PlannedStep(id("missing_ton"), 1)), result.steps());
    }

    @Test
    void partialBroadWoodStockRejectsCrossVariantConversionRingWithinBudget() {
        List<MaterialRef> logs = new ArrayList<>();
        List<MaterialRef> boards = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int species = 0; species < 67; species++) {
            logs.add(material("ring_log_" + species));
            boards.add(material("ring_board_" + species));
        }
        for (int species = 0; species < logs.size(); species++) {
            MaterialRef log = logs.get(species);
            MaterialRef board = boards.get(species);
            MaterialRef nextLog = logs.get((species + 1) % logs.size());
            recipes.put(log, List.of(recipe("ring_log_" + species, log, 1,
                    ingredient(board, 1))));
            recipes.put(board, List.of(recipe("ring_board_" + species, board, 1,
                    ingredient(nextLog, 1))));
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(logs.get(0), 1),
                List.of(new IngredientRef(logs, 2)), 20, 20_000, 4_000,
                System.nanoTime() + 200_000_000L);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertTrue(result.expandedStates() < 1_000);
    }

    @Test
    void partialPrefabWoodStockRejectsDeepUnseededVariantsWithinBudget() {
        List<MaterialRef> logs = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int species = 0; species < 67; species++) {
            MaterialRef log = material("partial_log_" + species);
            logs.add(log);
            MaterialRef input = material("partial_input_" + species + "_0");
            recipes.put(log, List.of(recipe("partial_log_recipe_" + species, log, 1,
                    ingredient(input, 1))));
            for (int depth = 1; depth < 128; depth++) {
                MaterialRef next = material("partial_input_" + species + "_" + depth);
                recipes.put(input, List.of(recipe(
                        "partial_chain_" + species + "_" + depth,
                        input, 1, ingredient(next, 1))));
                input = next;
            }
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(logs.get(0), 1),
                List.of(new IngredientRef(logs, 729)), 20, 20_000, 4_000,
                System.nanoTime() + 200_000_000L);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(1, result.expandedStates());
    }

    @Test
    void broadFamilyGuardPreservesRealAmplification() {
        MaterialRef oak = material("amplify_oak");
        MaterialRef birch = material("amplify_birch");
        RecipeNode duplicate = recipe("duplicate_birch", birch, 2,
                ingredient(oak, 1));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(birch, List.of(duplicate))),
                Map.of(oak, 1), List.of(new IngredientRef(List.of(oak, birch), 2)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("duplicate_birch"), 1)), result.steps());
    }

    @Test
    void entirelyUnseededLargeTagFailsWithoutSearchingEveryVariant() {
        List<MaterialRef> variants = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < 96; i++) {
            MaterialRef variant = material("dead_variant_" + i);
            MaterialRef missing = material("missing_" + i);
            variants.add(variant);
            recipes.put(variant, List.of(recipe("dead_variant_" + i, variant, 1,
                    ingredient(missing, 1))));
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(),
                List.of(new IngredientRef(variants, 1)), 20, 4);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(1, result.expandedStates());
    }

    @Test
    void exactCompressedDemandSkipsUnseededProducerChain() {
        MaterialRef timber = material("timber");
        MaterialRef compressedTimber = material("compressed_timber");
        MaterialRef tonOfTimber = material("ton_of_timber");
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                compressedTimber, List.of(recipe("compress_timber", compressedTimber, 1,
                        ingredient(timber, 9))),
                tonOfTimber, List.of(recipe("compress_ton_of_timber", tonOfTimber, 1,
                        ingredient(compressedTimber, 9)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(), List.of(ingredient(tonOfTimber, 1)), 20, 2);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(1, result.expandedStates());
    }

    @Test
    void unseededReverseConversionIsPrunedWithoutExhaustingSearch() {
        MaterialRef compressed = material("compressed_stone");
        MaterialRef doubleCompressed = material("double_compressed_stone");
        RecipeNode decompress = recipe("decompress_double", compressed, 9,
                ingredient(doubleCompressed, 1));
        RecipeNode compress = recipe("compress_double", doubleCompressed, 1,
                ingredient(compressed, 9));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                compressed, List.of(decompress), doubleCompressed, List.of(compress)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(), List.of(ingredient(compressed, 9)), 20, 2);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(1, result.expandedStates());
    }

    @Test
    void stockedCompressedInputStillAllowsDecompression() {
        MaterialRef compressed = material("compressed_stone");
        MaterialRef doubleCompressed = material("double_compressed_stone");
        RecipeNode decompress = recipe("decompress_double", compressed, 9,
                ingredient(doubleCompressed, 1));
        RecipeNode compress = recipe("compress_double", doubleCompressed, 1,
                ingredient(compressed, 9));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                compressed, List.of(decompress), doubleCompressed, List.of(compress)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(doubleCompressed, 1),
                List.of(ingredient(compressed, 9)), 20, 6);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("decompress_double"), 1)),
                result.steps());
    }

    @Test
    void existingTargetSeedAllowsCrossMaterialAmplification() {
        MaterialRef shard = material("shard");
        MaterialRef cluster = material("cluster");
        RecipeNode unpack = recipe("unpack_cluster", shard, 2, ingredient(cluster, 1));
        RecipeNode pack = recipe("pack_cluster", cluster, 1, ingredient(shard, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                shard, List.of(unpack), cluster, List.of(pack)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(shard, 1), List.of(ingredient(shard, 2)), 20, 8);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("pack_cluster"), 1),
                new PureRecipePlanner.PlannedStep(id("unpack_cluster"), 1)), result.steps());
    }

    @Test
    void quartzConversionsPruneReverseLoopInsteadOfReportingChiseledQuartzMissing() {
        MaterialRef quartz = material("quartz_block");
        MaterialRef slab = material("quartz_slab");
        MaterialRef chiseled = material("chiseled_quartz_block");
        MaterialRef stairs = material("quartz_stairs");

        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                slab, List.of(
                        recipe("slabs_from_chiseled", slab, 2, ingredient(chiseled, 1)),
                        recipe("slabs_from_quartz", slab, 2, ingredient(quartz, 1))),
                chiseled, List.of(recipe("chiseled_from_slabs", chiseled, 1,
                        ingredient(slab, 2))),
                stairs, List.of(recipe("stairs_from_chiseled", stairs, 4,
                        ingredient(chiseled, 6)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(quartz, 8),
                List.of(ingredient(slab, 1), ingredient(stairs, 2), ingredient(chiseled, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(0, result.backtracks());
        assertTrue(result.steps().stream().anyMatch(step ->
                step.recipeId().equals(id("slabs_from_quartz"))));
    }

    @Test
    void searchBudgetProducesExplicitNonSuccessStatus() {
        MaterialRef target = material("target");
        RecipeNode impossibleA = recipe("impossible_a", target, 1, ingredient(LOG, 1));
        RecipeNode impossibleB = recipe("impossible_b", target, 1, ingredient(PLANK, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(impossibleA, impossibleB)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(LOG, 1, PLANK, 1),
                List.of(ingredient(target, 2)), 20, 1);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, result.status());
        assertTrue(result.expandedStates() > 1);
        assertTrue(result.steps().isEmpty());
        assertEquals(Map.of(LOG, 1L, PLANK, 1L), result.remaining());
    }

    @Test
    void stepBudgetProducesExplicitNonSuccessStatus() {
        MaterialRef board = material("board");
        MaterialRef handle = material("handle");
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                board, List.of(recipe("board", board, 1, ingredient(LOG, 1))),
                handle, List.of(recipe("handle", handle, 1, ingredient(board, 1)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(LOG, 1), List.of(ingredient(handle, 1)), 1, 100);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.STEP_LIMIT, result.status());
        assertTrue(result.steps().isEmpty());
        assertEquals(Map.of(LOG, 1L), result.remaining());
    }

    @Test
    void expiredDeadlineProducesUnknownTimeLimit() {
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(), List.of(ingredient(LOG, 1)),
                20, 100, 100, System.nanoTime() - 1L);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(PureRecipePlanner.Status.TIME_LIMIT, result.status());
    }

    @Test
    void expiredSearchStillBuildsABoundedMultiMissingTrace() {
        MaterialRef iron = material("timed_out_iron");
        MaterialRef gold = material("timed_out_gold");
        MaterialRef artifact = material("timed_out_artifact");
        RecipeNode assemble = recipe("assemble_timed_out_artifact", artifact, 1,
                ingredient(iron, 2), ingredient(gold, 3));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(artifact, List.of(assemble))),
                Map.of(), List.of(ingredient(artifact, 1)),
                20, 100, 100, System.nanoTime() - 1L);

        assertEquals(PureRecipePlanner.Status.TIME_LIMIT, result.status());
        assertEquals(List.of(ingredient(iron, 2), ingredient(gold, 3)), result.missing());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(
                id("assemble_timed_out_artifact"), 1)), result.steps());
    }

    @Test
    void reachabilityIndexStopsAtTheSearchDeadline() {
        MaterialRef target = material("reachability_target");
        MaterialRef missing = material("reachability_missing");
        List<RecipeNode> targetProducers = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            targetProducers.add(recipe("reachability_producer_" + index, target, 1,
                    ingredient(missing, 1)));
        }
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(target, targetProducers);
        for (int index = 0; index < 128; index++) {
            MaterialRef output = material("reachability_output_" + index);
            recipes.put(output, List.of(recipe("reachability_recipe_" + index,
                    output, 1, ingredient(material("reachability_input_" + index), 1))));
        }
        AtomicLong clock = new AtomicLong();

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(),
                List.of(ingredient(target, 1)), 20, 100, 100, 32L,
                clock::incrementAndGet);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(PureRecipePlanner.Status.TIME_LIMIT, result.status());
    }

    @Test
    void singleProducerDoesNotScanUnrelatedGraphBeforeResolvingItsMissingInput() {
        MaterialRef target = material("focused_target");
        MaterialRef missing = material("focused_missing");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(target, List.of(recipe("focused_producer", target, 1,
                ingredient(missing, 1))));
        for (int index = 0; index < 128; index++) {
            MaterialRef output = material("unrelated_output_" + index);
            recipes.put(output, List.of(recipe("unrelated_recipe_" + index,
                    output, 1, ingredient(material("unrelated_input_" + index), 1))));
        }
        AtomicLong clock = new AtomicLong();

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(),
                List.of(ingredient(target, 1)), 20, 100, 100, 20L,
                clock::incrementAndGet);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Feasibility.INFEASIBLE, result.feasibility());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(List.of(ingredient(missing, 1)), result.missing());
    }

    @Test
    void partialTraceTimeoutKeepsKnownUnresolvableResult() {
        MaterialRef target = material("partial_target");
        MaterialRef missing = material("partial_missing");
        RecipeNode producer = recipe("partial_producer", target, 1,
                ingredient(missing, 1));
        AtomicLong clock = new AtomicLong();

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(producer))), Map.of(),
                List.of(ingredient(target, 1)), 20, 100, 100, 12L,
                clock::incrementAndGet);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(List.of(ingredient(missing, 1)), result.missing());
    }

    @Test
    void interruptedPlanningStopsCooperatively() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PureRecipePlanner.resolve(
                    new ImmutableRecipeGraph(Map.of()), Map.of(), List.of(ingredient(LOG, 1)), 20));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void scaledRecipeFailurePreservesAnyNbtSemantics() {
        MaterialRef target = material("any_nbt_target");
        MaterialRef input = material("any_nbt_input");
        IngredientRef anyNbtInput = new IngredientRef(
                List.of(input), 2, NbtMatchMode.ANY);
        RecipeNode producer = recipe("any_nbt_producer", target, 1, anyNbtInput);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(producer))), Map.of(),
                List.of(ingredient(target, 3)), 20);

        assertFalse(result.feasible());
        assertEquals(NbtMatchMode.ANY, result.missing().get(0).nbtMatchMode());
        assertEquals(6, result.missing().get(0).count());
    }

    @Test
    void candidateCoverageIsRecomputedAfterEarlierRecipeConsumesItsSeed() {
        MaterialRef rawA = material("score_raw_a");
        MaterialRef rawB = material("score_raw_b");
        MaterialRef intermediate = material("score_intermediate");
        MaterialRef first = material("score_first");
        MaterialRef second = material("score_second");
        List<RecipeNode> producers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            producers.add(recipe("a_seed_" + i, intermediate, 1, ingredient(rawA, 1)));
            producers.add(recipe("b_seed_" + i, intermediate, 1, ingredient(rawB, 1)));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                intermediate, producers,
                first, List.of(recipe("make_first", first, 1, ingredient(intermediate, 1))),
                second, List.of(recipe("make_second", second, 1, ingredient(intermediate, 1)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(rawA, 1, rawB, 1), List.of(ingredient(first, 1), ingredient(second, 1)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(id("a_seed_0"), id("make_first"), id("b_seed_0"), id("make_second")),
                result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void overlappingNbtAlternativesDoNotCountTheSameStockTwiceForCatalysts() {
        MaterialRef red = new MaterialRef(id("same_tool"), "{color:1}");
        MaterialRef blue = new MaterialRef(id("same_tool"), "{color:2}");
        IngredientRef tool = new IngredientRef(List.of(red, blue), 2,
                NbtMatchMode.ANY, DemandRole.CATALYST);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(Map.of()),
                Map.of(red, 1), List.of(tool), 20);

        assertFalse(result.feasible());
        assertEquals(Map.of(red, 1L), result.remaining());
    }

    @Test
    void laterDemandSeesNbtVariantProducedAfterInitialStockMatching() {
        MaterialRef oldTool = new MaterialRef(id("evolving_tool"), "{version:1}");
        MaterialRef newTool = new MaterialRef(id("evolving_tool"), "{version:2}");
        MaterialRef raw = material("new_tool_seed");
        MaterialRef first = material("tool_first_result");
        IngredientRef anyTool = new IngredientRef(List.of(oldTool, newTool), 1, NbtMatchMode.ANY);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                first, List.of(recipe("use_old_tool", first, 1, anyTool)),
                newTool, List.of(recipe("produce_new_tools", newTool, 2, ingredient(raw, 1)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(oldTool, 1, raw, 1),
                List.of(ingredient(first, 1), ingredient(newTool, 1), anyTool), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(id("use_old_tool"), id("produce_new_tools")),
                result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
        assertTrue(result.remaining().isEmpty());
    }

    private static RecipeNode recipe(String id, MaterialRef output, int count, IngredientRef... inputs) {
        return new RecipeNode(id(id), output, count, List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, long count) {
        return new IngredientRef(List.of(material), count);
    }

    private static IngredientRef ingredientAny(MaterialRef material) {
        return new IngredientRef(List.of(material), 1, NbtMatchMode.ANY);
    }

    private static RecipeNode smithing(String id, MaterialRef output, IngredientRef... inputs) {
        return new RecipeNode(id(id), output, 1, List.of(inputs), "smithing",
                new ResourceLocation("minecraft", "smithing"));
    }

    private static IngredientRef catalyst(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count, NbtMatchMode.EXACT,
                DemandRole.CATALYST);
    }

    private static IngredientRef durability(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count, NbtMatchMode.EXACT,
                DemandRole.DURABILITY);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static MaterialRef minecraftMaterial(String path) {
        return new MaterialRef(new ResourceLocation("minecraft", path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
