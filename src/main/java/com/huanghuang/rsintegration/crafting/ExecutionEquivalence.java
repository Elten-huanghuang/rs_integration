package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compares the observable operation sequence of DAG and legacy flat execution. */
public final class ExecutionEquivalence {
    private ExecutionEquivalence() {}

    /** Projects the authoritative graph order into the legacy executor shape. */
    public static List<CraftingResolver.ResolutionStep> projectFlatSteps(CraftPlanGraph graph) {
        Map<NodeId, CraftNode> nodes = graph.nodesById();
        List<CraftingResolver.ResolutionStep> projected = new ArrayList<>(
                graph.topologicalOrder().size());
        for (NodeId nodeId : graph.topologicalOrder()) {
            CraftNode node = nodes.get(nodeId);
            if (node == null) throw new IllegalArgumentException("missing graph node " + nodeId);
            projected.add(projectStep(node));
        }
        return List.copyOf(projected);
    }

    /** Projects one authoritative graph node without relying on list position or node id. */
    static CraftingResolver.ResolutionStep projectStep(CraftNode node) {
        ModType modType = ModType.findById(node.modTypeId());
        if (modType == null) {
            throw new IllegalArgumentException("unknown graph mod type " + node.modTypeId());
        }
        return new CraftingResolver.ResolutionStep(node.recipeId(), modType,
                node.recipeTypeId(), node.alternativeIds(), node.alternativeModTypeIds(),
                node.inferMode(), node.executions(), node.syntheticInput(), node.syntheticOutput(),
                node.outputs().stream().filter(output -> output.kind() == OutputKind.PRIMARY)
                        .findFirst().map(output -> new ProductionTarget(output.material(), output.quantity()))
                        .orElse(null));
    }

    public static Report compare(CraftPlanGraph graph,
                                  List<CraftingResolver.ResolutionStep> flatSteps) {
        Map<NodeId, CraftNode> nodes = graph.nodesById();
        List<Mismatch> mismatches = new ArrayList<>();
        List<NodeId> order = graph.topologicalOrder();
        if (order.size() != flatSteps.size()) {
            mismatches.add(new Mismatch(-1, "node count " + order.size()
                    + " != flat step count " + flatSteps.size()));
        }
        int count = Math.min(order.size(), flatSteps.size());
        for (int i = 0; i < count; i++) {
            CraftNode node = nodes.get(order.get(i));
            CraftingResolver.ResolutionStep step = flatSteps.get(i);
            if (!node.recipeId().equals(step.recipeId())) {
                mismatches.add(new Mismatch(i, "recipe " + node.recipeId() + " != " + step.recipeId()));
            }
            if (node.executions() != step.executions()) {
                mismatches.add(new Mismatch(i, "executions " + node.executions()
                        + " != " + step.executions()));
            }
            if (!node.modTypeId().equals(step.modType().id())) {
                mismatches.add(new Mismatch(i, "mod type " + node.modTypeId()
                        + " != " + step.modType().id()));
            }
            if (!node.recipeTypeId().equals(step.recipeTypeId())) {
                mismatches.add(new Mismatch(i, "recipe type " + node.recipeTypeId()
                        + " != " + step.recipeTypeId()));
            }
            if (!node.alternativeIds().equals(step.alternativeIds())) {
                mismatches.add(new Mismatch(i, "alternative recipes differ"));
            }
            if (!node.alternativeModTypeIds().equals(step.alternativeModTypes())) {
                mismatches.add(new Mismatch(i, "alternative mod types differ"));
            }
            if (node.inferMode() != step.inferMode()) {
                mismatches.add(new Mismatch(i, "infer mode differs"));
            }
            if (!sameStack(node.syntheticInput(), step.syntheticInput())) {
                mismatches.add(new Mismatch(i, "synthetic input differs"));
            }
            if (!sameStack(node.syntheticOutput(), step.syntheticOutput())) {
                mismatches.add(new Mismatch(i, "synthetic output differs"));
            }
        }
        return new Report(mismatches.isEmpty(), List.copyOf(mismatches));
    }

    public record Report(boolean equivalent, List<Mismatch> mismatches) {
        public Report { mismatches = List.copyOf(mismatches); }
    }

    public record Mismatch(int index, String detail) {}

    private static boolean sameStack(ItemStack left,
                                     ItemStack right) {
        if (left == null || right == null) return left == right;
        return left.getCount() == right.getCount()
                && MaterialMatcher.equivalentRuntimeFragment(left, right);
    }
}
