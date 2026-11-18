package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.Supplier;

/** 测量生产规划入口，不计入配方目录、世界替身和库存夹具初始化。 */
public final class DeepPlanningBenchmark extends BootstrapTest {
    private static final int WARMUP = 12;
    private static final int SAMPLES = 21;
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        bootstrapMinecraft();
        DeepPlanningFixture.initializeMod();
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        if (args[0].equals("legacy")) RSIntegrationConfig.CRAFTING_MAX_DEPTH.set(16);
        else if (!args[0].equals("default")) RSIntegrationConfig.CRAFTING_MAX_DEPTH.set(Integer.parseInt(args[0]));
        boolean legacy = args[0].equals("legacy");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        THREADS.setThreadCpuTimeEnabled(true);
        System.out.println("case,planner,depth,branches,typed_depth_limit,status,nodes,missing,median_ms,p95_ms,median_kib,p95_max_slice_ms,p95_max_slice_cpu_ms,p95_finalize_ms,native_routes,peak_callbacks_per_slice,p99_max_slice_ms");
        for (int depth : new int[]{16, 64, 128, 256, 512, 1024}) run("chain", depth, 1, false, false, legacy);
        run("wide", 8, 16, false, false, legacy);
        run("shared", 48, 16, true, false, legacy);
        run("shared_deep", 256, 4, true, false, legacy);
        run("mixed", 512, 1, false, true, legacy);
        if (!legacy) {
            runFullTree(8, 0, 32, 64);
            runFullTree(9, 0, 32, 64);
            runFullTree(10, 0, 48, 96);
            runFullTree(9, 320, 32, 64);
        }
    }

    private static void runFullTree(int levels, int trunk, int routes, int alternatives) {
        var seen = new HashSet<String>();
        int[] callbacks = {0};
        try (var fixture = DeepPlanningFixture.fullTree(levels, trunk, routes, alternatives, type -> {
            seen.add(type.id());
            callbacks[0]++;
        })) {
            measure(trunk == 0 ? "full_tree" : "full_tree_with_trunk", "typed", levels + trunk, 1 << (levels - 1), () -> {
                seen.clear();
                callbacks[0] = 0;
                var missing = new ArrayList<String>();
                var job = fixture.sliced(true, 500, missing);
                long maxSlice = 0L;
                long maxSliceCpu = 0L;
                int maxCallbacks = 0;
                boolean done;
                do {
                    int before = callbacks[0];
                    long cpuStarted = THREADS.getCurrentThreadCpuTime();
                    long started = System.nanoTime();
                    done = job.advance(5_000_000L);
                    maxSlice = Math.max(maxSlice, System.nanoTime() - started);
                    maxSliceCpu = Math.max(maxSliceCpu, THREADS.getCurrentThreadCpuTime() - cpuStarted);
                    maxCallbacks = Math.max(maxCallbacks, callbacks[0] - before);
                } while (!done);
                long started = System.nanoTime();
                var result = job.graph();
                long finalizeNanos = System.nanoTime() - started;
                int expected = (1 << levels) - 1 + trunk;
                if (!missing.isEmpty() || !result.unresolvedDemands().isEmpty()
                        || result.nodes().size() != expected || seen.size() != routes) {
                    throw new IllegalStateException("满树基准未完整求解，节点=" + result.nodes().size()
                            + "/" + expected + " 路由=" + seen.size() + "/" + routes + " 缺料=" + missing);
                }
                return new Run("SUCCESS", result.nodes().size(), 0, maxSlice, maxSliceCpu, finalizeNanos,
                        seen.size(), maxCallbacks);
            });
            var graphJob = fixture.sliced(true, 500, new ArrayList<>());
            graphJob.advance(Long.MAX_VALUE);
            var graph = graphJob.graph();
            measure(trunk == 0 ? "full_tree" : "full_tree_with_trunk", "estimate", levels + trunk,
                    1 << (levels - 1), () -> {
                        var work = fixture.parallelEstimator().start(graph);
                        long maxSlice = 0L;
                        int maxProbes = 0;
                        boolean done;
                        do {
                            int before = fixture.probeCalls();
                            long start = System.nanoTime();
                            done = work.advance(5_000_000L);
                            maxSlice = Math.max(maxSlice, System.nanoTime() - start);
                            maxProbes = Math.max(maxProbes, fixture.probeCalls() - before);
                        } while (!done);
                        int estimates = work.result().size();
                        if (estimates != graph.nodes().size() || fixture.probeCalls() != routes) {
                            throw new IllegalStateException("满树并行估算缺失或重复建立探针");
                        }
                        return new Run("SUCCESS", estimates, 0, maxSlice, 0L, 0L, routes, maxProbes);
                    });
        }
    }

    private static void run(String name, int depth, int branches, boolean shared, boolean mixed, boolean legacy) {
        try (var fixture = new DeepPlanningFixture(depth, branches, shared, mixed)) {
            measure(name, "pure", depth, branches, () -> {
                var result = fixture.pure(true);
                return new Run(result.status().name(), result.steps().size(), result.missing().size());
            });
            measure(name, legacy ? "typed_legacy" : "typed", depth, branches, () -> {
                var missing = new ArrayList<String>();
                try {
                    long maxSlice = 0L;
                    long maxSliceCpu = 0L;
                    long finalizeNanos = 0L;
                    CraftPlanGraph result;
                    if (legacy) {
                        result = fixture.legacyTyped(500, missing);
                    } else {
                        var job = fixture.sliced(true, 500, missing);
                        boolean done;
                        do {
                            long cpuStarted = THREADS.getCurrentThreadCpuTime();
                            long started = System.nanoTime();
                            done = job.advance(5_000_000L);
                            maxSlice = Math.max(maxSlice, System.nanoTime() - started);
                            maxSliceCpu = Math.max(maxSliceCpu, THREADS.getCurrentThreadCpuTime() - cpuStarted);
                        } while (!done);
                        long started = System.nanoTime();
                        result = job.graph();
                        finalizeNanos = System.nanoTime() - started;
                    }
                    int unresolved = result.unresolvedDemands().size()
                            + (int) result.rootDemands().stream().filter(root -> root.unresolvedQuantity() > 0).count();
                    return new Run(unresolved == 0 && missing.isEmpty() ? "SUCCESS" : "PARTIAL",
                            result.nodes().size(), Math.max(unresolved, missing.size()),
                            maxSlice, maxSliceCpu, finalizeNanos, 0, 0);
                } catch (CraftingPlanningLimitException limit) {
                    return new Run("LIMIT", 0, 0);
                } catch (CraftingPlanningTimeoutException timeout) {
                    return new Run("TIMEOUT", 0, 0);
                }
            });
        }
    }

    private static void measure(String name, String planner, int depth, int branches, Supplier<Run> action) {
        for (int i = 0; i < WARMUP; i++) action.get();
        long[] times = new long[SAMPLES];
        long[] allocations = new long[SAMPLES];
        long[] slices = new long[SAMPLES];
        long[] sliceCpu = new long[SAMPLES];
        long[] finalizes = new long[SAMPLES];
        Run run = null;
        int peakCallbacks = 0;
        long threadId = Thread.currentThread().getId();
        for (int i = 0; i < SAMPLES; i++) {
            long before = THREADS.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            Run next = action.get();
            times[i] = System.nanoTime() - start;
            allocations[i] = THREADS.getThreadAllocatedBytes(threadId) - before;
            slices[i] = next.maxSlice();
            sliceCpu[i] = next.maxSliceCpu();
            finalizes[i] = next.finalizeNanos();
            peakCallbacks = Math.max(peakCallbacks, next.peakCallbacks());
            if (run != null && (!run.status().equals(next.status()) || run.nodes() != next.nodes()
                    || run.missing() != next.missing())) throw new IllegalStateException("基准结果不一致：" + run + " / " + next);
            run = next;
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        Arrays.sort(slices);
        Arrays.sort(sliceCpu);
        Arrays.sort(finalizes);
        int p95 = (int) Math.ceil(SAMPLES * .95) - 1;
        int p99 = (int) Math.ceil(SAMPLES * .99) - 1;
        System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%s,%d,%d,%.3f,%.3f,%.1f,%.3f,%.3f,%.3f,%d,%d,%.3f%n", name, planner,
                depth, branches, RSIntegrationConfig.CRAFTING_MAX_DEPTH.get(), run.status(), run.nodes(),
                run.missing(), times[SAMPLES / 2] / 1e6, times[p95] / 1e6,
                allocations[SAMPLES / 2] / 1024.0, slices[p95] / 1e6,
                sliceCpu[p95] / 1e6, finalizes[p95] / 1e6, run.nativeRoutes(), peakCallbacks, slices[p99] / 1e6);
    }

    private record Run(String status, int nodes, int missing, long maxSlice, long maxSliceCpu, long finalizeNanos,
                       int nativeRoutes, int peakCallbacks) {
        Run(String status, int nodes, int missing) {
            this(status, nodes, missing, 0L, 0L, 0L, 0, 0);
        }
    }
}
