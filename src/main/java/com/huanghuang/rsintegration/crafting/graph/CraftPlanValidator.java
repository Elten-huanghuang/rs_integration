package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;

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
        var work = start(graph);
        work.advance(Long.MAX_VALUE);
        return List.copyOf(work.errors);
    }

    public static Validation start(CraftPlanGraph graph) { return new Validation(graph); }

    public static final class Validation extends GraphWork {
        private final List<String> errors = new ArrayList<>();

        public void check() {
            requireComplete();
            if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid craft plan graph: " + String.join("; ", errors));
        }

        private Validation(CraftPlanGraph graph) {
            Map<NodeId, CraftNode> nodes = new HashMap<>();
            Map<InputPortId, InputDemand> inputs = new HashMap<>();
            Map<OutputPortId, OutputDeclaration> outputs = new HashMap<>();

            scan(graph.nodes()::iterator, node -> {
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
            });

            Set<AllocationId> allocationIds = new HashSet<>();
            Map<InputPortId, Long> suppliedByInput = new HashMap<>();
            Map<OutputPortId, Long> consumedByOutput = new HashMap<>();
            scan(graph.allocations()::iterator, allocation -> {
                if (!allocationIds.add(allocation.id())) {
                    errors.add("duplicate allocation " + allocation.id());
                }
                InputDemand input = inputs.get(allocation.consumer());
                if (input == null) {
                    errors.add("missing consumer " + allocation.consumer());
                    return;
                }
                if (!IngredientMatcher.test(input.ingredient(), allocation.material())) {
                    errors.add("material does not satisfy input " + allocation.consumer());
                }
                suppliedByInput.merge(allocation.consumer(), allocation.quantity(),
                        SaturatingLongMath::add);

                if (allocation.source() instanceof MaterialSource.InitialPool initial) {
                    if (!initial.key().equals(allocation.material())) {
                        errors.add("initial source material mismatch " + allocation.id());
                    }
                } else if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                    OutputDeclaration output = outputs.get(producer.outputPort());
                    if (output == null) {
                        errors.add("missing producer output " + producer.outputPort());
                        return;
                    }
                    if (!output.material().equals(allocation.material())) {
                        errors.add("producer material mismatch " + allocation.id());
                    }
                    consumedByOutput.merge(producer.outputPort(), allocation.quantity(),
                            SaturatingLongMath::add);
                }
            });

            Map<InputPortId, Long> unresolvedByInput = new HashMap<>();
            scan(graph.unresolvedDemands()::iterator, unresolved -> {
                InputDemand input = inputs.get(unresolved.consumer());
                if (input == null) {
                    errors.add("unresolved demand has missing consumer " + unresolved.consumer());
                    return;
                }
                unresolvedByInput.merge(unresolved.consumer(), (long) unresolved.quantity(),
                        SaturatingLongMath::add);
            });

            scan(inputs.values()::iterator, input -> {
                long supplied = suppliedByInput.getOrDefault(input.id(), 0L);
                long unresolved = unresolvedByInput.getOrDefault(input.id(), 0L);
                if (supplied + unresolved != input.quantity()) {
                    errors.add("input quantity mismatch " + input.id() + " expected="
                            + input.quantity() + " actual=" + (supplied + unresolved));
                }
            });

            scan(graph.rootDemands()::iterator, root -> {
                for (RootAllocation allocation : root.allocations()) {
                    if (!IngredientMatcher.test(root.ingredient(), allocation.material())) {
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
                        consumedByOutput.merge(producer.outputPort(), allocation.quantity(),
                                SaturatingLongMath::add);
                    }
                }
                long rootSupplied = 0L;
                for (RootAllocation allocation : root.allocations()) {
                    rootSupplied = SaturatingLongMath.add(rootSupplied, allocation.quantity());
                }
                if (rootSupplied + root.unresolvedQuantity() != root.quantity()) {
                    errors.add("root quantity mismatch expected=" + root.quantity() + " actual="
                            + (rootSupplied + root.unresolvedQuantity()));
                }
            });

            scan(outputs.values()::iterator, output -> {
                long consumed = consumedByOutput.getOrDefault(output.id(), 0L);
                if (consumed > output.quantity()) {
                    errors.add("output overallocated " + output.id() + " capacity="
                            + output.quantity() + " allocated=" + consumed);
                }
            });

            Map<NodeId, Integer> positions = new HashMap<>();
            scan(graph.topologicalOrder()::iterator, id -> {
                if (!nodes.containsKey(id) || positions.putIfAbsent(id, positions.size()) != null)
                    errors.add("invalid node in topological order " + id);
            });
            once(() -> {
                if (positions.size() != nodes.size()) errors.add("topological order must contain every node exactly once");
            });
            scan(graph.allocations()::iterator, allocation -> {
                if (!(allocation.source() instanceof MaterialSource.ProducerOutput producer)) return;
                Integer producerIndex = positions.get(producer.outputPort().nodeId());
                Integer consumerIndex = positions.get(allocation.consumer().nodeId());
                if (producerIndex != null && consumerIndex != null && producerIndex >= consumerIndex) {
                    errors.add("dependency appears after consumer " + producer.outputPort().nodeId()
                            + " -> " + allocation.consumer().nodeId());
                }
            });
        }
    }
}
