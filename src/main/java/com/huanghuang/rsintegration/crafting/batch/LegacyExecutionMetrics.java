package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;
import java.util.Locale;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Bounded telemetry for the remaining flat-execution adapters. */
public final class LegacyExecutionMetrics {
    public enum Reason {
        MOD_REQUIRES_FLAT_EXECUTION,
        INFER_MODE,
        RUNTIME_SELECTED_INPUTS,
        UNKNOWN_OUTPUT,
        NONDETERMINISTIC_OUTPUT,
        SELF_AMPLIFYING_TERMINAL,
        TAINT_SYNTHETIC_STEP,
        DIRECT_TERMINAL_RESERVATION,
        PURE_CHAIN_OPERATION_THRESHOLD,
        GRAPH_COMPOSITION_REJECTED
    }

    private static final Map<Reason, AtomicLong> BY_REASON = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> BY_MOD_TYPE = new ConcurrentHashMap<>();

    private LegacyExecutionMetrics() {}

    static Reason fromTerminalDecision(TerminalGraphExecutionPolicy.Reason reason) {
        return switch (reason) {
            case INFER_MODE -> Reason.INFER_MODE;
            case DYNAMIC_INPUTS -> Reason.RUNTIME_SELECTED_INPUTS;
            case UNKNOWN_OUTPUT -> Reason.UNKNOWN_OUTPUT;
            case NONDETERMINISTIC_OUTPUT -> Reason.NONDETERMINISTIC_OUTPUT;
            case SELF_AMPLIFYING_INPUT -> Reason.SELF_AMPLIFYING_TERMINAL;
            case COMPOSABLE -> Reason.GRAPH_COMPOSITION_REJECTED;
        };
    }

    static Reason rejectedGraphReason(List<CraftingResolver.ResolutionStep> steps) {
        return steps.stream().anyMatch(step -> step.recipeId().equals(
                CraftingResolver.TAINT_EARTH_HEART_STEP))
                ? Reason.TAINT_SYNTHETIC_STEP
                : Reason.GRAPH_COMPOSITION_REJECTED;
    }

    public static void record(Reason reason, ResourceLocation recipeId,
                              @Nullable ModType modType) {
        BY_REASON.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
        String typeId = modType == null ? "unknown" : modType.id();
        BY_MOD_TYPE.computeIfAbsent(typeId, ignored -> new AtomicLong()).incrementAndGet();
        Diagnostics.record(Diagnostics.Category.LEGACY_EXECUTION,
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
