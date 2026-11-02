package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 测量真实机器准备入口；轻量委托不模拟模组实际配方解析和库存 I/O 耗时。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx1g"})
public class CraftingPreparationBenchmark {
    @State(Scope.Thread)
    public static class PreparationData {
        @Param({"1", "8", "32", "128"})
        public int machineCount;
        @Param({"first", "late", "reject", "multi"})
        public String scenario;
        GraphPreparationFixture fixture;

        @Setup(Level.Trial)
        public void create() throws Exception {
            fixture = createFixture(machineCount, scenario, false);
        }

        @TearDown(Level.Trial)
        public void close() {
            fixture.close();
        }
    }

    @Benchmark
    public void prepareNode(PreparationData data, Blackhole blackhole) throws Throwable {
        Object result = data.fixture.prepare();
        blackhole.consume(result);
        // 每轮释放保留的委托，避免跨轮携带准备资源；两版执行同样的清理。
        data.fixture.release(result);
    }

    private static GraphPreparationFixture createFixture(
            int count, String scenario, boolean tracking) throws Exception {
        GraphPreparationFixture fixture = new GraphPreparationFixture(
                count, scenario.equals("multi") ? 128 : 1, tracking);
        int readyFrom = switch (scenario) {
            case "first", "multi" -> 0;
            case "late" -> count * 3 / 4;
            case "reject" -> count;
            default -> throw new IllegalArgumentException(scenario);
        };
        IBatchDelegate.PreparationResult ready = IBatchDelegate.PreparationResult.ready();
        IBatchDelegate.PreparationResult retry =
                IBatchDelegate.PreparationResult.retry("machine needs energy");
        fixture.resultAt = index -> index >= readyFrom ? ready : retry;
        return fixture;
    }

    /** 非计时调用计数，跟踪委托与计时委托分开。 */
    public static void main(String[] args) throws Throwable {
        System.out.println("machines,scenario,prepares,created,releasedBeforeCleanup,releasedAfterCleanup,state,machinesKept,workers");
        for (int count : List.of(1, 8, 32, 128)) {
            for (String scenario : List.of("first", "late", "reject", "multi")) {
                try (GraphPreparationFixture fixture = createFixture(count, scenario, true)) {
                    Object result = fixture.prepare();
                    int released = fixture.delegates.stream().mapToInt(instance -> instance.releases).sum();
                    Object prepared = GraphPreparationFixture.component(result, "prepared");
                    int kept = prepared == null ? 0
                            : ((List<?>) GraphPreparationFixture.component(prepared, "machines")).size();
                    int workers = prepared == null ? 0
                            : (int) GraphPreparationFixture.component(prepared, "operationCost");
                    fixture.release(result);
                    int total = fixture.delegates.stream().mapToInt(instance -> instance.releases).sum();
                    if (total != fixture.delegates.size()
                            || fixture.delegates.stream().anyMatch(instance -> instance.releases != 1)) {
                        throw new AssertionError("准备资源未恰好释放一次");
                    }
                    System.out.printf("%d,%s,%d,%d,%d,%d,%s,%d,%d%n", count, scenario,
                            fixture.probes.size(), fixture.delegates.size(), released, total,
                            GraphPreparationFixture.component(result, "state"), kept, workers);
                }
            }
        }
    }
}
