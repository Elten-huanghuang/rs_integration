package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningLookupCache.PreparationStage;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphPreparationReuseTest extends BootstrapTest {
    private static final PlanningLookupCache.Limits UNCACHED = new PlanningLookupCache.Limits(0, 0, 0, 0);
    private static final MaterialRef RAW = material("raw", "");
    private static final MaterialRef RAW_FIRST = material("raw", "{quality:1,owner:1}");
    private static final MaterialRef RAW_SECOND = material("raw", "{quality:1,owner:2}");
    private static final MaterialRef INTERMEDIATE = material("intermediate", "{stage:1}");
    private static final ResourceLocation PRODUCER = id("producer");
    private static final ResourceLocation TERMINAL = id("terminal");

    @Test
    void identicalPreparationIsReusedWithoutConflatingItsStages() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        PlanningLookupCache.run(() -> {
            assertSame(graph, ImmutableRecipeGraphProjector.bindSmithingStates(graph, stock));
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            ImmutableRecipeGraph second = ImmutableRecipeGraphProjector.bindAvailability(graph, new LinkedHashMap<>(stock));
            assertSame(first, second);
            assertNotSame(graph, first);
            assertTrue(boundInputs(first).contains(RAW_FIRST));
            assertFalse(boundInputs(graph).contains(RAW_FIRST));
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).hits());
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.SMITHING).builds());
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.SMITHING).hits());
            return null;
        });
        assertFalse(PlanningLookupCache.isActive());
    }

    @Test
    void changingCountsOnTheSameMapCannotReturnStaleBindings() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            stock.put(RAW_FIRST, 0);
            stock.put(RAW_SECOND, 1);
            ImmutableRecipeGraph second = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            assertFalse(boundInputs(second).contains(RAW_FIRST));
            assertTrue(boundInputs(second).contains(RAW_SECOND));
            stock.put(RAW_SECOND, 2);
            ImmutableRecipeGraph third = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            assertNotSame(second, third);
            stock.put(RAW_FIRST, 1);
            stock.put(RAW_SECOND, 0);
            assertSame(first, ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
            assertEquals(3, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).hits());
            return null;
        });
    }

    @Test
    void changingNbtWithoutChangingItemOrCountInvalidatesPreparation() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>(Map.of(RAW_FIRST, 1));
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            stock.clear();
            stock.put(RAW_SECOND, 1);
            ImmutableRecipeGraph second = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            assertTrue(boundInputs(first).contains(RAW_FIRST));
            assertFalse(boundInputs(second).contains(RAW_FIRST));
            assertTrue(boundInputs(second).contains(RAW_SECOND));
            assertEquals(2, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            return null;
        });
    }

    @Test
    void equalMapsWithDifferentIterationOrderKeepTheirOriginalAlternativeOrder() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> firstStock = stock(1, 1);
        Map<MaterialRef, Integer> reversedStock = new LinkedHashMap<>();
        reversedStock.put(RAW_SECOND, 1);
        reversedStock.put(RAW_FIRST, 1);
        assertEquals(firstStock, reversedStock);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, firstStock);
            ImmutableRecipeGraph second = ImmutableRecipeGraphProjector.bindAvailability(graph, reversedStock);
            assertEquals(List.of(RAW, RAW_FIRST, RAW_SECOND), boundInputs(first));
            assertEquals(List.of(RAW, RAW_SECOND, RAW_FIRST), boundInputs(second));
            assertEquals(2, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            assertEquals(0, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).hits());
            return null;
        });
    }

    @Test
    void equalReplacementGraphsArePreparedIndependently() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        ImmutableRecipeGraph replacement = new ImmutableRecipeGraph(graph.recipesByOutput(), graph.recipesById());
        assertEquals(graph, replacement);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock(1, 0));
            ImmutableRecipeGraph second = ImmutableRecipeGraphProjector.bindAvailability(replacement, stock(1, 0));
            assertNotSame(first, second);
            assertEquals(first, second);
            assertEquals(2, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            return null;
        });
    }

    @Test
    void zeroAndNullStockEntriesAreNotTreatedAsAvailable() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(0, 0);
        stock.put(RAW_FIRST, null);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            assertEquals(List.of(RAW), boundInputs(first));
            assertSame(first, ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
            stock.put(RAW_FIRST, 1);
            assertTrue(boundInputs(ImmutableRecipeGraphProjector.bindAvailability(graph, stock)).contains(RAW_FIRST));
            return null;
        });
    }

    @Test
    void preparationIsNotRetainedWhenItsInputChangesDuringWork() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        AtomicInteger builds = new AtomicInteger();
        PlanningLookupCache.run(() -> {
            PlanningLookupCache.reusePreparation(PreparationStage.INVENTORY, graph, stock, () -> {
                builds.incrementAndGet();
                stock.put(RAW_FIRST, 2);
                return graph;
            });
            assertEquals(0, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).retainedGraphs());
            PlanningLookupCache.reusePreparation(PreparationStage.INVENTORY, graph, stock, () -> {
                builds.incrementAndGet();
                return graph;
            });
            assertEquals(2, builds.get());
            return null;
        });
    }

    @Test
    void failedPreparationIsNotCachedAndDoesNotPreventRetry() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        PlanningLookupCache.run(() -> {
            assertThrows(IllegalStateException.class, () -> PlanningLookupCache.reusePreparation(
                    PreparationStage.INVENTORY, graph, stock, () -> { throw new IllegalStateException("test failure"); }));
            assertEquals(0, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).retainedGraphs());
            assertSame(graph, PlanningLookupCache.reusePreparation(PreparationStage.INVENTORY, graph, stock, () -> graph));
            assertEquals(2, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            return null;
        });
        assertFalse(PlanningLookupCache.isActive());
    }

    @Test
    void cancellationDoesNotReturnACachedPreparation() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            try {
                Thread.currentThread().interrupt();
                assertThrows(CancellationException.class,
                        () -> ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
            } finally {
                Thread.interrupted();
            }
            return null;
        });
        assertFalse(PlanningLookupCache.isActive());
    }

    @Test
    void nestedCacheCapacityExhaustionRecomputesInsteadOfAbortingPlanning() {
        ImmutableRecipeGraph graph = graph("generic", 20);
        Map<MaterialRef, Integer> stock = stock(1_000_000, 0);
        ImmutableRecipeGraph expected = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
        PureRecipePlanner.Result expectedPlan = PureRecipePlanner.resolve(expected, stock,
                expected.recipesById().get(TERMINAL).inputs(), 32);
        for (PlanningLookupCache.Limits limits : List.of(UNCACHED,
                new PlanningLookupCache.Limits(1, 10, 1, 1000),
                new PlanningLookupCache.Limits(1, 10, 10, 1),
                new PlanningLookupCache.Limits(1, 10, 10, graph.recipesByOutput().size() + stock.size()))) {
            PlanningLookupCache.run(limits, () -> {
                for (int repetition = 0; repetition < 5; repetition++) {
                    ImmutableRecipeGraph prepared = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
                    assertEquals(expected, prepared);
                    PureRecipePlanner.Result actual = PureRecipePlanner.resolve(prepared, stock,
                            prepared.recipesById().get(TERMINAL).inputs(), 32);
                    assertTrue(actual.feasible());
                    assertEquals(expectedPlan, actual);
                }
                var stats = PlanningLookupCache.preparationStats(PreparationStage.INVENTORY);
                assertTrue(stats.retainedGraphs() <= limits.maxGraphs());
                assertTrue(stats.retainedUnits() <= limits.maxOutputVariants());
                return null;
            });
        }
    }

    @Test
    void preparationAndTimingsAreScopedToEachRequest() {
        ImmutableRecipeGraph graph = graph("generic", 0);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        PlanningLookupCache.run(() -> {
            ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            var stats = PlanningLookupCache.preparationStats(PreparationStage.INVENTORY);
            assertEquals(1, stats.builds());
            assertEquals(1, stats.hits());
            assertTrue(stats.elapsedNanos() >= stats.maxNanos());
            assertTrue(stats.maxNanos() >= 0);
            return null;
        });
        assertFalse(PlanningLookupCache.isActive());
        PlanningLookupCache.run(() -> {
            assertEquals(0, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).retainedGraphs());
            assertEquals(0, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            return null;
        });
        String report = PerformanceMonitor.snapshot();
        assertTrue(report.matches("(?s).*prepareInventory=\\d+/\\d+/\\d+/\\d+us.*"));
        assertTrue(report.matches("(?s).*prepareSmithing=\\d+/\\d+/\\d+/\\d+us.*"));
    }

    @Test
    void concurrentRequestsPrepareTheirOwnInventoryVariants() throws Exception {
        ImmutableRecipeGraph graph = graph("generic", 0);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> prepareConcurrently(graph, RAW_FIRST, barrier));
            var second = workers.submit(() -> prepareConcurrently(graph, RAW_SECOND, barrier));
            assertEquals(List.of(RAW, RAW_FIRST), boundInputs(first.get(10, TimeUnit.SECONDS)));
            assertEquals(List.of(RAW, RAW_SECOND), boundInputs(second.get(10, TimeUnit.SECONDS)));
        } finally {
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"generic", "smelting", "wr_arcane_workbench", "malum_runic_workbench"})
    void preparedMachineGraphsKeepTheirPlansAndDemandTrees(String machineType) {
        ImmutableRecipeGraph graph = graph(machineType, 0);
        Map<MaterialRef, Integer> stock = stock(1, 1);
        ImmutableRecipeGraph expected = PlanningLookupCache.run(UNCACHED,
                () -> ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
        PureRecipePlanner.Result expectedPlan = PureRecipePlanner.resolve(expected, stock,
                expected.recipesById().get(TERMINAL).inputs(), 32);
        PureDemandTreeInspector.Result expectedTree = PureDemandTreeInspector.inspect(expected, stock, TERMINAL, 1);
        PlanningLookupCache.run(() -> {
            for (int repetition = 0; repetition < 3; repetition++) {
                ImmutableRecipeGraph prepared = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
                assertEquals(expected, prepared);
                assertEquals(expectedPlan, PureRecipePlanner.resolve(prepared, stock,
                        prepared.recipesById().get(TERMINAL).inputs(), 32));
                assertEquals(expectedTree, PureDemandTreeInspector.inspect(prepared, stock, TERMINAL, 1));
            }
            assertTrue(expectedPlan.feasible());
            assertEquals(PureDemandTreeInspector.Status.COMPLETE, expectedTree.status());
            assertEquals(1, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).builds());
            assertEquals(2, PlanningLookupCache.preparationStats(PreparationStage.INVENTORY).hits());
            return null;
        });
    }

    @Test
    void repeatedLargeGraphPreparationDoesNotRebuildEveryTime() {
        ImmutableRecipeGraph graph = graph("generic", 10_000);
        Map<MaterialRef, Integer> stock = stock(1, 0);
        ImmutableRecipeGraph expected = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
        List<PlanningLookupCache.PreparationStats> samples = new ArrayList<>();
        for (PlanningLookupCache.Limits limits : List.of(UNCACHED, PlanningLookupCache.DEFAULT_LIMITS)) {
            samples.add(PlanningLookupCache.run(limits, () -> {
                for (int repetition = 0; repetition < 12; repetition++) {
                    assertEquals(expected, ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
                }
                return PlanningLookupCache.preparationStats(PreparationStage.INVENTORY);
            }));
        }
        assertEquals(12, samples.get(0).builds());
        assertEquals(1, samples.get(1).builds());
        assertEquals(11, samples.get(1).hits());
        System.out.printf("[RSI-preparation-work] outputVariants=%d uncachedBuilds=%d cachedBuilds=%d reused=%d%n",
                graph.recipesByOutput().size(), samples.get(0).builds(), samples.get(1).builds(), samples.get(1).hits());
    }

    private static ImmutableRecipeGraph prepareConcurrently(ImmutableRecipeGraph graph, MaterialRef material,
                                                             CyclicBarrier barrier) {
        ImmutableRecipeGraph result = PlanningLookupCache.run(() -> {
            Map<MaterialRef, Integer> stock = Map.of(material, 1);
            ImmutableRecipeGraph first = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            assertSame(first, ImmutableRecipeGraphProjector.bindAvailability(graph, stock));
            return first;
        });
        assertFalse(PlanningLookupCache.isActive());
        return result;
    }

    private static Map<MaterialRef, Integer> stock(int firstCount, int secondCount) {
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(RAW_FIRST, firstCount);
        stock.put(RAW_SECOND, secondCount);
        return stock;
    }

    private static List<MaterialRef> boundInputs(ImmutableRecipeGraph graph) {
        return graph.recipesById().get(PRODUCER).inputs().get(0).alternatives();
    }

    private static ImmutableRecipeGraph graph(String machineType, int unrelatedOutputs) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(INTERMEDIATE, List.of(new RecipeNode(PRODUCER, INTERMEDIATE, 1,
                List.of(new IngredientRef(List.of(RAW), 1, NbtMatchMode.ANY)), machineType, id("machine_type"))));
        MaterialRef output = material("finished", "");
        recipes.put(output, List.of(new RecipeNode(TERMINAL, output, 1,
                List.of(new IngredientRef(List.of(INTERMEDIATE), 1, NbtMatchMode.PARTIAL)))));
        for (int index = 0; index < unrelatedOutputs; index++) {
            MaterialRef other = material("other_" + index, "");
            recipes.put(other, List.of(new RecipeNode(id("other_recipe_" + index), other, 1,
                    List.of(new IngredientRef(List.of(RAW), 1, NbtMatchMode.ANY)))));
        }
        return new ImmutableRecipeGraph(recipes);
    }

    private static MaterialRef material(String path, String nbt) {
        return new MaterialRef(id(path), nbt);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
