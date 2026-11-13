package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** 仅沿服务端确认的当前材料缺口追踪；缺失的节点明细不当作已完成或故障。 */
public final class MonitorDependencyDetails {
    public record Trace(List<NodeProgress> causes, boolean incomplete) {}
    public record Row(Component title, Component detail, ItemStack icon, int targetNodeId) {}
    private final Map<Integer, NodeProgress> nodes = new HashMap<>();

    public MonitorDependencyDetails(CraftProgressSnapshot snapshot) {
        for (var node : snapshot.nodes()) nodes.put(node.nodeId(), node);
    }

    public NodeProgress node(int id) { return nodes.get(id); }

    public static Component title(NodeProgress node) {
        var output = node.displayOutput();
        return output.isEmpty() ? Component.literal(node.recipeId()) : output.getHoverName();
    }

    public Trace trace(int nodeId) {
        var queue = new ArrayDeque<Integer>();
        var visited = new HashSet<Integer>();
        var causes = new LinkedHashMap<Integer, NodeProgress>();
        boolean incomplete = false;
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            int currentId = queue.removeFirst();
            if (!visited.add(currentId)) continue;
            var current = nodes.get(currentId);
            if (current == null) { incomplete = true; continue; }
            incomplete |= current.waitingInputsTruncated();
            if (current.draining() || current.state() == NodeState.SUCCEEDED || current.state() == NodeState.CANCELLED) continue;
            boolean external = current.waitingInputs().stream().anyMatch(input -> input.producerNodeId() < 0);
            if (currentId != nodeId && (current.state() == NodeState.FAILED || external
                    || current.reason() != Reason.NONE && current.reason() != Reason.WAITING_MATERIALS)) {
                causes.put(currentId, current);
            }
            if (current.state() == NodeState.BLOCKED && current.waitingInputs().isEmpty()
                    && (current.reason() == Reason.NONE || current.reason() == Reason.WAITING_MATERIALS)) incomplete = true;
            for (var input : current.waitingInputs()) {
                if (input.producerNodeId() >= 0) queue.add(input.producerNodeId());
            }
        }
        return new Trace(List.copyOf(causes.values()), incomplete);
    }

    public List<Row> rows(int nodeId) {
        var node = nodes.get(nodeId);
        if (node == null) return List.of(new Row(Component.translatable("rsi.monitor.wait.unsynced"),
                Component.translatable("rsi.monitor.wait.unsynced_hint"), ItemStack.EMPTY, -1));
        var rows = new ArrayList<Row>();
        rows.add(new Row(title(node), state(node), node.displayOutput(), -1));
        rows.add(new Row(Component.translatable("rsi.monitor.step_counts", node.completedOperations(),
                node.totalOperations(), node.runningOperations()),
                Component.translatable("rsi.monitor.pending_operations",
                        Math.max(0L, (long) node.totalOperations() - node.completedOperations() - node.runningOperations())), ItemStack.EMPTY, -1));
        var trace = trace(nodeId);
        // 先列需要处理的上游，再列直接来源；多条分支各自保留，不捏造唯一阻塞点。
        for (var cause : trace.causes()) {
            rows.add(new Row(Component.translatable("rsi.monitor.wait.affected", title(cause)),
                    state(cause), cause.displayOutput(), cause.nodeId()));
        }
        for (var input : node.waitingInputs()) {
            var producer = nodes.get(input.producerNodeId());
            Component source = input.producerNodeId() < 0 ? Component.translatable("rsi.monitor.wait.external")
                    : producer == null ? Component.translatable("rsi.monitor.wait.source_unsynced", input.producerNodeId())
                    : Component.translatable("rsi.monitor.wait.source", title(producer), state(producer));
            rows.add(new Row(waitingLabel(input), source, input.material(), producer == null ? -1 : producer.nodeId()));
        }
        rows.add(new Row(Component.translatable("rsi.monitor.wait.machine"),
                node.machineLabel().isBlank() ? Component.translatable("rsi.monitor.wait.no_machine")
                        : Component.literal(node.machineLabel()), ItemStack.EMPTY, -1));
        var cycle = node.machineProgress();
        if (cycle.known()) rows.add(new Row(Component.translatable("rsi.monitor.cycle.progress", cycle.percent()),
                Component.translatable("rsi.monitor.cycle.coverage", cycle.reportingMachines(), cycle.activeMachines()), ItemStack.EMPTY, -1));
        else if (node.runningOperations() > 0) rows.add(new Row(Component.translatable("rsi.monitor.cycle.unknown"),
                Component.translatable("rsi.monitor.cycle.unknown_hint"), ItemStack.EMPTY, -1));
        if (trace.incomplete()) rows.add(new Row(Component.translatable("rsi.monitor.wait.partial"),
                Component.translatable("rsi.monitor.wait.unsynced_hint"), ItemStack.EMPTY, -1));
        if (!node.technicalDetail().isBlank()) rows.add(new Row(Component.translatable("rsi.monitor.wait.technical"),
                Component.literal(node.technicalDetail()), ItemStack.EMPTY, -1));
        return List.copyOf(rows);
    }

    public static Component waitingLabel(CraftProgressSnapshot.WaitingInput input) {
        var material = input.material();
        return Component.translatable("rsi.monitor.wait.material", material.getHoverName(),
                waitingAmount(InkFluidSupport.isToken(material), input.quantity()));
    }

    static String waitingAmount(boolean fluid, long quantity) {
        String amount = CompactQuantityFormat.format(quantity);
        return fluid ? amount + " mB" : "×" + amount;
    }

    public static Component state(NodeProgress node) {
        if (!node.draining() && (node.reason() == Reason.NONE || node.reason() == Reason.WAITING_MATERIALS)
                && !node.waitingInputs().isEmpty()) return waitingLabel(node.waitingInputs().get(0));
        return Component.translatable(node.draining() ? "rsi.monitor.draining"
                : node.reason() != Reason.NONE ? node.reason().translationKey()
                : "rsi.progress.step.state." + node.state().name().toLowerCase(Locale.ROOT));
    }
}
