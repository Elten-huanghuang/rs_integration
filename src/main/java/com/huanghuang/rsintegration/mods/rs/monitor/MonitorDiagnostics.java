package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 按证据汇总关注点；普通依赖等待和机器排队不作故障判断，也不推测关键路径。 */
public record MonitorDiagnostics(Kind kind, Reason reason, int count, Set<String> focusKeys,
                                 List<Group> groups, boolean partial) {
    public enum Kind { ATTENTION, QUEUED, DEPENDENCIES, RUNNING, PREPARING, STOPPING, SUCCEEDED, CANCELLED }
    public record Group(Kind kind, Reason reason, Set<String> keys) {
        public Component label() { return groupLabel(kind, reason, keys.size()); }
    }
    private record Cause(Kind kind, Reason reason) {}

    public static MonitorDiagnostics from(CraftProgressSnapshot snapshot) {
        boolean partial = snapshot.nodes().size() < snapshot.totalNodes();
        Kind terminal = switch (snapshot.result()) {
            case STOPPING -> Kind.STOPPING;
            case SUCCEEDED -> Kind.SUCCEEDED;
            case CANCELLED -> Kind.CANCELLED;
            default -> null;
        };
        if (terminal != null) return new MonitorDiagnostics(terminal, Reason.NONE, 0, Set.of(), List.of(), partial);

        Map<Cause, List<String>> grouped = new LinkedHashMap<>();
        for (var node : snapshot.nodes()) {
            Kind kind = classify(node);
            if (kind == Kind.RUNNING || kind == Kind.PREPARING || kind == null) continue;
            var cause = new Cause(kind, node.reason());
            grouped.computeIfAbsent(cause, ignored -> new ArrayList<>()).add("node:" + node.nodeId());
        }
        var groups = grouped.entrySet().stream()
                .map(entry -> new Group(entry.getKey().kind(), entry.getKey().reason(), Set.copyOf(entry.getValue())))
                .sorted(Comparator.comparing(Group::kind)
                        .thenComparing(Comparator.comparingInt((Group group) -> group.keys().size()).reversed())
                        .thenComparing(Group::reason)).toList();
        boolean processing = snapshot.runningNodes() > 0 || snapshot.nodes().stream().anyMatch(node ->
                node.state() == NodeState.RUNNING && !node.draining());
        Kind global = snapshot.result() == CraftProgressSnapshot.Result.FAILED ? Kind.ATTENTION
                : snapshot.reason() != Reason.NONE ? classifyReason(snapshot.reason()) : null;
        // 旧执行路径把正常的异步加工也记作全局 MACHINE_BUSY，不能据此误报为排队。
        if (processing && global == Kind.QUEUED) global = null;
        if (global != null && (groups.isEmpty() || global.compareTo(groups.get(0).kind()) < 0)) {
            return new MonitorDiagnostics(global, snapshot.reason(), 0, Set.of(), groups, partial);
        }
        if (!groups.isEmpty()) {
            var primary = groups.get(0);
            if (primary.kind() == Kind.DEPENDENCIES && processing) {
                return new MonitorDiagnostics(Kind.RUNNING, Reason.NONE, snapshot.runningNodes(), Set.of(), groups, partial);
            }
            return new MonitorDiagnostics(primary.kind(), primary.reason(), primary.keys().size(), primary.keys(), groups, partial);
        }
        Kind kind = processing ? Kind.RUNNING : Kind.PREPARING;
        return new MonitorDiagnostics(kind, processing ? Reason.NONE : snapshot.reason(), snapshot.runningNodes(), Set.of(), groups, partial);
    }

    private static Kind classify(NodeProgress node) {
        if (node.draining() || node.state() == NodeState.SUCCEEDED || node.state() == NodeState.CANCELLED) return null;
        if (node.state() == NodeState.FAILED) return Kind.ATTENTION;
        if (node.reason() != Reason.NONE) return classifyReason(node.reason());
        if (node.runningOperations() > 0 || node.state() == NodeState.RUNNING) return Kind.RUNNING;
        return node.state() == NodeState.BLOCKED ? Kind.DEPENDENCIES : Kind.PREPARING;
    }

    private static Kind classifyReason(Reason reason) {
        return switch (reason) {
            case MACHINE_BUSY, OPERATION_BUDGET, RESOURCE_CONFLICT -> Kind.QUEUED;
            case WAITING_MATERIALS -> Kind.DEPENDENCIES;
            default -> Kind.ATTENTION;
        };
    }

    static int priority(NodeProgress node) {
        Kind kind = classify(node);
        if (kind == null) return node.draining() ? 1 : 4;
        return switch (kind) {
            case ATTENTION -> 0;
            case RUNNING -> 1;
            case QUEUED -> 2;
            default -> 3;
        };
    }

    public Component label() { return groupLabel(kind, reason, count); }

    public Component category() {
        return Component.translatable("rsi.monitor.diagnosis." + kind.name().toLowerCase(Locale.ROOT));
    }

    private static Component groupLabel(Kind kind, Reason reason, int count) {
        Component text = reason != Reason.NONE ? Component.translatable(reason.translationKey())
                : Component.translatable("rsi.monitor.diagnosis." + kind.name().toLowerCase(Locale.ROOT));
        return count > 0 ? Component.translatable("rsi.monitor.diagnosis.group", text, count) : text;
    }

    public Component advice() {
        String advice = switch (reason) {
            case WAITING_MATERIALS -> "materials";
            case MACHINE_BUSY, OPERATION_BUDGET, RESOURCE_CONFLICT -> "queue";
            case CHUNK_UNLOADED -> "chunk";
            case NO_BOUND_MACHINE -> "binding";
            case NETWORK_UNAVAILABLE -> "network";
            case INPUT_CONFLICT -> "input";
            case SPECIAL_RESOURCE_INSUFFICIENT -> "resource";
            case MATERIAL_EXTRACTION_FAILED -> "extraction";
            case START_REJECTED -> "start";
            case OUTPUT_MISSING, PRODUCTION_TARGET_UNMET -> "output";
            case CONTRACT_INCOMPATIBLE -> "compatibility";
            case TIMEOUT -> "timeout";
            default -> switch (kind) {
                case DEPENDENCIES -> "materials";
                case RUNNING -> "running";
                case STOPPING -> "stopping";
                case SUCCEEDED, CANCELLED -> "finished";
                case PREPARING -> "preparing";
                default -> "unknown";
            };
        };
        return Component.translatable("rsi.monitor.advice." + advice);
    }

    public List<Component> tooltip(CraftProgressSnapshot snapshot) {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("rsi.monitor.diagnosis.title", category()));
        lines.add(label());
        if (snapshot.outputProgress().known()) lines.add(Component.translatable("rsi.monitor.produced",
                snapshot.outputProgress().completed(), snapshot.outputProgress().total()));
        lines.add(Component.translatable("rsi.monitor.diagnosis.totals", snapshot.completedNodes(),
                snapshot.totalNodes(), snapshot.runningNodes()));
        var secondary = groups.stream().filter(group -> group.kind() != kind || group.reason() != reason).toList();
        secondary.stream().limit(4).forEach(group -> lines.add(group.label()));
        if (secondary.size() > 4) lines.add(Component.translatable("rsi.monitor.diagnosis.more_groups", secondary.size() - 4));
        lines.add(advice());
        if (partial) lines.add(Component.translatable("rsi.monitor.diagnosis.partial", snapshot.nodes().size(), snapshot.totalNodes()));
        if (!focusKeys.isEmpty()) lines.add(Component.translatable("rsi.monitor.diagnosis.focus_hint"));
        return List.copyOf(lines);
    }

    public static Component timingLabel(CraftProgressSnapshot snapshot) {
        var timing = snapshot.timing();
        if (!timing.known()) return Component.translatable("rsi.monitor.timing_unknown");
        return snapshot.isTerminal() ? Component.translatable("rsi.monitor.timing_finished", duration(timing.elapsedMillis()))
                : Component.translatable("rsi.monitor.timing", duration(timing.elapsedMillis()), duration(timing.sinceProgressMillis()));
    }

    static String duration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return seconds < 3600L ? String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
                : String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L);
    }
}
