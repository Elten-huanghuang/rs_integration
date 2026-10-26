package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;
import java.util.Locale;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Bounded telemetry for completed recursive graph execution plans. */
public final class ExecutionMetrics {
    public enum Reason {
        GRAPH_PLAN
    }

    private static final Map<Reason, AtomicLong> BY_REASON = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> BY_MOD_TYPE = new ConcurrentHashMap<>();

    private ExecutionMetrics() {}

    public static void record(Reason reason, ResourceLocation recipeId,
                              @Nullable ModType modType) {
        BY_REASON.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
        String typeId = modType == null ? "unknown" : modType.id();
        BY_MOD_TYPE.computeIfAbsent(typeId, ignored -> new AtomicLong()).incrementAndGet();
        Diagnostics.record(Diagnostics.Category.GRAPH_EXECUTION,
                "reason=" + reason + " modType=" + typeId, recipeId, modType);
    }

    public static long count(Reason reason) {
        AtomicLong count = BY_REASON.get(reason);
        return count == null ? 0L : count.get();
    }

    public static String summary() {
        String reasons = Arrays.stream(Reason.values())
                .filter(reason -> count(reason) > 0)
                .map(reason -> reason.name().toLowerCase(Locale.ROOT)
                        + ":" + count(reason))
                .collect(Collectors.joining(","));
        String types = BY_MOD_TYPE.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().get())
                .collect(Collectors.joining(","));
        return "reasons=[" + reasons + "] types=[" + types + "]";
    }
}
