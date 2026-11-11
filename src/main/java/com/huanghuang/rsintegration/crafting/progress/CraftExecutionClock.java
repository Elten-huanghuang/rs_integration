package com.huanghuang.rsintegration.crafting.progress;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 只读诊断计时，与执行器的超时/租约续期规则独立。 */
public final class CraftExecutionClock {
    private final LongSupplier millis;
    private final long startedAt;
    private long lastProgressAt;
    private long produced;
    private final Map<Integer, Integer> completed = new HashMap<>();
    private final Map<Integer, Integer> dispatched = new HashMap<>();
    private CraftProgressTiming terminalTiming;

    public CraftExecutionClock() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    CraftExecutionClock(LongSupplier millis) {
        this.millis = millis;
        startedAt = lastProgressAt = millis.getAsLong();
    }

    /** 适配器确认部分产出或真实工作量变化时调用；等待和状态刷新不调用。 */
    public void markProgress() {
        if (terminalTiming == null) lastProgressAt = millis.getAsLong();
    }

    public CraftProgressTiming sample(List<CraftProgressSnapshot.NodeProgress> nodes,
                                      CraftOutputProgress output, boolean terminal) {
        if (terminalTiming != null) return terminalTiming;
        boolean advanced = false;
        for (var node : nodes) {
            int finished = node.completedOperations();
            int launched = finished + node.runningOperations();
            advanced |= finished > completed.getOrDefault(node.nodeId(), 0)
                    || launched > dispatched.getOrDefault(node.nodeId(), 0);
            completed.merge(node.nodeId(), finished, Math::max);
            dispatched.merge(node.nodeId(), launched, Math::max);
        }
        if (output.known() && output.completed() > produced) {
            produced = output.completed();
            advanced = true;
        }
        long now = millis.getAsLong();
        if (advanced) lastProgressAt = now;
        long elapsed = Math.max(0L, now - startedAt);
        var timing = new CraftProgressTiming(elapsed, Math.min(elapsed, Math.max(0L, now - lastProgressAt)));
        if (terminal) terminalTiming = timing;
        return timing;
    }
}
