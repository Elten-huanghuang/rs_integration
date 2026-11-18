package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留本节点获配的原始份额；每次只交给机器一轮，其余份额不能冒充重试的新库存。 */
final class TargetNodeProduction {
    record Round(List<MaterialBroker.Request> assigned, List<IngredientSpec> missing) {}
    private final List<Slot> slots = new ArrayList<>();
    private final NodeOutputAccumulator outputs;
    private final List<OutputDeclaration> guarantees;
    private final int executions;
    final OutputPortId targetPort;
    final ProbabilisticProduction progress;
    private Round pending;

    TargetNodeProduction(CraftNode node, OutputDeclaration target, int limit, List<MaterialAllocation> allocations) {
        outputs = new NodeOutputAccumulator(node.outputs());
        guarantees = node.outputs().stream().filter(o -> o.guaranteedMinimum() > 0
                && o.kind() != OutputKind.DYNAMIC).toList();
        executions = node.executions();
        targetPort = target.id();
        progress = new ProbabilisticProduction(ProductionTarget.of(target.resource(), target.quantity()), limit);
        for (InputDemand input : node.inputs()) {
            var lots = allocations.stream().filter(a -> a.consumer().equals(input.id())).toList();
            slots.add(new Slot(input, lots, node.executions()));
        }
    }

    void attach(CraftNodeRuntime runtime) {
        runtime.attachOutputs(outputs);
        Map<OutputPortId, Long> required = new LinkedHashMap<>();
        for (OutputDeclaration output : guarantees) {
            long total = output.guaranteedMinimum();
            long published = outputs.publishedQuantity(output.id());
            // 每轮只验收到期的必出份额，整批义务已满足的端口无需再次发布。
            long increment = Math.min(Math.max(0L, total - published), 1L + (total - 1L) / executions);
            required.put(output.id(), published + increment);
        }
        runtime.targetCompletion(targetPort, progress, required);
    }

    Round pending() { return pending; }
    void admittedRound() { pending = null; }

    Round next() {
        List<MaterialBroker.Request> assigned = new ArrayList<>();
        List<IngredientSpec> missing = new ArrayList<>();
        for (Slot slot : slots) slot.take(assigned, missing);
        return pending = new Round(List.copyOf(assigned), List.copyOf(missing));
    }

    private static final class Slot {
        final InputDemand input;
        final List<MaterialAllocation> lots;
        final long perAttempt;
        int index;
        long used;

        Slot(InputDemand input, List<MaterialAllocation> lots, int executions) {
            this.input = input;
            this.lots = lots;
            this.perAttempt = input.role() == DemandRole.CATALYST ? input.quantity()
                    : 1L + (input.quantity() - 1L) / executions;
        }

        void take(List<MaterialBroker.Request> assigned, List<IngredientSpec> missing) {
            long needed = perAttempt;
            while (needed > 0 && index < lots.size()) {
                MaterialAllocation lot = lots.get(index);
                long take = Math.min(needed, lot.quantity() - used);
                assigned.add(new MaterialBroker.Request(lot.source(), lot.material(), take));
                needed -= take;
                used += take;
                if (used == lot.quantity()) { index++; used = 0; }
            }
            if (needed > 0) missing.add(new IngredientSpec(input.ingredient(), Math.toIntExact(needed),
                    input.consumption()));
        }
    }
}
