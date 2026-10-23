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

/** 生产者索引及同一配方 ID 的附加输出变体，避免裁剪时重扫所有输出桶。 */
final class RecipeProducerIndex {
    private final Map<ResourceLocation, List<RecipeNode>> byItem;
    private final Map<ResourceLocation, List<MaterialRef>> additionalOutputs;

    private RecipeProducerIndex(Map<ResourceLocation, List<RecipeNode>> byItem,
                                Map<ResourceLocation, List<MaterialRef>> additionalOutputs) {
        this.byItem = Map.copyOf(byItem);
        this.additionalOutputs = Map.copyOf(additionalOutputs);
    }

    static RecipeProducerIndex build(ImmutableRecipeGraph graph) {
        Map<ResourceLocation, List<RecipeNode>> byItem = new HashMap<>();
        Map<ResourceLocation, Set<MaterialRef>> variants = new HashMap<>();
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            PlanningThreadContext.throwIfCancelled();
            for (RecipeNode node : entry.getValue()) {
                byItem.computeIfAbsent(node.output().itemId(), ignored -> new ArrayList<>()).add(node);
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
        return new RecipeProducerIndex(byItem, additionalOutputs);
    }

    List<RecipeNode> producers(ResourceLocation itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

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
