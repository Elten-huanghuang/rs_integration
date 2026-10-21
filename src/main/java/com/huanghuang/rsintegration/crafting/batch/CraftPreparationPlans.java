package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 材料准备图的裁剪、可执行判断和摘要。 */
final class CraftPreparationPlans {
    private CraftPreparationPlans() {}

    static boolean isExecutable(CraftPlanGraph graph) {
        return graph != null && !graph.nodes().isEmpty()
                && graph.unresolvedDemands().isEmpty();
    }

    @Nullable
    static CraftPlanGraph pruneBlocked(CraftPlanGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) return null;
        if (graph.unresolvedDemands().isEmpty()) return graph;

        Set<NodeId> blocked = graph.unresolvedDemands().stream()
                .map(unresolved -> unresolved.consumer().nodeId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed;
        do {
            changed = false;
            for (var allocation : graph.allocations()) {
                if (!(allocation.source() instanceof MaterialSource.ProducerOutput producer)) {
                    continue;
                }
                if (blocked.contains(producer.outputPort().nodeId())
                        && blocked.add(allocation.consumer().nodeId())) {
                    changed = true;
                }
            }
        } while (changed);

        Set<NodeId> retained = graph.nodes().stream()
                .map(CraftNode::id)
                .filter(nodeId -> !blocked.contains(nodeId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (retained.isEmpty()) return null;

        List<CraftNode> nodes = graph.nodes().stream()
                .filter(node -> retained.contains(node.id()))
                .toList();
        List<MaterialAllocation> allocations = graph.allocations().stream()
                .filter(allocation -> retained.contains(allocation.consumer().nodeId()))
                .filter(allocation -> !(allocation.source()
                        instanceof MaterialSource.ProducerOutput producer)
                        || retained.contains(producer.outputPort().nodeId()))
                .toList();
        List<NodeId> topologicalOrder = graph.topologicalOrder().stream()
                .filter(retained::contains)
                .toList();

        Map<OutputPortId, Long> internallyConsumed = new HashMap<>();
        for (var allocation : allocations) {
            if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                internallyConsumed.merge(producer.outputPort(), allocation.quantity(),
                        SaturatingLongMath::add);
            }
        }
        List<RootDemand> roots = new ArrayList<>();
        for (CraftNode node : nodes) {
            for (var output : node.outputs()) {
                long deliverable = output.quantity()
                        - internallyConsumed.getOrDefault(output.id(), 0L);
                if (deliverable <= 0L) continue;
                ItemStack display = output.material().toStack(1);
                roots.add(new RootDemand(Ingredient.of(display), deliverable, 0, display,
                        List.of(new RootAllocation(
                                new MaterialSource.ProducerOutput(output.id()),
                                output.material(), deliverable))));
            }
        }
        if (roots.isEmpty()) return null;

        CraftPlanGraph pruned = new CraftPlanGraph(graph.version(), nodes, allocations,
                roots, List.of(), topologicalOrder, graph.planningRevision());
        CraftPlanValidator.validate(pruned);
        return pruned;
    }

    @Nullable
    static Component summarizePreparedOutputs(CraftPlanGraph graph) {
        Map<OutputPortId, Long> consumed = new HashMap<>();
        for (var allocation : graph.allocations()) {
            if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                consumed.merge(producer.outputPort(), allocation.quantity(),
                        SaturatingLongMath::add);
            }
        }
        Map<MaterialKey, Long> totals = new LinkedHashMap<>();
        for (CraftNode node : graph.nodes()) {
            for (var output : node.outputs()) {
                long returned = output.quantity() - consumed.getOrDefault(output.id(), 0L);
                if (returned > 0L) {
                    totals.merge(output.material(), returned, SaturatingLongMath::add);
                }
            }
        }
        return formatTotals(totals);
    }

    @Nullable
    static Component summarizePreparationMissing(CraftPlanGraph graph) {
        Map<MaterialKey, Long> totals = new LinkedHashMap<>();
        for (var unresolved : graph.unresolvedDemands()) {
            ItemStack display = unresolved.displayHint();
            if (!display.isEmpty()) {
                totals.merge(MaterialKey.of(display), (long) unresolved.quantity(),
                        SaturatingLongMath::add);
            }
        }
        for (var root : graph.rootDemands()) {
            if (root.unresolvedQuantity() <= 0) continue;
            ItemStack display = root.displayHint();
            if (!display.isEmpty()) {
                totals.merge(MaterialKey.of(display), root.unresolvedQuantity(),
                        SaturatingLongMath::add);
            }
        }
        return formatTotals(totals);
    }

    @Nullable
    static Component summarizeUnresolvedInputs(CraftPlanGraph graph) {
        Map<MaterialKey, Long> totals = new LinkedHashMap<>();
        for (var unresolved : graph.unresolvedDemands()) {
            ItemStack display = unresolved.displayHint();
            if (display.isEmpty()) continue;
            totals.merge(MaterialKey.of(display), (long) unresolved.quantity(),
                    SaturatingLongMath::add);
        }
        return formatTotals(totals);
    }

    @Nullable
    private static Component formatTotals(Map<MaterialKey, Long> totals) {
        if (totals.isEmpty()) return null;
        var summary = Component.empty();
        int index = 0;
        for (Map.Entry<MaterialKey, Long> entry : totals.entrySet()) {
            if (index++ > 0) summary.append(Component.literal("、"));
            summary.append(entry.getKey().toStack(1).getHoverName())
                    .append(Component.literal(" x"
                            + CompactQuantityFormat.format(entry.getValue())));
        }
        return summary;
    }
}
