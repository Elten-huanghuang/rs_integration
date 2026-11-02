package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 对比旧静态扇出与退订列表稳定后的完整候选处理开销。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx2g"})
public class MaterialWakeIndexBenchmark {
    private static final int CONSUMER_COUNT = 100_000;

    @State(Scope.Thread)
    public static class FanoutState {
        @Param({"0", "50", "95"})
        public int inactivePercent;

        final MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(0), 0));
        final GraphMaterialWakeIndex index = new GraphMaterialWakeIndex();
        final BitSet candidates = new BitSet(CONSUMER_COUNT);
        final int[] allPositions = new int[CONSUMER_COUNT];
        final boolean[] blockedPositions = new boolean[CONSUMER_COUNT];
        final Map<MaterialSource, int[]> staticConsumersBySource = new HashMap<>();

        @Setup(Level.Trial)
        public void subscribeConsumers() {
            for (int position = 0; position < CONSUMER_COUNT; position++) {
                NodeId nodeId = new NodeId(position);
                allPositions[position] = position;
                blockedPositions[position] = position % 100 >= inactivePercent;
                index.subscribe(source, nodeId, position);
                if (position % 100 < inactivePercent) index.unsubscribe(nodeId);
            }
            staticConsumersBySource.put(source, allPositions);
        }
    }

    @Benchmark
    public int staticFanout(FanoutState state) {
        int[] positions = state.staticConsumersBySource.get(state.source);
        for (int position : positions) state.candidates.set(position);
        int count = countBlockedCandidates(state);
        state.candidates.clear();
        return count;
    }

    @Benchmark
    public int activeFanout(FanoutState state) {
        state.index.include(state.source, state.candidates);
        int count = countBlockedCandidates(state);
        state.candidates.clear();
        return count;
    }

    private static int countBlockedCandidates(FanoutState state) {
        int count = 0;
        for (int position = state.candidates.nextSetBit(0); position >= 0;
             position = state.candidates.nextSetBit(position + 1)) {
            if (state.blockedPositions[position]) count++;
        }
        return count;
    }
}
