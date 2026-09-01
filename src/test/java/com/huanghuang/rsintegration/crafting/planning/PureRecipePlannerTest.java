package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PureRecipePlannerTest {
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

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("planks"), 1),
                new PureRecipePlanner.PlannedStep(id("sticks"), 2)), result.steps());
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
        assertEquals(1, result.remaining().get(hammer));
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
        assertEquals(1, result.remaining().get(hammer));
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
        assertEquals(1, result.remaining().get(hammer));
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
        assertEquals(1, result.remaining().get(ironTool));
        assertEquals(1, result.remaining().get(goldTool));
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
        assertEquals(Map.of(stone, 49), result.remaining());
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
    void cycleTerminatesAndReportsMissing() {
        RecipeNode logFromPlank = recipe("log", LOG, 1, ingredient(PLANK, 1));
        RecipeNode plankFromLog = recipe("plank", PLANK, 1, ingredient(LOG, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                LOG, List.of(logFromPlank), PLANK, List.of(plankFromLog)));
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(),
                List.of(ingredient(LOG, 1)), 20);
        assertFalse(result.feasible());
        assertEquals(1, result.missing().size());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
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
        assertEquals(Map.of(LOG, 1, PLANK, 1), result.remaining());
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
        assertEquals(Map.of(LOG, 1), result.remaining());
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
    void reachabilityIndexDoesNotConsumeSearchDeadline() {
        MaterialRef target = material("reachability_target");
        MaterialRef missing = material("reachability_missing");
        RecipeNode producer = recipe("reachability_producer", target, 1,
                ingredient(missing, 1));
        AtomicLong clock = new AtomicLong();

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of(target, List.of(producer))), Map.of(),
                List.of(ingredient(target, 1)), 20, 100, 100, 8L,
                clock::incrementAndGet);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
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

    private static RecipeNode recipe(String id, MaterialRef output, int count, IngredientRef... inputs) {
        return new RecipeNode(id(id), output, count, List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    private static IngredientRef catalyst(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count, NbtMatchMode.EXACT,
                DemandRole.CATALYST);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
