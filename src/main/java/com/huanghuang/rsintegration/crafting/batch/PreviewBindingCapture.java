package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.batch.CraftPlanningApplicationService.BindingAwareGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** 在服务器线程分批过滤固定配方，后台只接收已完成的值图。 */
final class PreviewBindingCapture {
    private final ImmutableRecipeGraph graph;
    private final Predicate<RecipeNode> runtimeAvailable;
    private final Predicate<RecipeNode> machineAvailable;
    private final Iterator<Map.Entry<MaterialRef, List<RecipeNode>>> outputs;
    private final Map<MaterialRef, List<RecipeNode>> filtered = new LinkedHashMap<>();
    private final Set<ResourceLocation> blocked = new LinkedHashSet<>();
    private Map.Entry<MaterialRef, List<RecipeNode>> output;
    private final List<RecipeNode> usable = new ArrayList<>();
    private int nextCandidate;
    private boolean hasRuntimeCandidate;
    private boolean changed;

    PreviewBindingCapture(ImmutableRecipeGraph graph, Predicate<RecipeNode> runtimeAvailable,
                          Predicate<RecipeNode> machineAvailable) {
        this.graph = graph;
        this.runtimeAvailable = runtimeAvailable;
        this.machineAvailable = machineAvailable;
        this.outputs = graph.recipesByOutput().entrySet().iterator();
    }

    boolean advance(BooleanSupplier hasBudget) {
        while (hasBudget.getAsBoolean()) {
            if (output == null) {
                if (!outputs.hasNext()) return true;
                output = outputs.next();
            }
            List<RecipeNode> candidates = output.getValue();
            if (nextCandidate < candidates.size()) {
                RecipeNode candidate = candidates.get(nextCandidate++);
                if (runtimeAvailable.test(candidate)) {
                    hasRuntimeCandidate = true;
                    if (machineAvailable.test(candidate)) usable.add(candidate);
                }
            } else {
                changed |= usable.size() != candidates.size();
                if (!usable.isEmpty()) filtered.put(output.getKey(), List.copyOf(usable));
                else if (hasRuntimeCandidate) blocked.add(output.getKey().itemId());
                output = null;
                usable.clear();
                nextCandidate = 0;
                hasRuntimeCandidate = false;
            }
        }
        return false;
    }

    BindingAwareGraph result() {
        if (output != null || outputs.hasNext()) throw new IllegalStateException("配方绑定采集尚未完成");
        return new BindingAwareGraph(changed ? new ImmutableRecipeGraph(filtered) : graph, Set.copyOf(blocked));
    }
}
