package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

/** 生产者索引及同一配方 ID 的附加输出变体，避免裁剪时重扫所有输出桶。 */
final class RecipeProducerIndex {
    record InputRef(RecipeNode recipe, int port) {}
    private final Map<ResourceLocation, List<InputRef>> dependents;
    private final List<RecipeNode> inputFree;
    private final boolean uncertainOutputs;
    private final Set<RecipeNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ResourceLocation, List<RecipeNode>> byItem;
    private final Map<ResourceLocation, List<MaterialRef>> additionalOutputs;
    private final Map<ResourceLocation, List<RecipeNode>> coProductProducers;

    private RecipeProducerIndex(Map<ResourceLocation, List<RecipeNode>> byItem,
                                Map<ResourceLocation, List<MaterialRef>> additionalOutputs,
                                Map<ResourceLocation, List<RecipeNode>> coProductProducers,
                                Map<ResourceLocation, List<InputRef>> dependents,
                                List<RecipeNode> inputFree, boolean uncertainOutputs) {
        this.byItem = Map.copyOf(byItem);
        this.additionalOutputs = Map.copyOf(additionalOutputs);
        this.coProductProducers = Map.copyOf(coProductProducers);
        this.dependents = Map.copyOf(dependents);
        this.inputFree = List.copyOf(inputFree);
        this.uncertainOutputs = uncertainOutputs;
        byItem.values().forEach(nodes::addAll);
    }

    static RecipeProducerIndex build(ImmutableRecipeGraph graph) {
        return build(graph, PlanningThreadContext::throwIfCancelled);
    }

    static RecipeProducerIndex build(ImmutableRecipeGraph graph, Runnable budgetCheck) {
        Map<ResourceLocation, List<RecipeNode>> byItem = new HashMap<>();
        Map<ResourceLocation, Set<MaterialRef>> variants = new HashMap<>();
        Map<ResourceLocation, List<RecipeNode>> coProducts = new HashMap<>();
        Map<ResourceLocation, List<InputRef>> dependents = new HashMap<>();
        List<RecipeNode> inputFree = new ArrayList<>();
        boolean uncertainOutputs = false;
        long edges = 0;
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            budgetCheck.run();
            for (RecipeNode node : entry.getValue()) {
                byItem.computeIfAbsent(node.output().itemId(), ignored -> new ArrayList<>()).add(node);
                if (node.inputs().isEmpty()) inputFree.add(node);
                if ("smithing".equals(node.modTypeId())) uncertainOutputs = true;
                for (int port = 0; port < node.inputs().size(); port++) {
                    Set<ResourceLocation> items = new LinkedHashSet<>();
                    for (MaterialRef alternative : node.inputs().get(port).alternatives()) {
                        budgetCheck.run();
                        if (alternative.runtimeNbt() || alternative.constrainedNbt()) uncertainOutputs = true;
                        if (items.add(alternative.itemId())) {
                            dependents.computeIfAbsent(alternative.itemId(), ignored -> new ArrayList<>())
                                    .add(new InputRef(node, port));
                            edges++;
                        }
                    }
                }
                // 只供乐观可达性分析使用，不把保证副产物变为可直接点选的生产目标。
                Set<ResourceLocation> sideItems = new LinkedHashSet<>();
                for (MaterialRef output : node.guaranteedOutputs().keySet()) {
                    if (output.runtimeNbt() || output.constrainedNbt()) uncertainOutputs = true;
                    if (!output.itemId().equals(node.output().itemId()) && sideItems.add(output.itemId())) {
                        coProducts.computeIfAbsent(output.itemId(), ignored -> new ArrayList<>()).add(node);
                    }
                }
                RecipeNode canonical = graph.recipesById().get(node.recipeId());
                if (canonical == null || !canonical.output().equals(entry.getKey())) {
                    variants.computeIfAbsent(node.recipeId(), ignored -> new LinkedHashSet<>())
                            .add(entry.getKey());
                }
            }
        }
        byItem.replaceAll((ignored, nodes) -> List.copyOf(nodes));
        Map<ResourceLocation, List<MaterialRef>> additionalOutputs = new HashMap<>();
        variants.forEach((recipeId, outputs) -> additionalOutputs.put(recipeId, List.copyOf(outputs)));
        coProducts.replaceAll((ignored, nodes) -> List.copyOf(nodes));
        dependents.replaceAll((ignored, refs) -> List.copyOf(refs));
        PlanningLookupCache.recordProducerIndexBuild(edges);
        return new RecipeProducerIndex(byItem, additionalOutputs, coProducts, dependents, inputFree, uncertainOutputs);
    }

    List<RecipeNode> producers(ResourceLocation itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

    List<RecipeNode> coProductProducers(ResourceLocation itemId) {
        return coProductProducers.getOrDefault(itemId, List.of());
    }

    List<InputRef> dependents(ResourceLocation itemId) { return dependents.getOrDefault(itemId, List.of()); }
    List<RecipeNode> inputFree() { return inputFree; }
    boolean uncertainOutputs() { return uncertainOutputs; }
    boolean contains(RecipeNode node) { return nodes.contains(node); }

    List<MaterialRef> outputs(ImmutableRecipeGraph graph, ResourceLocation recipeId) {
        List<MaterialRef> additional = additionalOutputs.getOrDefault(recipeId, List.of());
        RecipeNode canonical = graph.recipesById().get(recipeId);
        if (canonical == null) return additional;
        if (additional.isEmpty()) return List.of(canonical.output());
        List<MaterialRef> result = new ArrayList<>(additional.size() + 1);
        result.add(canonical.output());
        result.addAll(additional);
        return result;
    }
}
