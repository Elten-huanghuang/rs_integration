package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.sun.management.ThreadMXBean;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;

/** 统一基准源集中的规划诊断探针：图构建和预热不计入测量，直接调用当前生产实现。 */
public final class PlanningAuditProbe extends BootstrapTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 4;
    private static final int SAMPLES = 7;
    private static final int LOOKUPS = 100;
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        initializeForgeCapabilities();
        THREADS.setThreadAllocatedMemoryEnabled(true);
        Path output = Path.of(args[0]);
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            csv.println("scenario,size,samples,median_ms,min_ms,max_ms,allocated_mib_per_op,gc_count,gc_time_ms,status,states,backtracks,memo_hits,output_scans,index_builds");
            // 使用多个等价目标，确保冷裁剪测量不会命中按目标缓存。
            for (int count : new int[] {1000, 50000, 100000}) {
                ImmutableRecipeGraph graph = catalog(count, 16, WARMUP + SAMPLES);
                ImmutableRecipeGraphProjector.publishCompiled(null, count, graph, 0L);
                measure(csv, "cold_dependency_projection", count, index -> {
                    ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.restrictToDependencies(
                            graph, id("target_" + index));
                    if (scoped.recipesById().size() != 17) throw new AssertionError("unexpected closure");
                    sink = scoped;
                    return new Observation("OK", 0, 0, 0, 0, 0);
                });
                measure(csv, "cached_dependency_projection", count, index -> {
                    sink = ImmutableRecipeGraphProjector.restrictToDependencies(graph, id("target_0"));
                    return new Observation("OK", 0, 0, 0, 0, 0);
                });
            }
            // 固定只有 64 条链式配方，改变不相关库存种类，隔离失败缓存键复制成本。
            ImmutableRecipeGraph chain = catalog(64, 64, 0);
            List<IngredientRef> roots = List.of(input(material("chain_64")));
            for (int count : new int[] {100, 1000, 10000}) {
                Map<MaterialRef, Long> stock = stock(count);
                measure(csv, "chain64_full_stock", count, index -> solve(chain, stock, roots, 8192));
                if (count == 10000) {
                    measure(csv, "chain64_memo_disabled", count, index -> solve(chain, stock, roots, 0));
                    // 仅用于隔离开销：这张人工图可以严格证明其他库存与需求无关。
                    measure(csv, "chain64_relevant_stock_only", count,
                            index -> solve(chain, Map.of(material("seed"), 1L), roots, 8192));
                }
            }
            // 固定库存及可行解，只改变生产者数量，观察排序及库存匹配分配。
            for (int count : new int[] {16, 128, 512}) {
                ProducerCase sample = producerCase(count);
                measure(csv, "wide_producer_sort", count, index -> {
                    PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                            sample.graph(), sample.stock(), sample.roots(),
                            256, 65536, 8192, Long.MAX_VALUE);
                    if (!result.feasible() || result.steps().size() != 1
                            || !result.steps().get(0).recipeId().equals(id("producer_0000"))) {
                        throw new AssertionError(result);
                    }
                    sink = result;
                    return new Observation(result.status().name(), result.expandedStates(),
                            result.backtracks(), result.memoHits(), 0, 0);
                });
            }
            // 根图已有代际索引。强制配方投影生成新图，验证其局部缓存的容量边界。
            for (int count : new int[] {50000, 65536, 65537, 100000}) {
                ImmutableRecipeGraph original = catalog(count, 1, 1);
                ImmutableRecipeGraphProjector.publishCompiled(null, count, original, 0L);
                ImmutableRecipeGraph derived = ForcedRecipeGraphProjector.apply(original,
                        Map.of(material("chain_1").itemId(), id("recipe_1")));
                if (derived == null || derived == original) throw new AssertionError("derived graph missing");
                measure(csv, "published_output_lookup100", count,
                        index -> lookup(original));
                measure(csv, "derived_output_lookup100", count,
                        index -> lookup(derived));
            }
        }
        System.out.println("AUDIT_COMPLETE " + output.toAbsolutePath());
    }

    private static Observation solve(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock,
                                     List<IngredientRef> roots, int memoLimit) {
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, stock, roots, 256, 65536, memoLimit, Long.MAX_VALUE);
        if (!result.feasible() || result.steps().size() != 64) throw new AssertionError(result);
        sink = result;
        return new Observation(result.status().name(), result.expandedStates(), result.backtracks(),
                result.memoHits(), 0, 0);
    }

    private static Observation lookup(ImmutableRecipeGraph graph) {
        return PlanningLookupCache.run(() -> {
            for (int i = 0; i < LOOKUPS; i++) {
                sink = PlanningLookupCache.outputVariants(graph, id("unrelated_" + i));
            }
            PlanningLookupCache.Stats stats = PlanningLookupCache.currentStats();
            return new Observation("OK", 0, 0, 0, stats.outputScans(), stats.outputIndexBuilds());
        });
    }

    private static void measure(PrintWriter csv, String name, int size,
                                IntFunction<Observation> operation) {
        for (int i = 0; i < WARMUP; i++) sink = operation.apply(i);
        double[] millis = new double[SAMPLES];
        long allocations = 0;
        long gcCount = gc(false);
        long gcMillis = gc(true);
        Observation observation = null;
        for (int i = 0; i < SAMPLES; i++) {
            long allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            observation = operation.apply(WARMUP + i);
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
            allocations += THREADS.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
        }
        Arrays.sort(millis);
        String row = String.format(Locale.ROOT,
                "%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%s,%d,%d,%d,%d,%d",
                name, size, SAMPLES, millis[SAMPLES / 2], millis[0], millis[SAMPLES - 1],
                allocations / (SAMPLES * 1048576.0), gc(false) - gcCount, gc(true) - gcMillis,
                observation.status(), observation.states(), observation.backtracks(),
                observation.memoHits(), observation.scans(), observation.indexBuilds());
        csv.println(row);
        csv.flush();
        System.out.println("AUDIT " + row);
    }

    private static long gc(boolean time) {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, time ? gc.getCollectionTime() : gc.getCollectionCount());
        }
        return total;
    }

    private static Map<MaterialRef, Long> stock(int count) {
        Map<MaterialRef, Long> stock = new HashMap<>();
        stock.put(material("seed"), 1L);
        for (int i = 1; i < count; i++) stock.put(material("stock_" + i), 1L);
        return stock;
    }

    private static ImmutableRecipeGraph catalog(int total, int chainDepth, int targetCount) {
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        MaterialRef previous = material("seed");
        for (int i = 1; i <= chainDepth; i++) {
            MaterialRef output = material("chain_" + i);
            outputs.put(output, List.of(new RecipeNode(id("recipe_" + i), output, 1,
                    List.of(input(previous)))));
            previous = output;
        }
        for (int i = 0; i < targetCount; i++) {
            MaterialRef output = material("target_output_" + i);
            outputs.put(output, List.of(new RecipeNode(id("target_" + i), output, 1,
                    List.of(input(previous)))));
        }
        for (int i = 0; outputs.size() < total; i++) {
            MaterialRef output = material("unrelated_" + i);
            outputs.put(output, List.of(new RecipeNode(id("unrelated_recipe_" + i), output, 1,
                    List.of(input(material("unrelated_seed"))))));
        }
        return new ImmutableRecipeGraph(outputs);
    }

    private static IngredientRef input(MaterialRef material) {
        return new IngredientRef(List.of(material), 1L);
    }

    private static ProducerCase producerCase(int count) {
        MaterialRef output = material("wide_output");
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        List<IngredientRef> inputs = new ArrayList<>();
        for (int group = 0; group < 4; group++) {
            List<MaterialRef> alternatives = new ArrayList<>();
            for (int variant = 0; variant < 32; variant++) {
                MaterialRef material = new MaterialRef(id("wide_input_" + group),
                        "{variant:" + variant + "}");
                alternatives.add(material);
                stock.put(material, 1L);
            }
            inputs.add(new IngredientRef(alternatives, 1, NbtMatchMode.ANY));
        }
        List<RecipeNode> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            candidates.add(new RecipeNode(id(String.format(Locale.ROOT, "producer_%04d", index)),
                    output, 1, inputs));
        }
        Collections.shuffle(candidates, new Random(42));
        return new ProducerCase(new ImmutableRecipeGraph(Map.of(output, candidates)),
                Map.copyOf(stock), List.of(input(output)));
    }

    private record ProducerCase(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock,
                                List<IngredientRef> roots) {}

    private static ResourceLocation id(String path) {
        return new ResourceLocation("audit", path);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private record Observation(String status, int states, int backtracks, int memoHits,
                               long scans, long indexBuilds) {}
}
