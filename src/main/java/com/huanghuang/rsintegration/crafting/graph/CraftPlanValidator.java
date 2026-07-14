package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CraftPlanValidator {

    private CraftPlanValidator() {}

    public static void validate(CraftPlanGraph graph) {
        List<String> errors = findErrors(graph);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid craft plan graph: " + String.join("; ", errors));
        }
    }

    static List<String> findErrors(CraftPlanGraph graph) {
        List<String> errors = new ArrayList<>();
        Map<NodeId, CraftNode> nodes = new HashMap<>();
        Map<InputPortId, InputDemand> inputs = new HashMap<>();
        Map<OutputPortId, OutputDeclaration> outputs = new HashMap<>();

        for (CraftNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                errors.add("duplicate node " + node.id());
            }
            for (InputDemand input : node.inputs()) {
                if (!input.id().nodeId().equals(node.id())) {
                    errors.add("input belongs to another node " + input.id());
                }
                if (inputs.putIfAbsent(input.id(), input) != null) {
                    errors.add("duplicate input " + input.id());
                }
            }
            for (OutputDeclaration output : node.outputs()) {
                if (!output.id().nodeId().equals(node.id())) {
                    errors.add("output belongs to another node " + output.id());
                }
                if (outputs.putIfAbsent(output.id(), output) != null) {
                    errors.add("duplicate output " + output.id());
                }
            }
        }

        Set<AllocationId> allocationIds = new HashSet<>();
        Map<InputPortId, Integer> suppliedByInput = new HashMap<>();
        Map<OutputPortId, Integer> consumedByOutput = new HashMap<>();
        for (MaterialAllocation allocation : graph.allocations()) {
            if (!allocationIds.add(allocation.id())) {
                errors.add("duplicate allocation " + allocation.id());
            }
            InputDemand input = inputs.get(allocation.consumer());
            if (input == null) {
                errors.add("missing consumer " + allocation.consumer());
                continue;
            }
            if (!IngredientMatcher.test(input.ingredient(), allocation.material().toStack(1))) {
                errors.add("material does not satisfy input " + allocation.consumer());
            }
            suppliedByInput.merge(allocation.consumer(), allocation.quantity(), Integer::sum);

            if (allocation.source() instanceof MaterialSource.InitialPool initial) {
                if (!initial.key().equals(allocation.material())) {
                    errors.add("initial source material mismatch " + allocation.id());
                }
            } else if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                OutputDeclaration output = outputs.get(producer.outputPort());
                if (output == null) {
                    errors.add("missing producer output " + producer.outputPort());
                    continue;
                }
                if (!output.material().equals(allocation.material())) {
                    errors.add("producer material mismatch " + allocation.id());
                }
                consumedByOutput.merge(producer.outputPort(), allocation.quantity(), Integer::sum);
            }
        }

        Map<InputPortId, Integer> unresolvedByInput = new HashMap<>();
        for (UnresolvedDemand unresolved : graph.unresolvedDemands()) {
            InputDemand input = inputs.get(unresolved.consumer());
            if (input == null) {
                errors.add("unresolved demand has missing consumer " + unresolved.consumer());
                continue;
            }
            unresolvedByInput.merge(unresolved.consumer(), unresolved.quantity(), Integer::sum);
        }

        for (InputDemand input : inputs.values()) {
            int supplied = suppliedByInput.getOrDefault(input.id(), 0);
            int unresolved = unresolvedByInput.getOrDefault(input.id(), 0);
            if (supplied + unresolved != input.quantity()) {
                errors.add("input quantity mismatch " + input.id() + " expected="
                        + input.quantity() + " actual=" + (supplied + unresolved));
            }
        }

        for (RootDemand root : graph.rootDemands()) {
            for (RootAllocation allocation : root.allocations()) {
                if (!IngredientMatcher.test(root.ingredient(), allocation.material().toStack(1))) {
                    errors.add("root material mismatch");
                }
                if (allocation.source() instanceof MaterialSource.InitialPool initial) {
                    if (!initial.key().equals(allocation.material())) errors.add("root initial source mismatch");
                } else if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                    OutputDeclaration output = outputs.get(producer.outputPort());
                    if (output == null) {
                        errors.add("root references missing output " + producer.outputPort());
                        continue;
                    }
                    if (!output.material().equals(allocation.material())) errors.add("root producer material mismatch");
                    consumedByOutput.merge(producer.outputPort(), allocation.quantity(), Integer::sum);
                }
            }
            int rootSupplied = root.allocations().stream().mapToInt(RootAllocation::quantity).sum();
            if (rootSupplied + root.unresolvedQuantity() != root.quantity()) {
                errors.add("root quantity mismatch expected=" + root.quantity() + " actual="
                        + (rootSupplied + root.unresolvedQuantity()));
            }
        }

        for (OutputDeclaration output : outputs.values()) {
            int consumed = consumedByOutput.getOrDefault(output.id(), 0);
            if (consumed > output.quantity()) {
                errors.add("output overallocated " + output.id() + " capacity="
                        + output.quantity() + " allocated=" + consumed);
            }
        }

        validateTopologicalOrder(graph, nodes.keySet(), errors);
        return List.copyOf(errors);
    }

    private static void validateTopologicalOrder(CraftPlanGraph graph, Set<NodeId> nodes, List<String> errors) {
        if (graph.topologicalOrder().size() != nodes.size()
                || !new HashSet<>(graph.topologicalOrder()).equals(nodes)) {
            errors.add("topological order must contain every node exactly once");
            return;
        }
        Map<NodeId, Integer> positions = new HashMap<>();
        for (int i = 0; i < graph.topologicalOrder().size(); i++) {
            NodeId id = graph.topologicalOrder().get(i);
            if (positions.put(id, i) != null) {
                errors.add("duplicate node in topological order " + id);
            }
        }
        for (MaterialAllocation allocation : graph.allocations()) {
            if (!(allocation.source() instanceof MaterialSource.ProducerOutput producer)) continue;
            Integer producerIndex = positions.get(producer.outputPort().nodeId());
            Integer consumerIndex = positions.get(allocation.consumer().nodeId());
            if (producerIndex != null && consumerIndex != null && producerIndex >= consumerIndex) {
                errors.add("dependency appears after consumer " + producer.outputPort().nodeId()
                        + " -> " + allocation.consumer().nodeId());
            }
        }
    }
}
