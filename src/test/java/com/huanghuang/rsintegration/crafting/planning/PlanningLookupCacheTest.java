package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import com.huanghuang.rsintegration.crafting.planning.DeterministicRecipePlanner.CompilationCache.StockLookup;

import static org.junit.jupiter.api.Assertions.*;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;

class PlanningLookupCacheTest extends BootstrapTest {
    @Test
    void compilerSuffixesVisitUnrelatedInventoryOnlyDuringRequestIndexConstruction() {
        MaterialRef raw = new MaterialRef(TOOL, "{v:1}");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        List<IngredientRef> roots = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            MaterialRef output = new MaterialRef(id("suffix_" + i), "");
            recipes.put(output, List.of(new RecipeNode(id("suffix_recipe_" + i), output, 1,
                    List.of(new IngredientRef(List.of(raw), 1, NbtMatchMode.PARTIAL)))));
            roots.add(new IngredientRef(List.of(output), 1));
        }
        var graph = new ImmutableRecipeGraph(recipes);
        int withoutUnrelated = 0;
        for (int unrelated : new int[] {0, 10000}) {
            Map<MaterialRef, Long> stock = new LinkedHashMap<>();
            stock.put(raw, 128L);
            for (int i = 0; i < unrelated; i++) stock.put(new MaterialRef(id("suffix_unrelated_" + i), ""), 1L);
            var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(), PlanningPreferences.empty(), 65_536);
            cache.trackStock(stock);
            AtomicInteger work = new AtomicInteger();
            for (int i = 0; i < roots.size(); i++) {
                // 这些 PARTIAL 输入仍交回普通搜索；这里只验证真实编译和评估阶段的工作量。
                assertNull(cache.prepare(stock, roots.subList(i, roots.size()), Set.of(), 1000, 65_536,
                        work::incrementAndGet));
            }
            if (unrelated == 0) withoutUnrelated = work.get();
            else assertTrue(work.get() - withoutUnrelated <= unrelated + 128 * 128,
                    "新增库存只能建一次索引，不能再乘每个编译后缀: " + work.get());
        }
    }

    @Test
    void compilerStockFamiliesShareOneBuildAndTrackPositiveVariantsThroughRollback() {
        MaterialRef family = new MaterialRef(TOOL, "");
        MaterialRef first = new MaterialRef(TOOL, "{v:1}");
        MaterialRef second = new MaterialRef(TOOL, "{v:2}");
        MaterialRef fluid = MaterialRef.fluid(TOOL);
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        stock.put(first, 1L); stock.put(second, 2L); stock.put(fluid, 3L);
        for (int i = 0; i < 10000; i++) stock.put(new MaterialRef(id("compiler_unrelated_" + i), ""), 1L);
        var cache = new DeterministicRecipePlanner.CompilationCache(lookupGraph(2), Map.of(), PlanningPreferences.empty(), 65_536);
        cache.trackStock(stock);
        AtomicInteger work = new AtomicInteger();
        for (int i = 0; i < 128; i++) {
            assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
            assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(fluid));
        }
        assertEquals(stock.size(), work.get());
        cache.stockChanged(first, 1, 0); stock.remove(first);
        assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        cache.stockChanged(second, 2, 0); stock.remove(second);
        assertFalse(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(fluid));
        MaterialRef created = new MaterialRef(TOOL, "{new:1}");
        cache.stockChanged(created, 0, 4); stock.put(created, 4L);
        assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        cache.stockChanged(created, 4, 0); stock.remove(created);
        assertFalse(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        cache.stockChanged(first, 0, 1); stock.put(first, 1L);
        assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        assertEquals(10003, work.get());
        assertFalse(cache.stockFamilies(Map.of(fluid, 1L), work::incrementAndGet).contains(family));
        assertTrue(cache.stockFamilies(stock, work::incrementAndGet).contains(family));
        assertEquals(10004, work.get());
        // 未完成的索引构建不能被下次调用误当成完整结果。
        cache.trackStock(stock);
        assertThrows(CancellationException.class, () -> cache.stockFamilies(stock, () -> { throw new CancellationException(); }));
        assertEquals(stock.size(), cache.stockFamilies(stock, () -> {}).size());
    }

    @Test
    void persistentFailureSnapshotsCompareExactValuesAcrossHashCollisionsAndRollback() {
        var empty = PureRecipePlanner.StateMap.<String, Long>empty();
        var first = empty.with("Aa", 1L).with("BB", 2L);
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertEquals(first, empty.with("BB", 2L).with("Aa", 1L));
        assertNotEquals(first, empty.with("Aa", 2L).with("BB", 1L));
        assertEquals(1L, first.get("Aa"));
        var changed = first.with("Aa", 4L).with("BB", null);
        assertEquals(1L, first.get("Aa"));
        assertEquals(2L, first.get("BB"));
        assertEquals(first, changed.with("BB", 2L).with("Aa", 1L));
        var forward = empty;
        var reverse = empty;
        for (int i = 0; i < 2000; i++) {
            forward = forward.with("key-" + i, (long) i);
            reverse = reverse.with("key-" + (1999 - i), (long) (1999 - i));
        }
        assertEquals(forward, reverse);
        var snapshot = forward;
        for (int i = 0; i < 2000; i++) forward = forward.with("key-" + i, null);
        assertEquals(empty, forward);
        assertEquals(2000, snapshot.size());
        assertEquals(1999L, snapshot.get("key-1999"));
    }

    @Test
    void routeStockLookupPreservesMatchingCountsAndStopsWhenSufficient() {
        MaterialRef first = new MaterialRef(TOOL, "{grade:1,owner:\"a\"}");
        MaterialRef second = new MaterialRef(TOOL, "{grade:1,owner:\"b\"}");
        MaterialRef rejected = new MaterialRef(TOOL, "{grade:2}");
        MaterialRef partial = new MaterialRef(TOOL, "{grade:1}");
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        stock.put(first, 2L);
        stock.put(second, 3L);
        stock.put(rejected, Long.MAX_VALUE);
        stock.put(MaterialRef.fluid(TOOL), Long.MAX_VALUE);
        stock.put(new MaterialRef(id("unrelated"), ""), Long.MAX_VALUE);
        IngredientRef overlapping = new IngredientRef(List.of(partial, partial,
                new MaterialRef(TOOL, "{owner:\"b\"}")), 5, NbtMatchMode.PARTIAL);
        StockLookup lookup = new StockLookup(stock, () -> {});
        assertTrue(lookup.stockCanSatisfy(overlapping, 5, () -> {}));
        assertFalse(lookup.stockCanSatisfy(overlapping, 6, () -> {}));
        assertTrue(lookup.stockCanSatisfy(new IngredientRef(List.of(first), 2), 2, () -> {}));
        assertFalse(lookup.stockCanSatisfy(new IngredientRef(List.of(first), 3), 3, () -> {}));
        assertTrue(lookup.stockCanSatisfy(new IngredientRef(List.of(partial), 1, NbtMatchMode.ANY),
                Long.MAX_VALUE, () -> {}));
        // 索引只保存键：扣减、归零、移除、恢复同一键，必须立即读取当前数量。
        stock.put(first, 0L);
        stock.put(second, 2L);
        assertFalse(lookup.stockCanSatisfy(overlapping, 3, () -> {}));
        stock.remove(second);
        assertFalse(lookup.stockCanSatisfy(overlapping, 1, () -> {}));
        stock.put(second, 3L);
        stock.put(first, 2L);
        assertTrue(lookup.stockCanSatisfy(overlapping, 5, () -> {}));
        stock.put(first, null);
        stock.put(second, -1L);
        assertFalse(lookup.stockCanSatisfy(overlapping, 1, () -> {}));
        stock.put(first, 10L);
        for (int i = 0; i < 1000; i++) stock.put(new MaterialRef(TOOL, "{tail:" + i + "}"), 1L);
        lookup = new StockLookup(stock, () -> {});
        AtomicInteger checks = new AtomicInteger();
        assertTrue(lookup.stockCanSatisfy(new IngredientRef(List.of(partial), 10, NbtMatchMode.ANY),
                10, checks::incrementAndGet));
        assertEquals(2, checks.get(), "只检查一个资源族和首个足量变体");
        assertFalse(new StockLookup(Map.of(MaterialRef.fluid(TOOL), Long.MAX_VALUE), () -> {})
                .stockCanSatisfy(new IngredientRef(List.of(first), 1, NbtMatchMode.ANY), 1, () -> {}));
    }

    @Test
    void routeStockLookupKeepsSpecialNbtAndAlternativeSemantics() {
        List<MaterialRef> variants = List.of(
                new MaterialRef(TOOL, ""), new MaterialRef(TOOL, REQUIRED), new MaterialRef(TOOL, STOCKED),
                new MaterialRef(TOOL, "{Damage:2}"), new MaterialRef(TOOL, "", true),
                new MaterialRef(TOOL, "{Damage:1}", false, true),
                new MaterialRef(POTION, AWKWARD_POTION),
                new MaterialRef(POTION, "{Potion:\"minecraft:awkward\",Purity:2}"),
                new MaterialRef(EARTH_HEART, ""), new MaterialRef(EARTH_HEART, "{isTainted:1b}"),
                new MaterialRef(EARTH_HEART, "{isTainted:1}"));
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        for (int i = 0; i < variants.size(); i++) stock.put(variants.get(i), (long) (i + 1));
        StockLookup lookup = new StockLookup(stock, () -> {});
        for (NbtMatchMode mode : NbtMatchMode.values()) {
            for (MaterialRef variant : variants) {
                IngredientRef demand = new IngredientRef(List.of(variant, variants.get(0), variant), 1, mode);
                long expected = stock.entrySet().stream()
                        .filter(entry -> ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), demand))
                        .mapToLong(Map.Entry::getValue).sum();
                assertEquals(expected > 0, lookup.stockCanSatisfy(demand, 1, () -> {}));
                assertTrue(lookup.stockCanSatisfy(demand, expected, () -> {}));
                assertFalse(lookup.stockCanSatisfy(demand, expected + 1, () -> {}));
            }
        }
        IngredientRef taintedHeart = new IngredientRef(
                List.of(new MaterialRef(EARTH_HEART, "{isTainted:1}")), 1, NbtMatchMode.EXACT);
        // 数值标签类型不同，必须保留大地之心的特殊匹配，而非仅比较 NBT 文本。
        assertTrue(new StockLookup(Map.of(new MaterialRef(EARTH_HEART, "{isTainted:1b}"), 1L), () -> {})
                .stockCanSatisfy(taintedHeart, 1, () -> {}));
        assertFalse(new StockLookup(Map.of(new MaterialRef(EARTH_HEART, ""), 1L), () -> {})
                .stockCanSatisfy(taintedHeart, 1, () -> {}));
        var bladeDemand = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(ingredient("ak:rage", 1000, 10), 1));
        var richer = ImmutableRecipeGraphProjector.material(blade("ak:rage", 1200, 15), true);
        var insufficient = ImmutableRecipeGraphProjector.material(blade("ak:rage", 999, 15), true);
        var wrong = ImmutableRecipeGraphProjector.material(blade("ak:greedy", 1200, 15), true);
        assertTrue(new StockLookup(Map.of(richer, 1L), () -> {}).stockCanSatisfy(bladeDemand, 1, () -> {}));
        assertFalse(new StockLookup(Map.of(insufficient, 1L, wrong, 1L), () -> {})
                .stockCanSatisfy(bladeDemand, 1, () -> {}));
    }

    @Test
    void routePrecheckRefreshesStockAndReusesLookupForOneStepSupply() {
        MaterialRef output = new MaterialRef(id("one_step_target"), "");
        MaterialRef middle = new MaterialRef(id("one_step_middle"), "");
        MaterialRef raw = new MaterialRef(id("one_step_raw"), "");
        MaterialRef other = new MaterialRef(id("one_step_other"), "");
        IngredientRef rawTwo = new IngredientRef(List.of(raw), 2);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                output, List.of(new RecipeNode(id("one_step_left"), output, 1,
                                List.of(new IngredientRef(List.of(middle), 3))),
                        new RecipeNode(id("one_step_right"), output, 1, List.of(new IngredientRef(List.of(other), 1)))),
                middle, List.of(new RecipeNode(id("one_step_supply"), middle, 2,
                        List.of(rawTwo, new IngredientRef(List.of(raw), 1))))));
        Map<MaterialRef, Long> stock = new LinkedHashMap<>(Map.of(raw, 6L, other, 1L));
        for (int i = 0; i < 3770; i++) stock.put(new MaterialRef(id("one_step_unrelated_" + i), ""), 1L);
        var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(), PlanningPreferences.empty(), 65_536);
        cache.prepare(stock, List.of(new IngredientRef(List.of(output), 1)), Set.of(), 64, 65_536, () -> {});
        assertTrue(cache.hasRouteChoices());
        AtomicInteger work = new AtomicInteger();
        assertFalse(cache.needsTransitiveRouteProbe(stock, work::incrementAndGet));
        assertTrue(work.get() < stock.size() + 30, "一步供料也共用本次库存索引");
        stock.put(raw, 5L);
        assertTrue(cache.needsTransitiveRouteProbe(stock, () -> {}));
        stock.put(middle, 3L); // 补产新增键。
        assertFalse(cache.needsTransitiveRouteProbe(stock, () -> {}));
        stock.remove(middle); // 回退撤销补产。
        assertTrue(cache.needsTransitiveRouteProbe(stock, () -> {}));
        stock.put(raw, 6L); // 恢复原库存。
        assertFalse(cache.needsTransitiveRouteProbe(stock, () -> {}));
        for (int failAt : List.of(1, stock.size(), stock.size() + 2)) {
            AtomicInteger visits = new AtomicInteger();
            assertThrows(DeterministicRecipePlanner.ProbeLimitReached.class, () ->
                    cache.needsTransitiveRouteProbe(stock, () -> {
                        if (visits.incrementAndGet() == failAt) throw new DeterministicRecipePlanner.ProbeLimitReached();
                    }));
            assertEquals(failAt, visits.get());
            assertFalse(cache.needsTransitiveRouteProbe(stock, () -> {}));
        }
        CancellationException cancelled = new CancellationException();
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> cache.needsTransitiveRouteProbe(stock, () -> { throw cancelled; })));
        IllegalStateException deadline = new IllegalStateException("测试请求截止时间");
        assertSame(deadline, assertThrows(IllegalStateException.class,
                () -> cache.needsTransitiveRouteProbe(stock, () -> { throw deadline; })));
    }
    @Test
    void routeInputChecksIndexUnrelatedStockOnlyOnce() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        List<IngredientRef> roots = new ArrayList<>();
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            MaterialRef output = new MaterialRef(id("review_choice_" + i), "");
            MaterialRef left = new MaterialRef(id("review_raw_a_" + i), "");
            MaterialRef right = new MaterialRef(id("review_raw_b_" + i), "");
            recipes.put(output, List.of(
                    new RecipeNode(id("review_a_" + i), output, 1L, List.of(new IngredientRef(List.of(left), 1L))),
                    new RecipeNode(id("review_b_" + i), output, 1L, List.of(new IngredientRef(List.of(right), 1L)))));
            stock.put(left, 1L);
            stock.put(right, 1L);
            roots.add(new IngredientRef(List.of(output), 1L));
        }
        for (int i = 40; i < 3770; i++) stock.put(new MaterialRef(id("review_unrelated_" + i), ""), 1L);
        var cache = new DeterministicRecipePlanner.CompilationCache(new ImmutableRecipeGraph(recipes),
                Map.of(), PlanningPreferences.empty(), 65_536);
        cache.prepare(stock, roots, Set.of(), 64, 65_536, () -> {});
        assertTrue(cache.hasRouteChoices());
        for (int repeat = 0; repeat < 3; repeat++) {
            AtomicInteger visits = new AtomicInteger();
            assertFalse(cache.needsTransitiveRouteProbe(stock, visits::incrementAndGet));
            System.out.println("ROUTE_STOCK_VISITS repeat=" + repeat + " stockTypes=" + stock.size()
                    + " routeInputs=40 work=" + visits.get());
            // 建索引每个键一次；每个输入各计费：端口、资源族、足量的首个变体。
            assertEquals(stock.size() + 40 * 3, visits.get());
        }
        for (int i = 3770; i < 4770; i++) stock.put(new MaterialRef(id("review_unrelated_" + i), ""), 1L);
        AtomicInteger larger = new AtomicInteger();
        assertFalse(cache.needsTransitiveRouteProbe(stock, larger::incrementAndGet));
        assertEquals(4770 + 40 * 3, larger.get());
        System.out.println("ROUTE_STOCK_VISITS stockTypes=" + stock.size() + " routeInputs=40 work=" + larger.get());
    }

    @Test
    void fifthDerivedGraphEvictsOnlyLeastRecentlyUsedIndex() {
        ImmutableRecipeGraphProjector.clearCache();
        MaterialRef raw = new MaterialRef(id("review_cache_raw"), "");
        MaterialRef output = new MaterialRef(id("review_cache_output"), "");
        RecipeNode producer = new RecipeNode(id("review_cache_recipe"), output, 1L,
                List.of(new IngredientRef(List.of(raw), 1L)));
        ImmutableRecipeGraph hot = new ImmutableRecipeGraph(Map.of(output, List.of(producer)));
        AtomicInteger builds = new AtomicInteger();
        var initial = countedProducerIndex(hot, builds);
        ImmutableRecipeGraph oldest = new ImmutableRecipeGraph(hot.recipesByOutput());
        var oldestIndex = countedProducerIndex(oldest, builds);
        List<ImmutableRecipeGraph> others = new ArrayList<>();
        List<RecipeProducerIndex> otherIndexes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var other = new ImmutableRecipeGraph(hot.recipesByOutput());
            others.add(other);
            otherIndexes.add(countedProducerIndex(other, builds));
        }
        assertEquals(4, builds.get());
        assertSame(initial, countedProducerIndex(hot, builds));
        countedProducerIndex(new ImmutableRecipeGraph(hot.recipesByOutput()), builds);
        assertSame(initial, countedProducerIndex(hot, builds));
        assertEquals(5, builds.get());
        System.out.println("DERIVED_LRU_BUILDS sequence=A,B,C,D,A,E,A builds=" + builds.get());
        for (int i = 0; i < others.size(); i++) assertSame(otherIndexes.get(i), countedProducerIndex(others.get(i), builds));
        assertEquals(5, builds.get());
        assertNotSame(oldestIndex, countedProducerIndex(oldest, builds));
        assertEquals(6, builds.get());
        ImmutableRecipeGraphProjector.clearCache();
    }

    private static RecipeProducerIndex countedProducerIndex(ImmutableRecipeGraph graph, AtomicInteger builds) {
        AtomicBoolean started = new AtomicBoolean();
        return PlanningLookupCache.producerIndex(graph, () -> {
            if (started.compareAndSet(false, true)) builds.incrementAndGet();
        });
    }

    @Test
    void requestLocalHitsAlsoRefreshSharedProducerRecency() {
        ImmutableRecipeGraphProjector.clearCache();
        ImmutableRecipeGraph hot = lookupGraph(2);
        ImmutableRecipeGraph oldest = new ImmutableRecipeGraph(hot.recipesByOutput());
        RecipeProducerIndex[] retained = new RecipeProducerIndex[2];
        try {
            PlanningLookupCache.run(() -> {
                retained[0] = PlanningLookupCache.producerIndex(hot);
                retained[1] = PlanningLookupCache.producerIndex(oldest);
                PlanningLookupCache.producerIndex(new ImmutableRecipeGraph(hot.recipesByOutput()));
                PlanningLookupCache.producerIndex(new ImmutableRecipeGraph(hot.recipesByOutput()));
                assertSame(retained[0], PlanningLookupCache.producerIndex(hot));
                PlanningLookupCache.producerIndex(new ImmutableRecipeGraph(hot.recipesByOutput()));
                return null;
            });
            assertSame(retained[0], PlanningLookupCache.producerIndex(hot));
            assertNotSame(retained[1], PlanningLookupCache.producerIndex(oldest));
        } finally {
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    @Test
    void reloadDoesNotRepublishOldRequestLocalProducerIndex() {
        ImmutableRecipeGraphProjector.clearCache();
        ImmutableRecipeGraph graph = lookupGraph(2);
        try {
            RecipeProducerIndex old = PlanningLookupCache.run(() -> {
                RecipeProducerIndex index = PlanningLookupCache.producerIndex(graph);
                ImmutableRecipeGraphProjector.clearCache();
                assertSame(index, PlanningLookupCache.producerIndex(graph));
                return index;
            });
            assertNotSame(old, PlanningLookupCache.producerIndex(graph));
        } finally {
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    @Test
    void concurrentProducerBuildsConvergeWithoutHoldingCacheLock() throws Exception {
        ImmutableRecipeGraphProjector.clearCache();
        ImmutableRecipeGraph graph = lookupGraph(2);
        CyclicBarrier building = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentProducerBuild(graph, building));
            var second = executor.submit(() -> concurrentProducerBuild(graph, building));
            RecipeProducerIndex result = first.get(10, TimeUnit.SECONDS);
            assertSame(result, second.get(10, TimeUnit.SECONDS));
            assertSame(result, PlanningLookupCache.producerIndex(graph));
            List<ImmutableRecipeGraph> others = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                var other = new ImmutableRecipeGraph(graph.recipesByOutput());
                others.add(other);
                PlanningLookupCache.producerIndex(other);
            }
            var hitA = executor.submit(() -> PlanningLookupCache.producerIndex(graph));
            var hitB = executor.submit(() -> PlanningLookupCache.producerIndex(graph));
            assertSame(result, hitA.get(5, TimeUnit.SECONDS));
            assertSame(result, hitB.get(5, TimeUnit.SECONDS));
            PlanningLookupCache.producerIndex(new ImmutableRecipeGraph(graph.recipesByOutput()));
            assertSame(result, PlanningLookupCache.producerIndex(graph));
        } finally {
            executor.shutdownNow();
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    private static RecipeProducerIndex concurrentProducerBuild(ImmutableRecipeGraph graph, CyclicBarrier barrier) {
        AtomicBoolean firstVisit = new AtomicBoolean(true);
        return PlanningLookupCache.producerIndex(graph, () -> {
            if (!firstVisit.compareAndSet(true, false)) return;
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Test
    void reloadDuringProducerBuildCannotReplaceNewGeneration() throws Exception {
        ImmutableRecipeGraphProjector.clearCache();
        ImmutableRecipeGraph graph = lookupGraph(2);
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            AtomicBoolean firstVisit = new AtomicBoolean(true);
            var old = executor.submit(() -> PlanningLookupCache.producerIndex(graph, () -> {
                if (!firstVisit.compareAndSet(true, false)) return;
                building.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("旧构建未恢复");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
            }));
            assertTrue(building.await(5, TimeUnit.SECONDS));
            var current = executor.submit(() -> {
                ImmutableRecipeGraphProjector.clearCache();
                return PlanningLookupCache.producerIndex(graph);
            }).get(5, TimeUnit.SECONDS);
            resume.countDown();
            assertNotSame(current, old.get(5, TimeUnit.SECONDS));
            assertSame(current, PlanningLookupCache.producerIndex(graph));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    @Test
    void staticDependencyIndexUsesGraphIdentityAndReloadBoundary() {
        MaterialRef raw = new MaterialRef(id("index_seed"), "");
        MaterialRef middle = new MaterialRef(id("index_middle"), "");
        MaterialRef output = new MaterialRef(id("index_target"), "");
        RecipeNode first = new RecipeNode(id("index_first"), middle, 1, List.of(new IngredientRef(List.of(raw), 1)));
        RecipeNode last = new RecipeNode(id("index_last"), output, 1, List.of(new IngredientRef(List.of(middle), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(middle, List.of(first), output, List.of(last)));
        ImmutableRecipeGraphProjector.clearCache();
        for (int run = 0; run < 3; run++) {
            int repeat = run;
            PlanningLookupCache.run(() -> {
                var reach = new PureRecipePlanner.SeededReachability(graph,
                        repeat == 1 ? Map.of() : Map.of(raw, 1L), () -> {});
                assertEquals(repeat != 1, reach.canReach(last));
                assertEquals(repeat == 0 ? 1 : 0, PlanningLookupCache.currentStats().producerIndexBuilds());
                return null;
            });
        }
        // 同配方 ID 的派生图更换输入后不能混用，目录重载也不能保留旧索引。
        RecipeNode changed = first.withOutputAndInputs(middle,
                List.of(new IngredientRef(List.of(new MaterialRef(id("other_seed"), "")), 1)));
        ImmutableRecipeGraph derived = new ImmutableRecipeGraph(Map.of(middle, List.of(changed), output, List.of(last)));
        assertNotSame(PlanningLookupCache.producerIndex(graph), PlanningLookupCache.producerIndex(derived));
        assertFalse(new PureRecipePlanner.SeededReachability(derived, Map.of(raw, 1L), () -> {}).canReach(last));
        var previous = PlanningLookupCache.producerIndex(graph);
        ImmutableRecipeGraphProjector.clearCache();
        assertNotSame(previous, PlanningLookupCache.producerIndex(graph));
        ImmutableRecipeGraphProjector.clearCache();
        AtomicBoolean reload = new AtomicBoolean();
        var interruptedGeneration = PlanningLookupCache.producerIndex(graph, () -> {
            if (reload.compareAndSet(false, true)) ImmutableRecipeGraphProjector.clearCache();
        });
        assertNotSame(interruptedGeneration, PlanningLookupCache.producerIndex(graph));
        var retained = PlanningLookupCache.producerIndex(graph);
        for (int i = 0; i < 4; i++) PlanningLookupCache.producerIndex(new ImmutableRecipeGraph(graph.recipesByOutput()));
        assertNotSame(retained, PlanningLookupCache.producerIndex(graph));
        ImmutableRecipeGraphProjector.clearCache();
    }

    @Test
    void exhaustedLocalProbeRetainsCandidatesAndMainSearchStillSolves() {
        MaterialRef raw = new MaterialRef(id("budget_raw"), "");
        MaterialRef target = new MaterialRef(id("budget_target"), "");
        List<MaterialRef> choices = new ArrayList<>();
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        for (int i = 0; i < 1200; i++) choices.add(new MaterialRef(id("budget_missing_" + i), ""));
        choices.add(raw);
        stock.put(raw, 1L);
        for (int i = 0; i < 3000; i++) stock.put(new MaterialRef(id("budget_unrelated"), "{variant:" + i + "}"), 1L);
        RecipeNode recipe = new RecipeNode(id("budget_recipe"), target, 1, List.of(new IngredientRef(choices, 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(target, List.of(recipe)));
        PlanningLookupCache.run(() -> {
            var reachable = new PureRecipePlanner.SeededReachability(graph, stock, () -> {});
            assertTrue(reachable.canReach(recipe));
            assertTrue(PlanningLookupCache.currentStats().localProbeExhaustions() > 0);
            var result = PureRecipePlanner.resolve(graph, stock, List.of(new IngredientRef(List.of(target), 1)), 64);
            assertTrue(result.feasible(), result.toString());
            return null;
        });
    }
    @Test
    void fixedLargeGraphReachabilityMeasurement() {
        MaterialRef seed = new MaterialRef(id("bench_seed"), "");
        MaterialRef target = new MaterialRef(id("bench_target"), "");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        List<RecipeNode> targets = new ArrayList<>();
        for (int i = 0; i < 8; i++) targets.add(new RecipeNode(id("bench_target_" + i), target, 1,
                List.of(new IngredientRef(List.of(seed), 1))));
        recipes.put(target, targets);
        for (int i = 0; i < 12102; i++) {
            MaterialRef output = new MaterialRef(id("bench_output_" + i), "");
            List<IngredientRef> inputs = new ArrayList<>();
            for (int j = 0; j < 5; j++) inputs.add(new IngredientRef(List.of(
                    new MaterialRef(id("bench_raw_" + (i % 3770)), "")), 1));
            recipes.put(output, List.of(new RecipeNode(id("bench_recipe_" + i), output, 1, inputs)));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(recipes);
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        stock.put(seed, 1L);
        for (int i = 0; i < 3769; i++) stock.put(new MaterialRef(id("bench_raw_" + i), ""), 1L);
        var cpu = ManagementFactory.getThreadMXBean();
        PlanningLookupCache.run(() -> {
            long start = System.nanoTime();
            long startCpu = cpu.getCurrentThreadCpuTime();
            ImmutableRecipeGraphProjector.publishCompiled(null, 0, graph, 0);
            System.out.println("STATIC_INDEX_BENCH nanos=" + (System.nanoTime() - start)
                    + " cpuNanos=" + (cpu.getCurrentThreadCpuTime() - startCpu)
                    + " stats=" + PlanningLookupCache.currentStats());
            return null;
        });
        try {
            for (int repeat = 0; repeat < 6; repeat++) {
                int run = repeat;
                PlanningLookupCache.run(() -> {
                    long start = System.nanoTime();
                    long startCpu = cpu.getCurrentThreadCpuTime();
                    var reachable = new PureRecipePlanner.SeededReachability(graph, stock, () -> {});
                    for (RecipeNode node : targets) assertTrue(reachable.canReach(node));
                    long probeNanos = System.nanoTime() - start;
                    long probeCpu = cpu.getCurrentThreadCpuTime() - startCpu;
                    start = System.nanoTime();
                    startCpu = cpu.getCurrentThreadCpuTime();
                    var result = PureRecipePlanner.resolve(graph, stock,
                            List.of(new IngredientRef(List.of(target), 1, NbtMatchMode.PARTIAL)), 64);
                    assertTrue(result.feasible(), result.toString());
                    System.out.println("REACHABILITY_BENCH run=" + run + " probeNs=" + probeNanos
                            + " probeCpuNs=" + probeCpu + " planNs=" + (System.nanoTime() - start)
                            + " planCpuNs=" + (cpu.getCurrentThreadCpuTime() - startCpu)
                            + " states=" + result.expandedStates() + " steps=" + result.steps()
                            + " stats=" + PlanningLookupCache.currentStats());
                    return null;
                });
            }
        } finally {
            ImmutableRecipeGraphProjector.clearCache();
        }
    }
    private static final PlanningLookupCache.Limits UNCACHED = new PlanningLookupCache.Limits(0, 0, 0, 0);
    private static final ResourceLocation TOOL = id("tool");
    private static final ResourceLocation POTION = new ResourceLocation("minecraft", "potion");
    private static final ResourceLocation EARTH_HEART =
            new ResourceLocation("enigmaticlegacy", "earth_heart");
    private static final String REQUIRED = "{Unbreakable:1}";
    private static final String STOCKED = "{Damage:0,Unbreakable:1b}";
    private static final String AWKWARD_POTION = "{Potion:\"minecraft:awkward\"}";

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
    void repeatedIngredientMatchReusesTheCompleteResult() {
        MaterialRef actual = new MaterialRef(TOOL, STOCKED);
        IngredientRef ingredient = new IngredientRef(
                List.of(new MaterialRef(TOOL, REQUIRED)), 1, NbtMatchMode.PARTIAL);

        PlanningLookupCache.run(() -> {
            for (int repetition = 0; repetition < 100; repetition++) {
                assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, ingredient));
            }
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            assertEquals(2, stats.nbtParses());
            assertEquals(0, stats.nbtCacheHits());
            return null;
        });
    }

    @Test
    void identicalPotionExactMatchUsesStringFastPathBeforePurityFallback() {
        MaterialRef actual = new MaterialRef(POTION, AWKWARD_POTION);
        IngredientRef ingredient = new IngredientRef(
                List.of(new MaterialRef(POTION, AWKWARD_POTION)), 1, NbtMatchMode.EXACT);

        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, ingredient));
            assertEquals(0, PlanningLookupCache.currentStats().nbtParses());
            return null;
        });
    }

    @Test
    void potionPurityFallbackUsesPlanningNbtCacheWithoutChangingExactSemantics() {
        MaterialRef plain = new MaterialRef(POTION, AWKWARD_POTION);
        MaterialRef tagged = new MaterialRef(POTION,
                "{Potion:\"minecraft:awkward\",Purity:2}");
        IngredientRef exactPlain = new IngredientRef(List.of(plain), 1, NbtMatchMode.EXACT);

        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(tagged, exactPlain));
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            assertEquals(2, stats.nbtParses());
            assertTrue(stats.nbtCacheHits() > 0);
            return null;
        });

        MaterialRef healing = new MaterialRef(POTION, "{Potion:\"minecraft:healing\"}");
        IngredientRef exactHealing = new IngredientRef(List.of(healing), 1, NbtMatchMode.EXACT);
        PlanningLookupCache.run(() -> {
            assertFalse(ImmutableRecipeGraphProjector.matchesIngredient(tagged, exactHealing));
            return null;
        });
    }

    @Test
    void identicalEarthHeartExactMatchUsesStringFastPathBeforeTaintedFallback() {
        MaterialRef actual = new MaterialRef(EARTH_HEART, "{isTainted:1b}");
        IngredientRef ingredient = new IngredientRef(
                List.of(new MaterialRef(EARTH_HEART, "{isTainted:1b}")), 1, NbtMatchMode.EXACT);

        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, ingredient));
            assertEquals(0, PlanningLookupCache.currentStats().nbtParses());
            return null;
        });
    }

    @Test
    void taintedEarthHeartFallbackUsesPlanningNbtCacheAcrossNumericTagTypes() {
        MaterialRef expected = new MaterialRef(EARTH_HEART, "{isTainted:1}");
        MaterialRef actual = new MaterialRef(EARTH_HEART, "{isTainted:1b}");
        IngredientRef exact = new IngredientRef(List.of(expected), 1, NbtMatchMode.EXACT);

        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, exact));
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            assertEquals(2, stats.nbtParses());
            assertTrue(stats.nbtCacheHits() > 0);
            return null;
        });
    }

    @Test
    void ingredientMatchCacheIsIdentityScopedAndPreservesNbtModes() {
        MaterialRef actual = new MaterialRef(TOOL, STOCKED);
        MaterialRef expected = new MaterialRef(TOOL, REQUIRED);
        IngredientRef exact = new IngredientRef(List.of(expected), 1, NbtMatchMode.EXACT);
        IngredientRef partial = new IngredientRef(List.of(expected), 1, NbtMatchMode.PARTIAL);

        PlanningLookupCache.run(() -> {
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, exact));
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(actual, partial));
            assertEquals(2, PlanningLookupCache.currentStats().nbtCacheHits());
            return null;
        });

        MaterialRef ordinaryActual = new MaterialRef(TOOL, "{quality:1,owner:7}");
        MaterialRef ordinaryExpected = new MaterialRef(TOOL, "{quality:1}");
        IngredientRef ordinaryExact = new IngredientRef(
                List.of(ordinaryExpected), 1, NbtMatchMode.EXACT);
        IngredientRef ordinaryPartial = new IngredientRef(
                List.of(ordinaryExpected), 1, NbtMatchMode.PARTIAL);
        PlanningLookupCache.run(() -> {
            assertFalse(ImmutableRecipeGraphProjector.matchesIngredient(
                    ordinaryActual, ordinaryExact));
            assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(
                    ordinaryActual, ordinaryPartial));
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
    void generationCacheReusesParsedNbtAcrossRequestsAndClearsOnReload() {
        ImmutableRecipeGraph graph = lookupGraph(1);
        String inventoryNbt = "{owner:42,quality:3}";
        PlanningLookupCache.replaceSharedNbtCache(graph);
        try {
            PlanningLookupCache.Stats first = PlanningLookupCache.run(() -> {
                assertTrue(PlanningLookupCache.matchesNbt(inventoryNbt, inventoryNbt, true));
                return PlanningLookupCache.currentStats();
            });
            PlanningLookupCache.Stats second = PlanningLookupCache.run(() -> {
                assertTrue(PlanningLookupCache.matchesNbt(inventoryNbt, inventoryNbt, true));
                return PlanningLookupCache.currentStats();
            });
            assertEquals(1, first.nbtParses());
            assertEquals(0, second.nbtParses());
            assertTrue(second.nbtCacheHits() > 0);

            PlanningLookupCache.clearSharedNbtCache();
            PlanningLookupCache.Stats afterReload = PlanningLookupCache.run(() -> {
                assertTrue(PlanningLookupCache.matchesNbt(inventoryNbt, inventoryNbt, true));
                return PlanningLookupCache.currentStats();
            });
            assertEquals(1, afterReload.nbtParses());
        } finally {
            PlanningLookupCache.clearSharedNbtCache();
        }
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
    void defaultRequestCacheRetainsNbtBeyondTheOldEntryLimit() {
        PlanningLookupCache.Stats stats = PlanningLookupCache.run(() -> {
            for (int value = 0; value < 700; value++) {
                String tag = "{variant:" + value + "}";
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(tag, tag));
            }
            String hotTag = "{variant:699}";
            for (int repetition = 0; repetition < 1_000; repetition++) {
                assertTrue(ImmutableRecipeGraphProjector.partialNbtMatches(hotTag, hotTag));
            }
            return PlanningLookupCache.currentStats();
        });

        assertEquals(700, stats.nbtParses());
        assertEquals(700, stats.cachedTags());
        assertTrue(stats.nbtCacheHits() >= 2_700);
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
    void oversizedGraphUsesOneBoundedCompactIndexAndKeepsCandidateOrder() {
        ImmutableRecipeGraph graph = lookupGraph(100_000);
        MaterialRef wanted = new MaterialRef(TOOL, REQUIRED);
        List<RecipeNode> expected = referenceCandidates(graph, wanted, NbtMatchMode.PARTIAL);
        PlanningLookupCache.run(() -> {
            for (int repetition = 0; repetition < 100; repetition++) {
                assertEquals(expected, ImmutableRecipeGraphProjector.candidates(
                        graph, wanted, NbtMatchMode.PARTIAL));
                assertEquals(1, PlanningLookupCache.outputVariants(
                        graph, id("unrelated_" + repetition)).size());
            }
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            assertEquals(1, stats.outputIndexBuilds());
            assertEquals(graph.recipesByOutput().size(), stats.outputScans());
            assertEquals(1, stats.indexedGraphs());
            assertTrue(stats.compactIndexBytes() > 0L);
            assertTrue(stats.compactIndexBytes() <= PlanningLookupCache.DEFAULT_LIMITS.maxCompactIndexBytes());
            return null;
        });
    }

    @Test
    void compactIndexBudgetIsSharedAcrossGraphsWithoutDroppingFallbackCandidates() {
        ImmutableRecipeGraph first = lookupGraph(20);
        ImmutableRecipeGraph second = lookupGraph(21);
        PlanningLookupCache.Limits limits = new PlanningLookupCache.Limits(0, 0, 2, 16);
        PlanningLookupCache.run(limits, () -> {
            for (ImmutableRecipeGraph graph : List.of(first, second, first, second)) {
                MaterialRef wanted = new MaterialRef(TOOL, "");
                assertEquals(referenceCandidates(graph, wanted, NbtMatchMode.ANY),
                        ImmutableRecipeGraphProjector.candidates(graph, wanted, NbtMatchMode.ANY));
            }
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            assertEquals(1, stats.outputIndexBuilds());
            assertTrue(stats.compactIndexBytes() <= limits.maxCompactIndexBytes());
            assertEquals(first.recipesByOutput().size() + 2L * second.recipesByOutput().size(),
                    stats.outputScans());
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
    void reachabilityIndexesOnlyRelevantSeedsAndAllocatesReachedRecipeState() {
        MaterialRef target = new MaterialRef(id("reachable_target"), "");
        MaterialRef seed = new MaterialRef(id("reachable_seed"), "");
        List<RecipeNode> producers = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            producers.add(new RecipeNode(
                    id("reachable_recipe_" + index), target, 1,
                    List.of(new IngredientRef(List.of(seed), 1, NbtMatchMode.EXACT))));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(target, producers));
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(seed, 1);
        for (int index = 0; index < 100; index++) {
            stock.put(new MaterialRef(id("unrelated_stock_" + index), ""), 1);
        }

        PlanningLookupCache.Stats stats = PlanningLookupCache.run(() -> {
            PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                    graph, stock,
                    // 此测试专门检查通用搜索的可达性索引，避免等价路线被聚合直接完成。
                    List.of(new IngredientRef(List.of(target), 1, NbtMatchMode.PARTIAL)), 64);
            assertTrue(result.feasible());
            return PlanningLookupCache.currentStats();
        });

        assertEquals(1, stats.reachabilityBuilds());
        assertEquals(101, stats.reachabilitySeedCandidates());
        assertEquals(101, stats.reachabilitySeeds());
        assertEquals(5, stats.reachabilityIndexedEdges());
        assertTrue(stats.localProbeWork() <= 30);
        assertEquals(0, stats.localProbeExhaustions());
        assertEquals(0, stats.reachabilityStateAllocations());
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
        // 此参考图只有普通工具 NBT，不包含 bladeState，不能满足拔刀剑条件。
        if (mode == NbtMatchMode.SLASHBLADE) return List.of();
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
