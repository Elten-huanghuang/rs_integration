package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并结构完全相同的重复通用合成节点。只压缩可在不改变材料、NBT、端口和
 * 替代配方语义时安全合并的节点；任何校验失败都会保留原图。
 */
public final class CraftPlanGraphCompactor {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CraftPlanGraphCompactor() {}

    public static CraftPlanGraph compact(CraftPlanGraph graph) {
        if (graph == null || graph.nodes().size() < 2) return graph;

        Map<NodeSignature, List<CraftNode>> groups = new LinkedHashMap<>();
        for (CraftNode node : graph.nodes()) {
            NodeSignature signature = NodeSignature.from(node);
            groups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(node);
        }

        Map<NodeId, NodeId> remap = new LinkedHashMap<>();
        for (List<CraftNode> group : groups.values()) {
            if (!canMerge(group, graph.allocations())) {
                for (CraftNode node : group) remap.put(node.id(), node.id());
                continue;
            }
            NodeId canonical = group.get(0).id();
            for (CraftNode node : group) remap.put(node.id(), canonical);
        }
        if (remap.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()))) {
            return graph;
        }

        Map<NodeId, List<CraftNode>> mergedMembers = new LinkedHashMap<>();
        for (CraftNode node : graph.nodes()) {
            mergedMembers.computeIfAbsent(remap.get(node.id()), ignored -> new ArrayList<>()).add(node);
        }

        List<CraftNode> nodes = new ArrayList<>(mergedMembers.size());
        for (List<CraftNode> members : mergedMembers.values()) {
            nodes.add(mergeNode(members));
        }

        List<MaterialAllocation> allocations = mergeAllocations(graph.allocations(), remap);
        List<RootDemand> roots = mergeRoots(graph.rootDemands(), remap);
        List<UnresolvedDemand> unresolved = graph.unresolvedDemands().stream()
                .map(demand -> new UnresolvedDemand(remapConsumer(demand.consumer(), remap),
                        demand.ingredient(), demand.quantity(), demand.displayHint()))
                .toList();
        Set<NodeId> ordered = new LinkedHashSet<>();
        for (NodeId nodeId : graph.topologicalOrder()) ordered.add(remap.get(nodeId));

        CraftPlanGraph result = new CraftPlanGraph(graph.version(), nodes, allocations, roots,
                unresolved, List.copyOf(ordered), graph.planningRevision());
        try {
            CraftPlanValidator.validate(result);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "[RSI-GraphCompactor] rejected unsafe compaction nodes={} -> {}, allocations={} -> {}",
                    graph.nodes().size(), nodes.size(), graph.allocations().size(),
                    allocations.size(), failure);
            return graph;
        }
        if (result.nodes().size() == graph.nodes().size()) return graph;
        LOGGER.debug(
                "[RSI-GraphCompactor] compacted nodes {} -> {}, allocations {} -> {}, roots {} -> {}",
                graph.nodes().size(), result.nodes().size(), graph.allocations().size(),
                result.allocations().size(), graph.rootDemands().size(), result.rootDemands().size());
        return result;
    }

    private static boolean canMerge(List<CraftNode> group, List<MaterialAllocation> allocations) {
        if (group.size() < 2) return false;
        CraftNode first = group.get(0);
        if (!ModType.GENERIC.id().equals(first.modTypeId())) return false;
        if (first.inputs().stream().anyMatch(input -> input.role() == DemandRole.CATALYST)) {
            return false;
        }
        long executions = 0L;
        Set<NodeId> ids = new HashSet<>();
        for (CraftNode node : group) {
            if (!isExecutionScaled(node)) return false;
            executions += node.executions();
            if (executions > Integer.MAX_VALUE) return false;
            ids.add(node.id());
        }
        for (MaterialAllocation allocation : allocations) {
            if (!(allocation.source() instanceof MaterialSource.ProducerOutput producer)) continue;
            if (ids.contains(allocation.consumer().nodeId())
                    && ids.contains(producer.outputPort().nodeId())) {
                return false;
            }
        }
        return true;
    }

    private static CraftNode mergeNode(List<CraftNode> members) {
        CraftNode first = members.get(0);
        int executions = members.stream().mapToInt(CraftNode::executions).sum();
        List<InputDemand> inputs = new ArrayList<>(first.inputs().size());
        for (InputDemand input : first.inputs()) {
            long quantity = 0L;
            for (CraftNode member : members) {
                InputDemand matching = inputForPort(member, input.id().index());
                quantity = SaturatingLongMath.add(quantity, matching.quantity());
            }
            inputs.add(new InputDemand(input.id(), input.ingredient(), quantity,
                    input.role(), input.displayHint()));
        }
        List<OutputDeclaration> outputs = new ArrayList<>(first.outputs().size());
        for (OutputDeclaration output : first.outputs()) {
            long quantity = 0L;
            for (CraftNode member : members) {
                OutputDeclaration matching = outputForPort(member, output.id().index());
                quantity = SaturatingLongMath.add(quantity, matching.quantity());
            }
            outputs.add(new OutputDeclaration(output.id(), output.material(), quantity, output.kind()));
        }
        return new CraftNode(first.id(), first.recipeId(), first.modTypeId(),
                first.recipeTypeId(), executions, first.alternativeIds(),
                first.alternativeModTypeIds(), first.inferMode(), first.syntheticInput(),
                first.syntheticOutput(), inputs, outputs, first.magicCosts());
    }

    private static InputDemand inputForPort(CraftNode node, int port) {
        return node.inputs().stream()
                .filter(input -> input.id().index() == port)
                .findFirst().orElseThrow();
    }

    private static boolean isExecutionScaled(CraftNode node) {
        int executions = Math.max(1, node.executions());
        for (InputDemand input : node.inputs()) {
            if (input.role() == DemandRole.CATALYST) return false;
            if (input.quantity() % executions != 0L) return false;
        }
        for (OutputDeclaration output : node.outputs()) {
            if (output.quantity() % executions != 0L) return false;
        }
        return true;
    }

    private static OutputDeclaration outputForPort(CraftNode node, int port) {
        return node.outputs().stream()
                .filter(output -> output.id().index() == port)
                .findFirst().orElseThrow();
    }

    private static List<MaterialAllocation> mergeAllocations(
            List<MaterialAllocation> allocations, Map<NodeId, NodeId> remap) {
        Map<AllocationKey, Long> quantities = new LinkedHashMap<>();
        for (MaterialAllocation allocation : allocations) {
            InputPortId consumer = remapConsumer(allocation.consumer(), remap);
            MaterialSource source = remapSource(allocation.source(), remap);
            AllocationKey key = new AllocationKey(consumer, source, allocation.material());
            quantities.merge(key, allocation.quantity(), SaturatingLongMath::add);
        }
        List<MaterialAllocation> result = new ArrayList<>(quantities.size());
        long id = 0L;
        for (Map.Entry<AllocationKey, Long> entry : quantities.entrySet()) {
            AllocationKey key = entry.getKey();
            result.add(new MaterialAllocation(new AllocationId(id++), key.consumer(),
                    key.source(), key.material(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static List<RootDemand> mergeRoots(List<RootDemand> roots, Map<NodeId, NodeId> remap) {
        List<RootDemand> result = new ArrayList<>(roots.size());
        for (RootDemand root : roots) {
            List<RootAllocation> allocations = root.allocations().stream()
                    .map(allocation -> new RootAllocation(
                            remapSource(allocation.source(), remap),
                            allocation.material(), allocation.quantity()))
                    .toList();
            result.add(new RootDemand(root.ingredient(), root.quantity(),
                    root.unresolvedQuantity(), root.displayHint(), allocations, root.role()));
        }
        return List.copyOf(result);
    }

    private static InputPortId remapConsumer(InputPortId consumer, Map<NodeId, NodeId> remap) {
        return new InputPortId(remap.getOrDefault(consumer.nodeId(), consumer.nodeId()),
                consumer.index());
    }

    private static MaterialSource remapSource(MaterialSource source, Map<NodeId, NodeId> remap) {
        if (!(source instanceof MaterialSource.ProducerOutput producer)) return source;
        OutputPortId oldPort = producer.outputPort();
        return new MaterialSource.ProducerOutput(new OutputPortId(
                remap.getOrDefault(oldPort.nodeId(), oldPort.nodeId()), oldPort.index()));
    }

    private record AllocationKey(InputPortId consumer, MaterialSource source,
                                 MaterialKey material) {}

    private record InputSignature(int port, String ingredient, long perExecution,
                                  DemandRole role, String display) {
        private static InputSignature from(InputDemand input, int executions) {
            return new InputSignature(input.id().index(), ingredientSignature(input.ingredient()),
                    input.quantity() / Math.max(1, executions),
                    input.role(), stackSignature(input.displayHint()));
        }
    }

    private record OutputSignature(int port, MaterialKey material, long perExecution,
                                   OutputKind kind) {
        private static OutputSignature from(OutputDeclaration output, int executions) {
            return new OutputSignature(output.id().index(), output.material(),
                    output.quantity() / Math.max(1, executions), output.kind());
        }
    }

    private record NodeSignature(String recipeId, String modTypeId, String recipeTypeId,
                                 List<String> alternatives,
                                 List<String> alternativeModTypes, boolean inferMode,
                                 String syntheticInput, String syntheticOutput,
                                 List<InputSignature> inputs,
                                 List<OutputSignature> outputs,
                                 List<MagicResourceCost> magicCosts,
                                 boolean hasCatalyst) {
        private static NodeSignature from(CraftNode node) {
            return new NodeSignature(node.recipeId().toString(), node.modTypeId(),
                    node.recipeTypeId() == null ? "" : node.recipeTypeId().toString(),
                    node.alternativeIds().stream().map(Object::toString).toList(),
                    node.alternativeModTypeIds(),
                    node.inferMode(), stackSignature(node.syntheticInput()),
                    stackSignature(node.syntheticOutput()),
                    node.inputs().stream().map(input -> InputSignature.from(input,
                            node.executions())).toList(),
                    node.outputs().stream().map(output ->
                            OutputSignature.from(output, node.executions())).toList(),
                    node.magicCosts(), node.inputs().stream().anyMatch(
                            input -> input.role() == DemandRole.CATALYST));
        }
    }

    private static String ingredientSignature(Ingredient ingredient) {
        if (ingredient == null) return "null";
        try {
            return ingredient.getClass().getName() + "|" + ingredient.toJson();
        } catch (RuntimeException ignored) {
            return ingredient.getClass().getName() + "|identity:"
                    + System.identityHashCode(ingredient);
        }
    }

    private static String stackSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                + "|" + (stack.getTag() == null ? "" : stack.getTag());
    }
}
