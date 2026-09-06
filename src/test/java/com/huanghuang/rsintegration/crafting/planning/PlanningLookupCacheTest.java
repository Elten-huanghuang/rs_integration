package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLookupCacheTest extends BootstrapTest {
    private static final PlanningLookupCache.Limits UNCACHED = new PlanningLookupCache.Limits(0, 0, 0, 0);
    private static final ResourceLocation TOOL = id("tool");
    private static final String REQUIRED = "{Unbreakable:1}";
    private static final String STOCKED = "{Damage:0,Unbreakable:1b}";

    @Test
    void cachedNbtMatchesUncachedReferenceIncludingMalformedAndOrdinaryTags() {
        List<String> tags = Arrays.asList(null, "", " ", "{}", REQUIRED, STOCKED,
                "{Damage:1,Unbreakable:1b}", "{Damage:0,Unbreakable:0b}",
                "{Damage:0,Unbreakable:1b,RepairCost:8,itemModifier:\"celestial_forge:vicious\"}",
                "{Damage:0,Unbreakable:1b,RepairCost:8,itemModifier:\"celestial_forge:other\"}",
                "{Damage:2}", "{Potion:\"minecraft:water\"}",
                "{Enchantments:[{id:\"test:sharpness\",lvl:1s}]}",
                "{custom:{level:1}}", "{custom:{level:1b}}", "{broken", "{Damage:\"zero\"}");
        PlanningLookupCache.run(() -> {
            for (int repetition = 0; repetition < 3; repetition++) {
                for (String expected : tags) {
                    for (String actual : tags) {
                        for (boolean partial : List.of(false, true)) {
                            assertEquals(referenceNbt(expected, actual, partial),
                                    PlanningLookupCache.matchesNbt(expected, actual, partial),
                                    () -> expected + " versus " + actual + " partial=" + partial);
                        }
                    }
                }
            }
            assertTrue(PlanningLookupCache.currentStats().nbtCacheHits() > 0);
            return null;
        });
        assertFalse(PlanningLookupCache.isActive());
    }

    @Test
    void normalizationDoesNotMutateCachedOrdinaryExactState() {
        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED, STOCKED));
            assertFalse(ImmutableRecipeGraphProjector.exactNbtMatches(
                    "{Damage:0}", "{Damage:0,Unbreakable:1b}"));
            assertFalse(ImmutableRecipeGraphProjector.partialNbtMatches("{Damage:1}", STOCKED));
            assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches("{Damage:0}", STOCKED));
            assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED, STOCKED));
            return null;
        });
    }

    @Test
    void nestedScopesReuseParsingAndReleaseItAfterTheRequest() {
        PlanningLookupCache.Stats stats = PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED, STOCKED));
            PlanningLookupCache.run(() -> {
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED, STOCKED));
                return null;
            });
            assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED, STOCKED));
            return PlanningLookupCache.currentStats();
        });
        assertEquals(2, stats.nbtParses());
        assertEquals(4, stats.nbtCacheHits());
        assertFalse(PlanningLookupCache.isActive());
        PlanningLookupCache.run(() -> {
            assertEquals(0, PlanningLookupCache.currentStats().cachedTags());
            assertTrue(ImmutableRecipeGraphProjector.exactNbtMatches(REQUIRED, STOCKED));
            assertEquals(2, PlanningLookupCache.currentStats().nbtParses());
            return null;
        });
    }

    @Test
    void exceptionAndCancellationReleaseTheRequestScope() {
        assertThrows(IllegalStateException.class, () -> PlanningLookupCache.run(() -> {
            ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED, STOCKED);
            throw new IllegalStateException("test failure");
        }));
        assertFalse(PlanningLookupCache.isActive());
        ImmutableRecipeGraph graph = lookupGraph(0);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> PlanningLookupCache.run(() ->
                    ImmutableRecipeGraphProjector.candidates(graph, new MaterialRef(TOOL, ""), NbtMatchMode.ANY)));
        } finally {
            Thread.interrupted();
        }
        assertFalse(PlanningLookupCache.isActive());
    }

    @Test
    void concurrentRequestsDoNotShareMutableParsedState() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            var first = workers.submit(() -> parallelRequest("{owner:1}", barrier));
            var second = workers.submit(() -> parallelRequest("{owner:2}", barrier));
            for (var future : List.of(first, second)) {
                PlanningLookupCache.Stats stats = future.get(10, TimeUnit.SECONDS);
                assertEquals(1, stats.nbtParses());
                assertEquals(3, stats.nbtCacheHits());
                assertEquals(1, stats.cachedTags());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void nbtEntryLimitFallsBackWithoutChangingMatches() {
        PlanningLookupCache.run(new PlanningLookupCache.Limits(1, 1000, 0, 0), () -> {
            for (int value = 0; value < 20; value++) {
                String tag = "{value:" + value + "}";
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(tag, tag));
                assertFalse(ImmutableRecipeGraphProjector.partialNbtMatches("{value:-1}", tag));
            }
            assertEquals(1, PlanningLookupCache.currentStats().cachedTags());
            return null;
        });
    }

    @Test
    void oversizedNbtIsNotRetainedButStillMatches() {
        PlanningLookupCache.run(new PlanningLookupCache.Limits(10, 5, 0, 0), () -> {
            for (int repetition = 0; repetition < 2; repetition++) {
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED, STOCKED));
            }
            assertEquals(0, PlanningLookupCache.currentStats().cachedTags());
            assertEquals(0, PlanningLookupCache.currentStats().cachedNbtCharacters());
            assertEquals(4, PlanningLookupCache.currentStats().nbtParses());
            return null;
        });
    }

    @Test
    void aggregateNbtCharacterLimitFallsBackWithoutRejectingInput() {
        PlanningLookupCache.run(new PlanningLookupCache.Limits(100, 10, 0, 0), () -> {
            for (int value = 0; value < 20; value++) {
                String tag = "{v:" + value + "}";
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(tag, tag));
            }
            assertEquals(2, PlanningLookupCache.currentStats().cachedTags());
            assertEquals(10, PlanningLookupCache.currentStats().cachedNbtCharacters());
            return null;
        });
    }

    @Test
    void indexedCandidatesKeepAllModesAndOriginalCandidateOrder() {
        ImmutableRecipeGraph graph = lookupGraph(50);
        PlanningLookupCache.run(() -> {
            for (NbtMatchMode mode : NbtMatchMode.values()) {
                for (String nbt : List.of("", REQUIRED, STOCKED, "{Damage:4,Unbreakable:1b}",
                        "{Enchantments:[{id:\"test:sharpness\",lvl:1s}]}", "{other:1}")) {
                    MaterialRef wanted = new MaterialRef(TOOL, nbt);
                    assertEquals(referenceCandidates(graph, wanted, mode),
                            ImmutableRecipeGraphProjector.candidates(graph, wanted, mode));
                }
            }
            assertTrue(ImmutableRecipeGraphProjector.candidates(graph,
                    new MaterialRef(id("unknown_output"), REQUIRED), NbtMatchMode.PARTIAL).isEmpty());
            assertEquals(1, PlanningLookupCache.currentStats().outputIndexBuilds());
            List<MaterialRef> variants = PlanningLookupCache.outputVariants(graph, TOOL);
            assertThrows(UnsupportedOperationException.class, () -> variants.clear());
            return null;
        });
    }

    @Test
    void graphAndVariantCapacityLimitsKeepTheFullCandidateSet() {
        ImmutableRecipeGraph graph = lookupGraph(5);
        ImmutableRecipeGraph other = lookupGraph(6);
        for (PlanningLookupCache.Limits limits : List.of(UNCACHED,
                new PlanningLookupCache.Limits(1, 20, 1, 1),
                new PlanningLookupCache.Limits(1, 20, 10, graph.recipesByOutput().size()),
                new PlanningLookupCache.Limits(1, 20, 1, 1000))) {
            PlanningLookupCache.run(limits, () -> {
                for (ImmutableRecipeGraph current : List.of(graph, other, graph, other)) {
                    MaterialRef wanted = new MaterialRef(TOOL, REQUIRED);
                    assertEquals(referenceCandidates(current, wanted, NbtMatchMode.PARTIAL),
                            ImmutableRecipeGraphProjector.candidates(current, wanted, NbtMatchMode.PARTIAL));
                }
                assertTrue(PlanningLookupCache.currentStats().indexedGraphs() <= limits.maxGraphs());
                assertTrue(PlanningLookupCache.currentStats().indexedVariants() <= limits.maxOutputVariants());
                return null;
            });
        }
    }

    @Test
    void graphIndexesAreIdentityScopedAndDoNotSurviveTheRequest() {
        ImmutableRecipeGraph graph = lookupGraph(1);
        ImmutableRecipeGraph equalGraph = new ImmutableRecipeGraph(graph.recipesByOutput(), graph.recipesById());
        assertEquals(graph, equalGraph);
        PlanningLookupCache.run(() -> {
            PlanningLookupCache.outputVariants(graph, TOOL);
            PlanningLookupCache.outputVariants(equalGraph, TOOL);
            assertEquals(2, PlanningLookupCache.currentStats().outputIndexBuilds());
            return null;
        });
        PlanningLookupCache.run(() -> {
            assertEquals(0, PlanningLookupCache.currentStats().indexedGraphs());
            PlanningLookupCache.outputVariants(graph, TOOL);
            assertEquals(1, PlanningLookupCache.currentStats().outputIndexBuilds());
            return null;
        });
    }

    @Test
    void replacementRecipeGraphDoesNotReuseOldCandidates() {
        MaterialRef output = new MaterialRef(TOOL, REQUIRED);
        RecipeNode oldRecipe = new RecipeNode(id("old_recipe"), output, 1, List.of());
        RecipeNode newRecipe = new RecipeNode(id("new_recipe"), output, 2, List.of());
        ImmutableRecipeGraph oldGraph = new ImmutableRecipeGraph(Map.of(output, List.of(oldRecipe)));
        ImmutableRecipeGraph newGraph = new ImmutableRecipeGraph(Map.of(output, List.of(newRecipe)));
        PlanningLookupCache.run(() -> {
            assertEquals(List.of(oldRecipe), ImmutableRecipeGraphProjector.candidates(oldGraph, output, NbtMatchMode.PARTIAL));
            assertEquals(List.of(newRecipe), ImmutableRecipeGraphProjector.candidates(newGraph, output, NbtMatchMode.PARTIAL));
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"generic", "smelting", "wr_arcane_workbench", "malum_runic_workbench"})
    void machineProjectionPlanningAndInspectionAreUnchanged(String machineType) {
        MaterialRef raw = new MaterialRef(id("raw"), "{quality:1,owner:7}");
        MaterialRef requiredRaw = new MaterialRef(raw.itemId(), "{quality:1}");
        MaterialRef intermediate = new MaterialRef(id("intermediate"), "{quality:2}");
        MaterialRef output = new MaterialRef(id("finished"), "");
        RecipeNode producer = new RecipeNode(id("producer"), intermediate, 1,
                List.of(new IngredientRef(List.of(requiredRaw), 1, NbtMatchMode.PARTIAL)),
                machineType, id("machine_recipe_type"));
        RecipeNode terminal = new RecipeNode(id("terminal"), output, 1,
                List.of(new IngredientRef(List.of(intermediate), 1, NbtMatchMode.PARTIAL)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                intermediate, List.of(producer), output, List.of(terminal)));
        for (Map<MaterialRef, Integer> stock : List.of(Map.of(raw, 1), Map.<MaterialRef, Integer>of())) {
            PureRecipePlanner.Result uncached = PlanningLookupCache.run(UNCACHED, () ->
                    PureRecipePlanner.resolve(graph, stock, terminal.inputs(), 32));
            PureRecipePlanner.Result cached = PureRecipePlanner.resolve(graph, stock, terminal.inputs(), 32);
            assertEquals(uncached, cached);
            assertEquals(!stock.isEmpty(), cached.feasible());
            PureDemandTreeInspector.Result uncachedTree = PlanningLookupCache.run(UNCACHED, () ->
                    PureDemandTreeInspector.inspect(graph, stock, terminal.recipeId(), 1));
            assertEquals(uncachedTree, PureDemandTreeInspector.inspect(graph, stock, terminal.recipeId(), 1));
        }
    }

    @Test
    void taglessExactLookupKeepsTheDirectMapFastPath() {
        ImmutableRecipeGraph graph = lookupGraph(100);
        MaterialRef wanted = new MaterialRef(TOOL, "");
        PlanningLookupCache.run(() -> {
            assertSame(graph.recipesByOutput().get(wanted),
                    ImmutableRecipeGraphProjector.candidates(graph, wanted, NbtMatchMode.EXACT));
            assertEquals(0, PlanningLookupCache.currentStats().outputIndexBuilds());
            assertEquals(0, PlanningLookupCache.currentStats().outputScans());
            return null;
        });
    }

    @Test
    void changingInventoryInsideOneScopeDoesNotReuseOldFeasibility() {
        MaterialRef raw = new MaterialRef(id("raw"), "{quality:1}");
        MaterialRef output = new MaterialRef(id("output"), "{quality:2}");
        MaterialRef terminalOutput = new MaterialRef(id("terminal_output"), "");
        RecipeNode producer = new RecipeNode(id("producer"), output, 1,
                List.of(new IngredientRef(List.of(raw), 1, NbtMatchMode.PARTIAL)));
        RecipeNode terminal = new RecipeNode(id("terminal"), terminalOutput, 1,
                List.of(new IngredientRef(List.of(output), 1, NbtMatchMode.PARTIAL)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                output, List.of(producer), terminalOutput, List.of(terminal)));
        PlanningLookupCache.run(() -> {
            for (int count : List.of(1, 0, 1)) {
                Map<MaterialRef, Integer> stock = Map.of(raw, count);
                assertEquals(count == 1, PureRecipePlanner.resolve(graph, stock, terminal.inputs(), 32).feasible());
                assertEquals(count == 1 ? PureDemandTreeInspector.Status.COMPLETE
                                : PureDemandTreeInspector.Status.MISSING_MATERIALS,
                        PureDemandTreeInspector.inspect(graph, stock, terminal.recipeId(), 1).status());
            }
            assertEquals(1, PlanningLookupCache.currentStats().outputIndexBuilds());
            return null;
        });
    }

    @Test
    void repeatedLookupsReduceWorkWithoutDroppingAnyCandidates() {
        ImmutableRecipeGraph graph = lookupGraph(10_000);
        MaterialRef wanted = new MaterialRef(TOOL, "");
        List<RecipeNode> expected = referenceCandidates(graph, wanted, NbtMatchMode.ANY);
        List<PlanningLookupCache.Stats> samples = new ArrayList<>();
        for (PlanningLookupCache.Limits limits : List.of(UNCACHED, PlanningLookupCache.DEFAULT_LIMITS)) {
            samples.add(PlanningLookupCache.run(limits, () -> {
                for (int repetition = 0; repetition < 100; repetition++) {
                    assertEquals(expected, ImmutableRecipeGraphProjector.candidates(graph, wanted, NbtMatchMode.ANY));
                    assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(REQUIRED, STOCKED));
                }
                return PlanningLookupCache.currentStats();
            }));
        }
        PlanningLookupCache.Stats uncached = samples.get(0);
        PlanningLookupCache.Stats cached = samples.get(1);
        assertEquals((long) graph.recipesByOutput().size() * 100, uncached.outputScans());
        assertEquals(graph.recipesByOutput().size(), cached.outputScans());
        assertEquals(uncached.candidateVariants(), cached.candidateVariants());
        assertEquals(200, uncached.nbtParses());
        assertEquals(2, cached.nbtParses());
        System.out.printf("[RSI-lookup-work] uncachedScans=%d cachedScans=%d candidateVariants=%d uncachedNbtParses=%d cachedNbtParses=%d%n",
                uncached.outputScans(), cached.outputScans(), cached.candidateVariants(),
                uncached.nbtParses(), cached.nbtParses());
    }

    private static PlanningLookupCache.Stats parallelRequest(String tag, CyclicBarrier barrier) {
        PlanningLookupCache.Stats stats = PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(tag, tag));
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(tag, tag));
            return PlanningLookupCache.currentStats();
        });
        assertFalse(PlanningLookupCache.isActive());
        return stats;
    }

    private static boolean referenceNbt(String expectedSnbt, String actualSnbt, boolean partial) {
        if (!partial && Objects.equals(expectedSnbt, actualSnbt)) return true;
        try {
            CompoundTag expected = expectedSnbt == null || expectedSnbt.isBlank() ? null : TagParser.parseTag(expectedSnbt);
            CompoundTag actual = actualSnbt == null || actualSnbt.isBlank() ? null : TagParser.parseTag(actualSnbt);
            return IngredientMatcher.nbtMatches(expected, actual, partial);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<RecipeNode> referenceCandidates(ImmutableRecipeGraph graph, MaterialRef wanted, NbtMatchMode mode) {
        if (mode == NbtMatchMode.EXACT && wanted.nbt().isEmpty()) {
            return graph.recipesByOutput().getOrDefault(wanted, List.of());
        }
        List<RecipeNode> candidates = new ArrayList<>();
        graph.recipesByOutput().forEach((output, recipes) -> {
            if (!output.itemId().equals(wanted.itemId())) return;
            for (RecipeNode recipe : recipes) {
                if (mode == NbtMatchMode.ANY || referenceNbt(wanted.nbt(), output.nbt(), mode == NbtMatchMode.PARTIAL)) {
                    candidates.add(recipe);
                } else if (!wanted.nbt().isEmpty() && "smithing".equals(recipe.modTypeId())) {
                    RecipeNode specialized = ImmutableRecipeGraphProjector.withDemandedOutput(recipe, wanted, mode);
                    if (specialized != null && referenceNbt(wanted.nbt(), specialized.output().nbt(), mode == NbtMatchMode.PARTIAL)) {
                        candidates.add(specialized);
                    }
                }
            }
        });
        return candidates;
    }

    private static ImmutableRecipeGraph lookupGraph(int unrelatedOutputs) {
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        MaterialRef raw = new MaterialRef(id("raw"), "");
        List<String> tags = List.of("", REQUIRED, STOCKED,
                "{Damage:0,Unbreakable:1b,RepairCost:8,itemModifier:\"celestial_forge:vicious\"}",
                "{Damage:4,Unbreakable:1b}", "{Enchantments:[{id:\"test:sharpness\",lvl:1s}]}",
                "{Potion:\"minecraft:water\"}");
        for (int variant = 0; variant < tags.size(); variant++) {
            MaterialRef output = new MaterialRef(TOOL, tags.get(variant));
            List<RecipeNode> recipes = new ArrayList<>();
            recipes.add(new RecipeNode(id("variant_" + variant), output, 1, List.of(any(raw))));
            if (variant == 0) {
                recipes.add(new RecipeNode(id("smithing_variant"), output, 1,
                        List.of(any(raw), any(raw), any(raw)), "smithing", id("smithing")));
            }
            outputs.put(output, recipes);
        }
        for (int index = 0; index < unrelatedOutputs; index++) {
            MaterialRef output = new MaterialRef(id("unrelated_" + index), "");
            outputs.put(output, List.of(new RecipeNode(id("unrelated_recipe_" + index), output, 1, List.of(any(raw)))));
        }
        return new ImmutableRecipeGraph(outputs);
    }

    private static IngredientRef any(MaterialRef material) {
        return new IngredientRef(List.of(material), 1, NbtMatchMode.ANY);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
